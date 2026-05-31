package dev.teacommontea.skstorage.util;

import ch.njol.skript.variables.Variables;
import ch.njol.skript.variables.VariablesStorage;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Pattern;

public final class SkriptReflect {

    private static final Field VARIABLE_NAME_PATTERN;
    private static final Method VARIABLE_LOADED;

    static {
        try {
            VARIABLE_NAME_PATTERN = VariablesStorage.class.getDeclaredField("variableNamePattern");
            VARIABLE_NAME_PATTERN.setAccessible(true);
            VARIABLE_LOADED = Variables.class.getDeclaredMethod(
                "variableLoaded", String.class, Object.class, VariablesStorage.class);
            VARIABLE_LOADED.setAccessible(true);
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            throw new ExceptionInInitializerError(
                "SkStorage incompatible with this Skript version: " + e.getMessage());
        }
    }

    private SkriptReflect() {}

    public static void setNamePattern(VariablesStorage storage, @Nullable Pattern pattern) {
        try {
            VARIABLE_NAME_PATTERN.set(storage, pattern);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to set variableNamePattern: " + e.getMessage(), e);
        }
    }

    public static boolean variableLoaded(String name, @Nullable Object value, VariablesStorage source) {
        try {
            return (Boolean) VARIABLE_LOADED.invoke(null, name, value, source);
        } catch (Exception e) {
            throw new RuntimeException("variableLoaded reflection failed: " + e.getMessage(), e);
        }
    }

    private static volatile Method GET_VARIABLE;

    @Nullable
    public static Object getVariable(String name) {
        try {
            if (GET_VARIABLE == null) {
                Method m = ch.njol.skript.variables.Variables.class
                    .getDeclaredMethod("getVariable", String.class, org.bukkit.event.Event.class, boolean.class);
                m.setAccessible(true);
                GET_VARIABLE = m;
            }
            return GET_VARIABLE.invoke(null, name, null, false);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception e) {
            throw new RuntimeException("getVariable reflection failed: " + e.getMessage(), e);
        }
    }
}
