package com.automend;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
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
import net.minecraft.entity.projectile.thrown.ExperienceBottleEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class AutoMendClient implements ClientModInitializer {

    private static final int[] ARMOR_HANDLER_SLOTS = {5, 6, 7, 8};
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static final int TAKE_OFF_AT = 405;
    private static final int FINISH_AT = 406;

    private static final int CLICK_DELAY_TICKS = 3;
    private static final int QUIET_TICKS_BEFORE_RESTORE = 30;

    private static boolean enabled = true;
    private static KeyBinding toggleKey;

    private final Map<EquipmentSlot, Item> removed = new EnumMap<>(EquipmentSlot.class);
    private int cooldown = 0;
    private int quietTicks = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.automend.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.automend"));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            if (player != null) {
                player.sendMessage(Text.literal("AutoMend: " + (enabled ? "ON" : "OFF")), true);
            }
        }
        if (!enabled || player == null || client.interactionManager == null || client.world == null) return;
        if (client.currentScreen != null || player.isCreative() || player.isSpectator()) return;
        if (cooldown > 0) { cooldown--; return; }

        boolean xpActive = xpNearby(client, player);
        if (xpActive) quietTicks = 0; else quietTicks++;

        PlayerScreenHandler handler = player.playerScreenHandler;
        List<Integer> active = activeSlots(handler);

        if (!removed.isEmpty()) {
            boolean lastPieceDone = active.size() == 1 && done(handler.getSlot(active.get(0)).getStack(), FINISH_AT);
            boolean nothingLeft = active.isEmpty();
            boolean xpStopped = !xpActive && quietTicks > QUIET_TICKS_BEFORE_RESTORE;
            if (lastPieceDone || nothingLeft || xpStopped) {
                restoreStep(client, player, handler);
                return;
            }
        }

        if (!xpActive) return;

        boolean anyNeedsMending = false;
        for (int slotId : active) {
            if (!done(handler.getSlot(slotId).getStack(), FINISH_AT)) { anyNeedsMending = true; break; }
        }
        if (!anyNeedsMending) return;

        if (active.size() > 1) {
            for (int slotId : active) {
                ItemStack s = handler.getSlot(slotId).getStack();
                if (done(s, TAKE_OFF_AT)) {
                    takeOff(client, player, handler, slotId, s);
                    return;
                }
            }
        }
    }

    private void takeOff(MinecraftClient client, ClientPlayerEntity player, PlayerScreenHandler handler,
                         int slotId, ItemStack stack) {
        if (!hasFreeInventorySlot(handler)) {
            player.sendMessage(Text.literal("AutoMend: inventory full, make room!"), true);
            return;
        }
        removed.put(armorSlotOf(stack), stack.getItem());
        click(client, player, slotId);
    }

    private void restoreStep(MinecraftClient client, ClientPlayerEntity player, PlayerScreenHandler handler) {
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

    private List<Integer> activeSlots(PlayerScreenHandler handler) {
        List<Integer> result = new ArrayList<>();
        for (int slotId : ARMOR_HANDLER_SLOTS) {
            ItemStack s = handler.getSlot(slotId).getStack();
            if (armorSlotOf(s) != null && hasMending(s)) result.add(slotId);
        }
        return result;
    }

    private static boolean done(ItemStack s, int target) {
        int remaining = s.getMaxDamage() - s.getDamage();
        return remaining >= Math.min(target, s.getMaxDamage());
    }

    private boolean xpNearby(MinecraftClient client, ClientPlayerEntity player) {
        var box = player.getBoundingBox();
        return !client.world.getEntitiesByClass(ExperienceBottleEntity.class, box.expand(10), e -> true).isEmpty()
                || !client.world.getEntitiesByClass(ExperienceOrbEntity.class, box.expand(6), e -> true).isEmpty();
    }

    private void click(MinecraftClient client, ClientPlayerEntity player, int slotId) {
        client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slotId, 0,
                SlotActionType.QUICK_MOVE, player);
        cooldown = CLICK_DELAY_TICKS;
    }

    private boolean hasFreeInventorySlot(PlayerScreenHandler handler) {
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
