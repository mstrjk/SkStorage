package net.teacommontea.skstorage.route;

import net.teacommontea.skstorage.util.Log;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class VariablePattern {

    private static final String UUID_REGEX =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private static final String ANY_SEGMENT = "(?:(?!::).)*";

    private final String raw;
    private final Pattern compiled;
    private final boolean uuidKeyed;

    private VariablePattern(String raw, Pattern compiled, boolean uuidKeyed) {
        this.raw = raw;
        this.compiled = compiled;
        this.uuidKeyed = uuidKeyed;
    }

    public String raw() {
        return raw;
    }

    public Pattern compiled() {
        return compiled;
    }

    public boolean isUuidKeyed() {
        return uuidKeyed;
    }

    public boolean matches(String variableName) {
        return compiled.matcher(variableName).matches();
    }

    @Nullable
    public static VariablePattern parse(String raw) {
        String body = raw;
        boolean leading = false;
        if (body.startsWith("*")) {
            leading = true;
            body = body.substring(1);
        }

        StringBuilder regex = new StringBuilder();
        if (leading) {

            regex.append("(?:").append(ANY_SEGMENT).append("::)*");
        }

        boolean uuidKeyed = false;
        String[] segments = body.split("::", -1);
        boolean trailingOpen = segments.length > 0 && segments[segments.length - 1].isEmpty();
        int lastIndex = trailingOpen ? segments.length - 1 : segments.length;
        for (int s = 0; s < lastIndex; s++) {
            if (s > 0) regex.append("::");
            String seg = segments[s];
            if (seg.isEmpty()) {

                continue;
            }
            if (seg.indexOf('%') < 0) {

                regex.append(Pattern.quote(seg));
                continue;
            }

            if (describesUuid(seg)) {
                regex.append(UUID_REGEX);
                uuidKeyed = true;
            } else {
                regex.append(ANY_SEGMENT);
            }
        }

        if (trailingOpen) {

            regex.append("(?:::").append(ANY_SEGMENT).append(")*");
        }

        Pattern compiled;
        try {
            compiled = Pattern.compile("^" + regex + "$");
        } catch (RuntimeException e) {
            Log.hard("pattern compiled to invalid regex: " + raw + " (" + e.getMessage() + ")");
            return null;
        }
        return new VariablePattern(raw, compiled, uuidKeyed);
    }

    private static boolean describesUuid(String segment) {
        String s = segment.toLowerCase(java.util.Locale.ENGLISH);
        return s.contains("%") && s.contains("uuid");
    }

    public static String uuidRegex() {
        return UUID_REGEX;
    }
}
