package com.automend;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.ExperienceBottleEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * DarkSpider Auto Mend
 *
 * Auto Mend (passive): wear full armour and throw XP bottles yourself. A piece is taken off as
 * soon as it is fully repaired; when every piece is repaired, all armour goes back on.
 *
 * One-Click Mend: press the mend key. The mod throws your hotbar XP bottles at your feet,
 * swaps armour the same way, and stops when done. Press the key again to stop early.
 *
 * Panic: puts armour back on (key, or automatically when your health is low).
 *
 * All swaps are sent as direct inventory clicks; the inventory screen is never opened.
 * Open the settings screen with the GUI key (default Right Shift).
 */
public class AutoMendClient implements ClientModInitializer {

    static final int[] ARMOR_HANDLER_SLOTS = {5, 6, 7, 8}; // head, chest, legs, feet
    static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static final int CLICK_DELAY_TICKS = 3;
    private static final int QUIET_TICKS_BEFORE_RESTORE = 30;
    private static final int SESSION_MAX_TICKS = 900; // 45 seconds safety stop
    private static final int PANIC_LOCK_TICKS = 100;

    /** true = every piece is mended to FULL durability (the target setting is ignored). */
    private static final boolean HEAL_FULL = false;
    /** true = armour goes straight back on the moment you take damage. */
    private static final boolean RESTORE_ON_HIT = true;
    /** After a hit or a stall, wait this long before taking armour off again. */
    private static final int HOLD_OFF_TICKS = 100;
    /** If worn armour gains no durability for this long while pieces are off, put them back on. */
    private static final int NO_PROGRESS_TICKS = 100;

    static KeyBinding guiKey;
    static KeyBinding toggleKey;
    static KeyBinding mendKey;
    static KeyBinding panicKey;

    /** Shown in the HUD. */
    static String status = "Idle";
    /** Armour pieces this mod took off, so the same ones can be put back on. */
    static final Map<EquipmentSlot, Item> removed = new EnumMap<>(EquipmentSlot.class);

    private static int cooldown = 0;
    private static int quietTicks = 0;
    private static int panicLock = 0;

    private static boolean restoring = false;
    private static int holdOff = 0;
    private static int lastTotal = -1;
    private static int noProgress = 0;

    private static boolean session = false;
    private static boolean stopRequested = false;
    private static int throwCooldown = 0;
    private static int sessionTicks = 0;
    private static int savedSlot = 0;
    private static int lastBottleSlot = 8;
    private static float savedPitch = 0f;

    @Override
    public void onInitializeClient() {
        Config.load();
        guiKey = register("key.automend.gui", GLFW.GLFW_KEY_RIGHT_SHIFT);
        toggleKey = register("key.automend.toggle", GLFW.GLFW_KEY_M);
        mendKey = register("key.automend.mend", GLFW.GLFW_KEY_R);
        panicKey = register("key.automend.panic", GLFW.GLFW_KEY_V);
        ClientTickEvents.END_CLIENT_TICK.register(AutoMendClient::tick);
        HudRenderCallback.EVENT.register(Hud::render);
    }

    private static KeyBinding register(String id, int key) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyBinding(id, InputUtil.Type.KEYSYM, key, "category.automend"));
    }

    // ---- Main tick --------------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;

        while (guiKey.wasPressed()) {
            if (client.currentScreen == null) client.setScreen(new DarkSpiderScreen());
        }
        while (toggleKey.wasPressed()) {
            Config.enabled = !Config.enabled;
            Config.save();
            if (player != null) {
                player.sendMessage(Text.literal("DarkSpider Auto Mend: " + (Config.enabled ? "ON" : "OFF")), true);
            }
        }
        boolean mendPressed = false;
        while (mendKey.wasPressed()) mendPressed = true;
        boolean panicPressed = false;
        while (panicKey.wasPressed()) panicPressed = true;

        if (player == null || client.interactionManager == null || client.world == null) {
            session = false;
            stopRequested = false;
            restoring = false;
            holdOff = 0;
            noProgress = 0;
            lastTotal = -1;
            removed.clear();
            status = "Idle";
            return;
        }
        if (client.currentScreen != null || player.isCreative() || player.isSpectator()) return;

        boolean canClick = cooldown == 0;
        if (cooldown > 0) cooldown--;
        PlayerScreenHandler handler = player.playerScreenHandler;

        if (holdOff > 0) holdOff--;

        // Took damage: put armour back on right away and stay dressed for a while.
        if (RESTORE_ON_HIT && player.hurtTime > 0) {
            holdOff = HOLD_OFF_TICKS;
            if (!removed.isEmpty()) restoring = true;
            if (session) stopRequested = true;
        }

        // Master switch off: finish putting armour back on, then do nothing.
        if (!Config.enabled) {
            status = "OFF";
            if (!removed.isEmpty()) {
                if (canClick) restoreStep(client, player, handler);
            } else if (session) {
                endSession(player, "DarkSpider Auto Mend: off");
            }
            return;
        }

        // Panic: key press, or low health while the mod is busy.
        boolean busy = session || !removed.isEmpty();
        if (panicPressed || (Config.panicAuto && busy && player.getHealth() <= Config.panicHearts * 2f)) {
            panicLock = PANIC_LOCK_TICKS;
            if (session) endSession(player, "DarkSpider Auto Mend: PANIC - armour restored");
            else player.sendMessage(Text.literal("DarkSpider Auto Mend: PANIC"), true);
        }
        if (panicLock > 0) {
            panicLock--;
            status = "PANIC";
            if (!removed.isEmpty() && canClick) restoreStep(client, player, handler);
            return;
        }

        // Enemy guard: never run with another player close.
        boolean enemy = Config.enemyGuard && enemyNear(client, player);
        if (enemy) {
            status = "Enemy nearby";
            if (session) {
                stopRequested = true;
            } else {
                if (!removed.isEmpty() && canClick) restoreStep(client, player, handler);
                return;
            }
        }

        if (mendPressed && Config.oneClick && !enemy) {
            if (session) stopRequested = true; else startSession(player);
        }

        if (session) {
            sessionTick(client, player, handler, canClick);
            return;
        }
        if (!Config.passive) {
            status = "Idle";
            if (!removed.isEmpty() && canClick) restoreStep(client, player, handler);
            return;
        }
        passiveTick(client, player, handler, canClick);
    }

    // ---- Auto Mend (passive) ----------------------------------------------------------------

    private static void passiveTick(MinecraftClient client, ClientPlayerEntity player,
                                    PlayerScreenHandler handler, boolean canClick) {
        boolean xpActive = xpNearby(client, player);
        if (xpActive) quietTicks = 0; else quietTicks++;

        List<Integer> active = activeSlots(handler);
        boolean needs = anyNeeds(handler, active, Config.target);
        trackProgress(handler, active);

        if (!removed.isEmpty()) {
            boolean xpStopped = !xpActive && quietTicks > QUIET_TICKS_BEFORE_RESTORE;
            boolean stalled = noProgress > NO_PROGRESS_TICKS;
            if (stalled) holdOff = HOLD_OFF_TICKS;
            if (!needs || xpStopped || stalled) restoring = true;
        }

        // Once restoring starts, put EVERYTHING back before doing anything else.
        if (restoring) {
            if (removed.isEmpty()) {
                restoring = false;
            } else {
                status = "Restoring armour";
                if (canClick) restoreStep(client, player, handler);
                return;
            }
        }

        if (!xpActive || !needs) {
            status = "Idle";
            return;
        }
        if (holdOff > 0) {
            status = "Holding off";
            return;
        }
        status = "Mending (XP)";
        if (canClick && active.size() > 1) {
            for (int slotId : active) {
                ItemStack s = handler.getSlot(slotId).getStack();
                if (done(s, Config.target)) {
                    takeOff(client, player, handler, slotId, s);
                    return;
                }
            }
        }
    }

    // ---- One-Click Mend ---------------------------------------------------------------------

    private static void startSession(ClientPlayerEntity player) {
        if (findBottleSlot(player) == -1 && !hasBottleInInventory(player)) {
            player.sendMessage(Text.literal("DarkSpider Auto Mend: no XP bottles"), true);
            return;
        }
        PlayerScreenHandler handler = player.playerScreenHandler;
        if (!anyNeeds(handler, activeSlots(handler), Config.target)) {
            player.sendMessage(Text.literal("DarkSpider Auto Mend: armour already " + targetText()), true);
            return;
        }
        savedSlot = player.getInventory().selectedSlot;
        savedPitch = player.getPitch();
        session = true;
        stopRequested = false;
        restoring = false;
        throwCooldown = 0;
        sessionTicks = 0;
        noProgress = 0;
        lastTotal = -1;
        player.sendMessage(Text.literal("DarkSpider Auto Mend: mending..."), true);
    }

    private static void sessionTick(MinecraftClient client, ClientPlayerEntity player,
                                    PlayerScreenHandler handler, boolean canClick) {
        if (throwCooldown > 0) throwCooldown--;
        if (++sessionTicks > SESSION_MAX_TICKS) stopRequested = true;

        List<Integer> active = activeSlots(handler);
        boolean needs = anyNeeds(handler, active, Config.target);
        trackProgress(handler, active);
        if (!removed.isEmpty() && noProgress > NO_PROGRESS_TICKS) stopRequested = true;

        int bottleSlot = findBottleSlot(player);
        if (bottleSlot != -1) lastBottleSlot = bottleSlot;

        // Out of hotbar bottles: pull more in from the inventory.
        if (bottleSlot == -1 && needs && !stopRequested && Config.refill) {
            if (!canClick) return;
            if (refillBottles(client, player, handler)) {
                status = "Refilling bottles";
                return;
            }
        }

        // Wrap up: armour back on first, then end.
        if (!needs || bottleSlot == -1 || stopRequested) {
            status = "Restoring armour";
            if (!removed.isEmpty()) {
                if (canClick) restoreStep(client, player, handler);
                return;
            }
            String msg = !needs ? "DarkSpider Auto Mend: done, armour " + targetText()
                    : bottleSlot == -1 ? "DarkSpider Auto Mend: out of XP bottles"
                    : "DarkSpider Auto Mend: stopped";
            endSession(player, msg);
            return;
        }

        status = "Mending (one-click)";

        // Take off pieces that are done while others still need XP.
        if (canClick && holdOff == 0 && active.size() > 1) {
            for (int slotId : active) {
                ItemStack s = handler.getSlot(slotId).getStack();
                if (done(s, Config.target)) {
                    takeOff(client, player, handler, slotId, s);
                    return;
                }
            }
        }

        // Throw a bottle at your feet.
        if (throwCooldown == 0) {
            player.getInventory().selectedSlot = bottleSlot;
            player.setPitch(90f);
            client.interactionManager.interactItem(player, Hand.MAIN_HAND);
            player.swingHand(Hand.MAIN_HAND);
            throwCooldown = Config.throwDelay;
        }
    }

    private static void endSession(ClientPlayerEntity player, String message) {
        session = false;
        stopRequested = false;
        restoring = false;
        player.getInventory().selectedSlot = savedSlot;
        player.setPitch(savedPitch);
        status = "Idle";
        player.sendMessage(Text.literal(message), true);
    }

    private static boolean refillBottles(MinecraftClient client, ClientPlayerEntity player,
                                         PlayerScreenHandler handler) {
        int source = -1;
        for (int slotId = 9; slotId <= 35; slotId++) {
            if (handler.getSlot(slotId).getStack().isOf(Items.EXPERIENCE_BOTTLE)) { source = slotId; break; }
        }
        if (source == -1) return false;

        int target = -1;
        if (player.getInventory().getStack(lastBottleSlot).isEmpty()) {
            target = lastBottleSlot;
        } else {
            for (int i = 0; i < 9; i++) {
                if (player.getInventory().getStack(i).isEmpty()) { target = i; break; }
            }
        }
        if (target == -1) return false;

        client.interactionManager.clickSlot(handler.syncId, source, target, SlotActionType.SWAP, player);
        cooldown = CLICK_DELAY_TICKS;
        return true;
    }

    private static int findBottleSlot(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isOf(Items.EXPERIENCE_BOTTLE)) return i;
        }
        return -1;
    }

    private static boolean hasBottleInInventory(ClientPlayerEntity player) {
        if (!Config.refill) return false;
        for (int i = 9; i < 36; i++) {
            if (player.getInventory().getStack(i).isOf(Items.EXPERIENCE_BOTTLE)) return true;
        }
        return false;
    }

    // ---- Armour handling --------------------------------------------------------------------

    private static void takeOff(MinecraftClient client, ClientPlayerEntity player, PlayerScreenHandler handler,
                                int slotId, ItemStack stack) {
        if (!hasFreeInventorySlot(handler)) {
            player.sendMessage(Text.literal("DarkSpider Auto Mend: inventory full, make room!"), true);
            return;
        }
        removed.put(armorSlotOf(stack), stack.getItem());
        noProgress = 0;
        click(client, player, slotId);
    }

    private static void restoreStep(MinecraftClient client, ClientPlayerEntity player, PlayerScreenHandler handler) {
        for (Map.Entry<EquipmentSlot, Item> entry : new ArrayList<>(removed.entrySet())) {
            EquipmentSlot slot = entry.getKey();
            int armorSlotId = ARMOR_HANDLER_SLOTS[indexOf(slot)];

            if (!handler.getSlot(armorSlotId).getStack().isEmpty()) { removed.remove(slot); continue; }

            int candidate = -1;
            for (int slotId = 9; slotId <= 44; slotId++) {
                ItemStack s = handler.getSlot(slotId).getStack();
                if (armorSlotOf(s) != slot) continue;
                if (s.getItem() == entry.getValue() && hasMending(s)) { candidate = slotId; break; }
                if (candidate == -1) candidate = slotId;
            }
            removed.remove(slot);
            if (candidate == -1) continue;
            click(client, player, candidate);
            return;
        }
    }

    /** Handler slot ids of worn armour pieces that have Mending. */
    private static List<Integer> activeSlots(PlayerScreenHandler handler) {
        List<Integer> result = new ArrayList<>();
        for (int slotId : ARMOR_HANDLER_SLOTS) {
            ItemStack s = handler.getSlot(slotId).getStack();
            if (armorSlotOf(s) != null && hasMending(s)) result.add(slotId);
        }
        return result;
    }

    private static boolean anyNeeds(PlayerScreenHandler handler, List<Integer> active, int target) {
        for (int slotId : active) {
            if (!done(handler.getSlot(slotId).getStack(), target)) return true;
        }
        return false;
    }

    /** True if the piece is fully repaired (HEAL_FULL), or has at least `target` durability left. */
    private static boolean done(ItemStack s, int target) {
        if (HEAL_FULL) return s.getDamage() <= 0;
        int remaining = s.getMaxDamage() - s.getDamage();
        return remaining >= Math.min(target, s.getMaxDamage());
    }

    private static String targetText() {
        return HEAL_FULL ? "full" : "at " + Config.target + "+";
    }

    /** Counts ticks in which worn armour gained no durability (XP not actually being applied). */
    private static void trackProgress(PlayerScreenHandler handler, List<Integer> active) {
        int total = 0;
        for (int slotId : active) {
            ItemStack s = handler.getSlot(slotId).getStack();
            total += s.getMaxDamage() - s.getDamage();
        }
        if (total > lastTotal) noProgress = 0; else noProgress++;
        lastTotal = total;
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private static boolean xpNearby(MinecraftClient client, ClientPlayerEntity player) {
        var box = player.getBoundingBox();
        return !client.world.getEntitiesByClass(ExperienceBottleEntity.class, box.expand(10), e -> true).isEmpty()
                || !client.world.getEntitiesByClass(ExperienceOrbEntity.class, box.expand(6), e -> true).isEmpty();
    }

    private static boolean enemyNear(MinecraftClient client, ClientPlayerEntity player) {
        double r = Config.guardRange;
        double r2 = r * r;
        for (PlayerEntity p : client.world.getPlayers()) {
            if (p == player || p.isSpectator()) continue;
            if (p.squaredDistanceTo(player) <= r2) return true;
        }
        return false;
    }

    private static void click(MinecraftClient client, ClientPlayerEntity player, int slotId) {
        // QUICK_MOVE = shift-click: armour slot -> inventory, or inventory -> matching armour slot.
        client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slotId, 0,
                SlotActionType.QUICK_MOVE, player);
        cooldown = CLICK_DELAY_TICKS;
    }

    private static boolean hasFreeInventorySlot(PlayerScreenHandler handler) {
        for (int i = 9; i <= 44; i++) if (handler.getSlot(i).getStack().isEmpty()) return true;
        return false;
    }

    private static int indexOf(EquipmentSlot slot) {
        for (int i = 0; i < 4; i++) if (ARMOR_SLOTS[i] == slot) return i;
        return 0;
    }

    private static EquipmentSlot armorSlotOf(ItemStack stack) {
        if (stack.isEmpty()) return null;
        EquippableComponent eq = stack.get(DataComponentTypes.EQUIPPABLE);
        if (eq == null) return null;
        EquipmentSlot slot = eq.slot();
        return slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? slot : null;
    }

    private static boolean hasMending(ItemStack stack) {
        for (RegistryEntry<Enchantment> e : stack.getEnchantments().getEnchantments()) {
            if (e.matchesKey(Enchantments.MENDING)) return true;
        }
        return false;
    }
}
