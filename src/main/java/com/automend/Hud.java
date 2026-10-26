package com.automend;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/** Small overlay in the top-left corner: armour durability, current status, bottle count. */
public final class Hud {
    private Hud() {}

    private static final int ACCENT = 0xFFC8102E;
    private static final String[] NAMES = {"Helmet", "Chest", "Legs", "Boots"};

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (!Config.hud || player == null || mc.options.hudHidden
                || mc.currentScreen instanceof DarkSpiderScreen) return;

        TextRenderer tr = mc.textRenderer;
        int x = 6, y = 6, w = 156, h = 90;
        ctx.fill(x, y, x + w, y + h, 0xB00B0B10);
        ctx.fill(x, y, x + 2, y + h, ACCENT);
        ctx.drawText(tr, "DarkSpider Auto Mend", x + 7, y + 4, ACCENT, true);

        for (int i = 0; i < 4; i++) {
            EquipmentSlot slot = AutoMendClient.ARMOR_SLOTS[i];
            int ry = y + 17 + i * 11;
            ctx.drawText(tr, NAMES[i], x + 7, ry, 0xFFCFCFD6, false);

            ItemStack s = player.getEquippedStack(slot);
            if (s.isEmpty()) {
                String t = AutoMendClient.removed.containsKey(slot) ? "off" : "-";
                ctx.drawText(tr, t, x + 50, ry, 0xFF8A8A96, false);
                continue;
            }
            int max = s.getMaxDamage();
            int rem = max - s.getDamage();
            float ratio = max <= 0 ? 1f : (float) rem / max;
            int color = ratio > 0.6f ? 0xFF3DDC84 : ratio > 0.3f ? 0xFFFFC043 : 0xFFFF4D4D;
            ctx.fill(x + 50, ry + 2, x + 110, ry + 7, 0xFF2A2A33);
            ctx.fill(x + 50, ry + 2, x + 50 + (int) (60 * ratio), ry + 7, color);
            ctx.drawText(tr, String.valueOf(rem), x + 114, ry, 0xFFFFFFFF, false);
        }

        int bottles = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isOf(Items.EXPERIENCE_BOTTLE)) bottles += s.getCount();
        }

        String status = Config.enabled ? AutoMendClient.status : "OFF";
        int statusColor = !Config.enabled ? 0xFF8A8A96
                : "PANIC".equals(status) ? 0xFFFF4D4D : 0xFFFFFFFF;
        ctx.drawText(tr, "Status: " + status, x + 7, y + 17 + 4 * 11 + 3, statusColor, false);
        ctx.drawText(tr, "XP bottles: " + bottles + "   Target: " + Config.target,
                x + 7, y + 17 + 4 * 11 + 14, 0xFF8A8A96, false);
    }
}
