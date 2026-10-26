package com.automend;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** The DarkSpider Auto Mend settings screen. Everything is drawn by hand, no vanilla widgets. */
public class DarkSpiderScreen extends Screen {

    private static final int ACCENT = 0xFFC8102E;
    private static final int ACCENT_DIM = 0xFF6E0A19;
    private static final int PANEL = 0xF20B0B10;
    private static final int HEADER = 0xFF15080C;
    private static final int ROW = 0x14FFFFFF;
    private static final int ROW_HOVER = 0x26FFFFFF;
    private static final int TEXT = 0xFFEDEDED;
    private static final int MUTED = 0xFF8A8A96;
    private static final int OFF = 0xFF3A3A46;

    private static final int CONTENT_HEIGHT = 352;

    private record Hit(int x1, int y1, int x2, int y2, Runnable action) {
        boolean contains(double x, double y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private int scroll = 0;

    public DarkSpiderScreen() {
        super(Text.literal("DarkSpider Auto Mend"));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        hits.clear();
        // Our own dim overlay (no vanilla background call).
        ctx.fill(0, 0, width, height, 0xA0000000);

        int pw = Math.min(330, width - 16);
        int px = (width - pw) / 2;
        int py;
        int maxScroll = Math.max(0, CONTENT_HEIGHT - (height - 10));
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        if (maxScroll == 0) py = (height - CONTENT_HEIGHT) / 2;
        else py = 5 - scroll;

        // Panel
        ctx.fill(px - 1, py - 1, px + pw + 1, py + CONTENT_HEIGHT + 1, ACCENT_DIM);
        ctx.fill(px, py, px + pw, py + CONTENT_HEIGHT, PANEL);

        // Header
        ctx.fill(px, py, px + pw, py + 36, HEADER);
        ctx.fill(px, py + 36, px + pw, py + 38, ACCENT);
        ctx.getMatrices().push();
        ctx.getMatrices().scale(1.4f, 1.4f, 1f);
        ctx.drawText(textRenderer, "DarkSpider Auto Mend",
                (int) ((px + 12) / 1.4f), (int) ((py + 6) / 1.4f), ACCENT, true);
        ctx.getMatrices().pop();
        ctx.drawText(textRenderer, "armour automation", px + 12, py + 23, MUTED, false);

        // Close button
        int cx = px + pw - 24, cy = py + 10;
        boolean hoverClose = mouseX >= cx && mouseX < cx + 16 && mouseY >= cy && mouseY < cy + 16;
        ctx.fill(cx, cy, cx + 16, cy + 16, hoverClose ? ACCENT : ACCENT_DIM);
        ctx.drawText(textRenderer, "x", cx + 8 - textRenderer.getWidth("x") / 2, cy + 4, 0xFFFFFFFF, false);
        hits.add(new Hit(cx, cy, cx + 16, cy + 16, this::close));

        // Master switch
        String masterLabel = Config.enabled ? "ON" : "OFF";
        int mx0 = px + pw - 24 - 8 - 24;
        pill(ctx, mx0, py + 12, Config.enabled);
        ctx.drawText(textRenderer, masterLabel, mx0 - 6 - textRenderer.getWidth(masterLabel), py + 14,
                Config.enabled ? ACCENT : MUTED, false);
        hits.add(new Hit(mx0 - 30, py + 8, mx0 + 26, py + 28, () -> {
            Config.enabled = !Config.enabled;
            Config.save();
        }));

        int y = py + 44;
        y = section(ctx, px, pw, y, "MODULES");
        y = moduleRow(ctx, px, pw, y, mouseX, mouseY, "Auto Mend",
                "Takes pieces off at target while you throw XP", Config.passive,
                () -> Config.passive = !Config.passive);
        y = moduleRow(ctx, px, pw, y, mouseX, mouseY, "One-Click Mend",
                "Mend key throws bottles and swaps armour", Config.oneClick,
                () -> Config.oneClick = !Config.oneClick);
        y = moduleRow(ctx, px, pw, y, mouseX, mouseY, "Panic Restore",
                "Puts armour back on when health is low", Config.panicAuto,
                () -> Config.panicAuto = !Config.panicAuto);
        y = moduleRow(ctx, px, pw, y, mouseX, mouseY, "Refill Bottles",
                "Pulls XP bottles into the hotbar when empty", Config.refill,
                () -> Config.refill = !Config.refill);
        y = moduleRow(ctx, px, pw, y, mouseX, mouseY, "Enemy Guard",
                "No stripping while another player is close", Config.enemyGuard,
                () -> Config.enemyGuard = !Config.enemyGuard);
        y = moduleRow(ctx, px, pw, y, mouseX, mouseY, "HUD Overlay",
                "Shows durability and status on screen", Config.hud,
                () -> Config.hud = !Config.hud);

        y = section(ctx, px, pw, y + 2, "SETTINGS");
        y = stepper(ctx, px, pw, y, mouseX, mouseY, "Target durability",
                String.valueOf(Config.target),
                () -> Config.target = Config.clamp(Config.target - 25, 100, 1000),
                () -> Config.target = Config.clamp(Config.target + 25, 100, 1000));
        y = stepper(ctx, px, pw, y, mouseX, mouseY, "Throw delay",
                Config.throwDelay + " ticks",
                () -> Config.throwDelay = Config.clamp(Config.throwDelay - 1, 2, 10),
                () -> Config.throwDelay = Config.clamp(Config.throwDelay + 1, 2, 10));
        y = stepper(ctx, px, pw, y, mouseX, mouseY, "Panic health",
                Config.panicHearts + " hearts",
                () -> Config.panicHearts = Config.clamp(Config.panicHearts - 1, 1, 10),
                () -> Config.panicHearts = Config.clamp(Config.panicHearts + 1, 1, 10));
        y = stepper(ctx, px, pw, y, mouseX, mouseY, "Guard range",
                Config.guardRange + " blocks",
                () -> Config.guardRange = Config.clamp(Config.guardRange - 2, 4, 32),
                () -> Config.guardRange = Config.clamp(Config.guardRange + 2, 4, 32));

        // Footer
        String keys = "GUI: " + AutoMendClient.guiKey.getBoundKeyLocalizedText().getString()
                + "   Mend: " + AutoMendClient.mendKey.getBoundKeyLocalizedText().getString()
                + "   Panic: " + AutoMendClient.panicKey.getBoundKeyLocalizedText().getString()
                + "   On/Off: " + AutoMendClient.toggleKey.getBoundKeyLocalizedText().getString();
        ctx.drawText(textRenderer, textRenderer.trimToWidth(keys, pw - 20), px + 10, y + 8, MUTED, false);
        ctx.drawText(textRenderer, "Rebind in Options > Controls > Key Binds", px + 10, y + 19, MUTED, false);
    }

    private int section(DrawContext ctx, int px, int pw, int y, String label) {
        ctx.drawText(textRenderer, label, px + 10, y + 2, ACCENT, false);
        ctx.fill(px + 10 + textRenderer.getWidth(label) + 6, y + 6, px + pw - 10, y + 7, ACCENT_DIM);
        return y + 14;
    }

    private int moduleRow(DrawContext ctx, int px, int pw, int y, int mx, int my,
                          String name, String desc, boolean on, Runnable toggle) {
        int x1 = px + 8, x2 = px + pw - 8, h = 24;
        boolean hover = mx >= x1 && mx < x2 && my >= y && my < y + h;
        ctx.fill(x1, y, x2, y + h, hover ? ROW_HOVER : ROW);
        ctx.fill(x1, y, x1 + 2, y + h, on ? ACCENT : OFF);
        ctx.drawText(textRenderer, name, x1 + 8, y + 3, TEXT, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(desc, pw - 80), x1 + 8, y + 13, MUTED, false);
        pill(ctx, x2 - 32, y + 6, on);
        hits.add(new Hit(x1, y, x2, y + h, () -> {
            toggle.run();
            Config.save();
        }));
        return y + 26;
    }

    private int stepper(DrawContext ctx, int px, int pw, int y, int mx, int my,
                        String name, String value, Runnable minus, Runnable plus) {
        int x1 = px + 8, x2 = px + pw - 8, h = 20;
        ctx.fill(x1, y, x2, y + h, ROW);
        ctx.fill(x1, y, x1 + 2, y + h, ACCENT_DIM);
        ctx.drawText(textRenderer, name, x1 + 8, y + 6, TEXT, false);

        int plusX = x2 - 6 - 14;
        int vw = 62;
        int valueRight = plusX - 4;
        int valueLeft = valueRight - vw;
        int minusX = valueLeft - 4 - 14;
        button(ctx, plusX, y + 3, "+", mx, my, plus);
        button(ctx, minusX, y + 3, "-", mx, my, minus);
        ctx.drawText(textRenderer, value, valueLeft + vw / 2 - textRenderer.getWidth(value) / 2, y + 6,
                0xFFFFFFFF, false);
        return y + 22;
    }

    private void button(DrawContext ctx, int x, int y, String label, int mx, int my, Runnable action) {
        boolean hover = mx >= x && mx < x + 14 && my >= y && my < y + 14;
        ctx.fill(x, y, x + 14, y + 14, hover ? ACCENT : ACCENT_DIM);
        ctx.drawText(textRenderer, label, x + 7 - textRenderer.getWidth(label) / 2, y + 3, 0xFFFFFFFF, false);
        hits.add(new Hit(x, y, x + 14, y + 14, () -> {
            action.run();
            Config.save();
        }));
    }

    private void pill(DrawContext ctx, int x, int y, boolean on) {
        ctx.fill(x, y, x + 24, y + 12, on ? ACCENT : OFF);
        int kx = on ? x + 13 : x + 1;
        ctx.fill(kx, y + 1, kx + 10, y + 11, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (Hit hit : hits) {
                if (hit.contains(mouseX, mouseY)) {
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scroll -= (int) (verticalAmount * 16);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (AutoMendClient.guiKey != null && AutoMendClient.guiKey.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
