package dev.teacommontea.skstorage.util;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UuidExtractor {

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    private UuidExtractor() {}

    @Nullable
    public static String firstUuid(String name) {
        Matcher m = UUID_PATTERN.matcher(name);
        return m.find() ? m.group() : null;
    }

    public static boolean containsUuid(String name) {
        return UUID_PATTERN.matcher(name).find();
    }
}
