package dev.teacommontea.skstorage.util;

import ch.njol.skript.aliases.ItemType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

public final class BukkitItemCodec {

    public static final String TYPE_MARKER = "_skstorage_bukkit_item";

    private BukkitItemCodec() {}

    public static boolean isItemType(@Nullable String type) {
        return "itemtype".equals(type) || "itemstack".equals(type);
    }

    @Nullable
    public static ItemStack toItemStack(@Nullable Object value) {
        if (value == null) return null;
        if (value instanceof ItemStack stack) return stack.clone();
        if (value instanceof ItemType type) {
            ItemStack stack = type.getRandom();
            if (stack != null) return stack.clone();
        }
        return null;
    }

    @Nullable
    public static byte[] encode(@Nullable ItemStack stack) {
        if (stack == null) return null;
        try {
            return stack.serializeAsBytes();
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    public static ItemStack decode(@Nullable byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    public static ItemType toItemType(@Nullable ItemStack stack) {
        if (stack == null) return null;
        try {
            return new ItemType(stack);
        } catch (Throwable t) {
            return null;
        }
    }

    public static final class Result {
        public final boolean intercepted;
        public final String type;
        public final byte[] value;

        private Result(boolean intercepted, String type, byte[] value) {
            this.intercepted = intercepted;
            this.type = type;
            this.value = value;
        }

        static Result passthrough(String type, byte[] value) {
            return new Result(false, type, value);
        }

        static Result intercepted(byte[] bukkitBytes) {
            return new Result(true, TYPE_MARKER, bukkitBytes);
        }
    }

    public static Result intercept(String name, @Nullable String type, @Nullable byte[] value) {
        if (!isItemType(type) || value == null) return Result.passthrough(type, value);
        Object live = SkriptReflect.getVariable(name);
        ItemStack stack = toItemStack(live);
        if (stack == null) return Result.passthrough(type, value);
        byte[] encoded = encode(stack);
        if (encoded == null) return Result.passthrough(type, value);
        return Result.intercepted(encoded);
    }
}
