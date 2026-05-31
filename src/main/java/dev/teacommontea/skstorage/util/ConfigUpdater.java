package dev.teacommontea.skstorage.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ConfigUpdater {

    private ConfigUpdater() {}

    public static List<String> sync(JavaPlugin plugin) {
        List<String> added = new ArrayList<>();

        File userFile = new File(plugin.getDataFolder(), "config.yml");
        if (!userFile.exists()) return added;
        YamlConfiguration rawUser = YamlConfiguration.loadConfiguration(userFile);

        InputStream in = plugin.getResource("config.yml");
        if (in == null) {
            plugin.getLogger().warning("ConfigUpdater: bundled config.yml resource not found");
            return added;
        }

        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(r);
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) continue;
                if (!rawUser.contains(key)) {
                    rawUser.set(key, defaults.get(key));
                    added.add(key);
                }
            }
            if (!added.isEmpty()) {
                rawUser.save(userFile);

                FileConfiguration cached = plugin.getConfig();
                for (String key : added) {
                    cached.set(key, rawUser.get(key));
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("ConfigUpdater: failed to read or write config: " + e.getMessage());
        }
        return added;
    }
}
