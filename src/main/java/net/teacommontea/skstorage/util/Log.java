package net.teacommontea.skstorage.util;

import ch.njol.skript.Skript;
import net.teacommontea.skstorage.SkStoragePlugin;

public final class Log {

    private Log() {}

    private static boolean flag(String key) {
        SkStoragePlugin p = SkStoragePlugin.get();
        return p != null && p.getConfig().getBoolean("experimental." + key, false);
    }

    private static boolean silenceSoft() {
        return flag("silently_error_soft") || flag("silently_error_hard");
    }

    private static boolean silenceHard() {
        return flag("silently_error_hard");
    }

    public static void soft(String message) {
        if (silenceSoft()) return;
        Skript.warning("[SkStorage] " + message);
    }

    public static void hard(String message) {
        if (silenceHard()) return;
        Skript.error("[SkStorage] " + message);
    }

    public static void hard(String message, Throwable t) {
        if (silenceHard()) return;
        Skript.error("[SkStorage] " + message + ": " + t.getMessage());
        t.printStackTrace();
    }

    public static void info(String message) {
        SkStoragePlugin p = SkStoragePlugin.get();
        if (p != null) p.getLogger().info(message);
    }
}
