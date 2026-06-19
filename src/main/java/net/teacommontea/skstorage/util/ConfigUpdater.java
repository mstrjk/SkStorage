package net.teacommontea.skstorage.util;
import net.teacommontea.skstorage.util.Log;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ConfigUpdater {

    private ConfigUpdater() {}

    public static List<String> sync(JavaPlugin plugin) {
        List<String> added = new ArrayList<>();
        Path userFile = plugin.getDataFolder().toPath().resolve("config.yml");
        if (!Files.exists(userFile)) return added;

        Set<String> userKeys;
        Set<String> defaultKeys;
        try {
            userKeys = topLevelKeys(Files.readString(userFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.soft("ConfigUpdater: cannot read config.yml: " + e.getMessage());
            return added;
        }

        String defaultText = readResource(plugin, "config.yml");
        if (defaultText == null) {
            Log.soft("ConfigUpdater: bundled config.yml resource not found.");
            return added;
        }
        defaultKeys = topLevelKeys(defaultText);

        StringBuilder appendix = new StringBuilder();
        for (String key : defaultKeys) {
            if (userKeys.contains(key)) continue;
            String block = extractTopLevelBlock(defaultText, key);
            if (block == null) continue;
            appendix.append('\n').append(block);
            if (!block.endsWith("\n")) appendix.append('\n');
            added.add(key);
        }

        if (!added.isEmpty()) {
            try {
                String current = Files.readString(userFile, StandardCharsets.UTF_8);
                if (!current.endsWith("\n")) current += "\n";
                Files.writeString(userFile, current + appendix, StandardCharsets.UTF_8);
            } catch (IOException e) {
                Log.soft("ConfigUpdater: failed to append missing keys: " + e.getMessage());
                return new ArrayList<>();
            }
        }
        return added;
    }

    public static boolean removeTopLevelKey(JavaPlugin plugin, String key) {
        Path userFile = plugin.getDataFolder().toPath().resolve("config.yml");
        try {
            String text = Files.readString(userFile, StandardCharsets.UTF_8);
            String block = extractTopLevelBlock(text, key);
            if (block == null) return false;

            String updated;
            if (text.contains(block + "\n\n")) {
                updated = text.replace(block + "\n\n", "");
            } else if (text.contains("\n" + block)) {
                updated = text.replace("\n" + block, "");
            } else {
                updated = text.replace(block, "");
            }
            Files.writeString(userFile, updated, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            Log.soft("ConfigUpdater: failed to remove key '" + key + "': " + e.getMessage());
            return false;
        }
    }

    private static Set<String> topLevelKeys(String yaml) {
        Set<String> keys = new LinkedHashSet<>();
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
            keys.addAll(parsed.getKeys(false));
        } catch (Exception ignored) {

            for (String line : yaml.split("\n", -1)) {
                if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '#' || line.charAt(0) == '\t') continue;
                int colon = line.indexOf(':');
                if (colon > 0) keys.add(line.substring(0, colon).trim());
            }
        }
        return keys;
    }

    private static String extractTopLevelBlock(String text, String key) {
        String[] lines = text.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (!l.isEmpty() && l.charAt(0) != ' ' && l.charAt(0) != '\t' && l.charAt(0) != '#') {
                int colon = l.indexOf(':');
                if (colon > 0 && l.substring(0, colon).trim().equals(key)) {
                    start = i;
                    break;
                }
            }
        }
        if (start < 0) return null;

        int end = lines.length;
        for (int i = start + 1; i < lines.length; i++) {
            String l = lines[i];

            if (!l.isEmpty() && l.charAt(0) != ' ' && l.charAt(0) != '\t' && l.charAt(0) != '#'
                && l.indexOf(':') > 0) {
                end = i;
                break;
            }
        }

        int lastBody = start;
        for (int i = start + 1; i < end; i++) {
            String l = lines[i];
            boolean indented = !l.isEmpty() && (l.charAt(0) == ' ' || l.charAt(0) == '\t');
            if (indented) {
                lastBody = i;
            } else if (l.isEmpty()) {

                break;
            } else if (l.charAt(0) == '#') {

                lastBody = i;
            } else {
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= lastBody; i++) {
            sb.append(lines[i]);
            if (i < lastBody) sb.append('\n');
        }
        return sb.toString();
    }

    private static String readResource(JavaPlugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) return null;
            Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }
}
