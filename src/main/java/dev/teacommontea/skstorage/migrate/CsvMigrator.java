package dev.teacommontea.skstorage.migrate;

import dev.teacommontea.skstorage.SkStoragePlugin;
import dev.teacommontea.skstorage.command.SkStorageCommand;
import org.bukkit.command.CommandSender;

import java.io.File;

public final class CsvMigrator {

    private final SkStoragePlugin plugin;

    public CsvMigrator(SkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    public void migrate(File source, CommandSender sender, boolean dryRun) {
        String PFX = SkStorageCommand.PFX;
        String MSG = SkStorageCommand.MSG;
        String ERR = SkStorageCommand.ERR;
        String INFO = SkStorageCommand.INFO;

        sender.sendMessage(PFX + ERR + "CSV migration not implemented yet.");
        sender.sendMessage(PFX + MSG + "Workaround:");
        sender.sendMessage(PFX + INFO + "1. In Skript's config.yml, change the default database 'type' to 'sqlite'.");
        sender.sendMessage(PFX + INFO + "2. Restart the server. Skript will rewrite variables to a SQLibrary-format DB.");
        sender.sendMessage(PFX + INFO + "3. Run /skstorage migrate sqlibrary <path-to-that-db> --dry-run");
        sender.sendMessage(PFX + MSG + "Native CSV path coming in a later version.");
    }
}
