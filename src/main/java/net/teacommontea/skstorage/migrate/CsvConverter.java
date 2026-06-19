package net.teacommontea.skstorage.migrate;
import net.teacommontea.skstorage.util.Log;

import net.teacommontea.skstorage.SkStoragePlugin;
import net.teacommontea.skstorage.route.SkStorageRouter;
import net.teacommontea.skstorage.route.Table;
import net.teacommontea.skstorage.util.ConfigUpdater;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CsvConverter {

    private CsvConverter() {}

    private static final Pattern CSV_LINE =
        Pattern.compile("(?<=^|,)\\s*(?:([^\",]*)|\"((?:[^\"]+|\"\")*)\")\\s*(?:,|$)");

    public static void runIfEnabled(SkStoragePlugin plugin) {
        if (!plugin.getConfig().getBoolean("convert_csv.enabled", false)) return;

        SkStorageRouter router = plugin.getRouter();
        Table target = router != null ? router.defaultTable() : null;
        if (target == null) {
            Log.soft("convert_csv: no default.db table available; skipping.");
            return;
        }

        Path csv = locateCsv(plugin);
        if (csv == null) {
            Log.soft("convert_csv: no variables.csv found (looked in plugins/Skript/). " +
                "Nothing to convert; disabling the option.");
            ConfigUpdater.removeTopLevelKey(plugin, "convert_csv");
            return;
        }

        long imported = 0, skipped = 0;
        try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] cols = splitCsv(line);
                if (cols.length < 2 || cols[0] == null || cols[0].isEmpty()) { skipped++; continue; }
                String name = cols[0];
                String type = cols.length >= 2 ? cols[1] : null;
                byte[] value = (cols.length >= 3 && cols[2] != null && !cols[2].isEmpty())
                    ? hexDecode(cols[2]) : null;
                if (type == null || type.isEmpty() || value == null) {

                    skipped++;
                    continue;
                }
                if (target.save(name, type, value)) imported++; else skipped++;
            }
            target.flush();
        } catch (IOException e) {
            Log.hard("convert_csv: read failed: " + e.getMessage());
            return;
        }

        plugin.getLogger().info("convert_csv: imported " + imported + " row(s) into default.db (" +
            skipped + " skipped). Your old CSV files were NOT deleted; remove them manually after verifying.");
        ConfigUpdater.removeTopLevelKey(plugin, "convert_csv");
        plugin.reloadConfig();
    }

    private static Path locateCsv(SkStoragePlugin plugin) {

        Path skriptDir = plugin.getDataFolder().toPath().getParent().resolve("Skript");
        for (String n : new String[]{"variables.csv", "variables.csv.bak"}) {
            Path p = skriptDir.resolve(n);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private static String[] splitCsv(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Matcher m = CSV_LINE.matcher(line);
        while (m.find()) {
            if (m.group(1) != null) {
                out.add(m.group(1).trim());
            } else if (m.group(2) != null) {
                out.add(m.group(2).replace("\"\"", "\"").trim());
            } else {
                out.add("");
            }
            if (m.end() == line.length()) break;
        }
        return out.toArray(new String[0]);
    }

    private static byte[] hexDecode(String hex) {
        int len = hex.length();
        if ((len & 1) != 0) return null;
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
