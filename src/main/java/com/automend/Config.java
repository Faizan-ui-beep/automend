package com.automend;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Settings, saved to config/darkspider-automend.properties. */
public final class Config {
    private Config() {}

    // Modules
    public static boolean enabled = true;      // master switch
    public static boolean passive = true;      // Auto Mend while you throw XP yourself
    public static boolean oneClick = true;     // mend key throws the bottles for you
    public static boolean panicAuto = true;    // restore armour when health is low
    public static boolean refill = true;       // pull XP bottles into the hotbar
    public static boolean enemyGuard = false;  // never strip armour with another player nearby
    public static boolean hud = true;          // on-screen overlay

    // Settings
    public static int target = 400;       // durability each piece is mended to
    public static int throwDelay = 3;     // ticks between thrown bottles (one-click mode)
    public static int panicHearts = 4;    // restore armour at or below this many hearts
    public static int guardRange = 10;    // blocks

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("darkspider-automend.properties");
    }

    public static void load() {
        Path p = file();
        if (!Files.exists(p)) return;
        Properties pr = new Properties();
        try (InputStream in = Files.newInputStream(p)) {
            pr.load(in);
        } catch (IOException e) {
            return;
        }
        enabled = bool(pr, "enabled", enabled);
        passive = bool(pr, "passive", passive);
        oneClick = bool(pr, "oneClick", oneClick);
        panicAuto = bool(pr, "panicAuto", panicAuto);
        refill = bool(pr, "refill", refill);
        enemyGuard = bool(pr, "enemyGuard", enemyGuard);
        hud = bool(pr, "hud", hud);
        target = clamp(num(pr, "target", target), 100, 1000);
        throwDelay = clamp(num(pr, "throwDelay", throwDelay), 2, 10);
        panicHearts = clamp(num(pr, "panicHearts", panicHearts), 1, 10);
        guardRange = clamp(num(pr, "guardRange", guardRange), 4, 32);
    }

    public static void save() {
        Properties pr = new Properties();
        pr.setProperty("enabled", String.valueOf(enabled));
        pr.setProperty("passive", String.valueOf(passive));
        pr.setProperty("oneClick", String.valueOf(oneClick));
        pr.setProperty("panicAuto", String.valueOf(panicAuto));
        pr.setProperty("refill", String.valueOf(refill));
        pr.setProperty("enemyGuard", String.valueOf(enemyGuard));
        pr.setProperty("hud", String.valueOf(hud));
        pr.setProperty("target", String.valueOf(target));
        pr.setProperty("throwDelay", String.valueOf(throwDelay));
        pr.setProperty("panicHearts", String.valueOf(panicHearts));
        pr.setProperty("guardRange", String.valueOf(guardRange));
        try (OutputStream out = Files.newOutputStream(file())) {
            pr.store(out, "DarkSpider AutoMend");
        } catch (IOException ignored) {
        }
    }

    private static boolean bool(Properties pr, String key, boolean def) {
        String v = pr.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int num(Properties pr, String key, int def) {
        try {
            return Integer.parseInt(pr.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
