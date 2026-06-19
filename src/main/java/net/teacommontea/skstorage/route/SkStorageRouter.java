package net.teacommontea.skstorage.route;

import ch.njol.skript.Skript;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.variables.VariablesStorage;
import net.teacommontea.skstorage.util.Log;
import net.teacommontea.skstorage.SkStoragePlugin;
import net.teacommontea.skstorage.util.SkriptReflect;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class SkStorageRouter extends VariablesStorage {

    public static final class Route {
        final Table table;
        final VariablePattern pattern;
        final int index;
        final int declOrder;

        Route(Table table, VariablePattern pattern, int index, int declOrder) {
            this.table = table;
            this.pattern = pattern;
            this.index = index;
            this.declOrder = declOrder;
        }
    }

    private final List<Route> routes = new ArrayList<>();
    @Nullable private Table defaultTable;
    private final boolean ignoreInvalid;

    private volatile boolean killed = false;
    @Nullable private Table disabledTable;

    public SkStorageRouter(String type) {
        super(type);
        this.ignoreInvalid = SkStoragePlugin.get().getConfig()
            .getBoolean("experimental.ignore_invalid_files", true);
    }

    public void addRoute(Table table, VariablePattern pattern, int index, int declOrder) {
        routes.add(new Route(table, pattern, index, declOrder));

        routes.sort((a, b) -> {
            int c = Integer.compare(b.index, a.index);
            return c != 0 ? c : Integer.compare(b.declOrder, a.declOrder);
        });
    }

    public void setDefaultTable(Table table) {
        this.defaultTable = table;
    }

    public List<Route> routes() {
        return routes;
    }

    @Nullable
    public Table defaultTable() {
        return defaultTable;
    }

    public List<Table> allTables() {
        List<Table> out = new ArrayList<>(routes.size() + 1);
        for (Route r : routes) out.add(r.table);
        if (defaultTable != null) out.add(defaultTable);
        return out;
    }

    @Nullable
    public Table route(String name) {
        if (killed) return disabledTable;
        for (Route r : routes) {
            if (r.pattern.matches(name)) return r.table;
        }
        return defaultTable;
    }

    public Explain routeExplain(String name) {
        if (killed) {
            return new Explain("_disabled", null, 0, true, "database is killed");
        }
        for (Route r : routes) {
            if (r.pattern.matches(name)) {
                return new Explain(r.table.name(), r.pattern.raw(), r.index, false, null);
            }
        }
        return new Explain(defaultTable != null ? defaultTable.name() : "(none)",
            null, Integer.MIN_VALUE, false, "no pattern matched");
    }

    public record Explain(String table, @Nullable String pattern, int index,
                          boolean killed, @Nullable String note) {}

    public boolean isKilled() {
        return killed;
    }

    public synchronized void kill(File dataDir) {
        if (killed) return;
        flushAll();
        for (Table t : allTables()) t.close();
        try {
            disabledTable = new Table("_disabled", false, true, Integer.MIN_VALUE,
                new File(dataDir, "_disabled.db"), 2, net.teacommontea.skstorage.util.SqliteSetup.Options.DEFAULT);
            disabledTable.open();
        } catch (Exception e) {
            Log.hard("kill: could not open _disabled.db: " + e.getMessage());
        }
        killed = true;
    }

    public synchronized void revive(File dataDir, boolean deleteDisabled) {
        if (!killed) return;
        if (disabledTable != null) {
            disabledTable.flush();
            disabledTable.close();
            disabledTable = null;
        }
        if (deleteDisabled) {
            deleteDisabledDb(dataDir);
        }
        try {
            for (Table t : allTables()) t.open();
        } catch (Exception e) {
            Log.hard("revive: could not reopen tables: " + e.getMessage());
        }
        killed = false;
    }

    public static void deleteDisabledDb(File dataDir) {
        File f = new File(dataDir, "_disabled.db");
        f.delete();
        new File(f.getAbsolutePath() + "-shm").delete();
        new File(f.getAbsolutePath() + "-wal").delete();
    }

    public int purgeUuidEverywhere(String uuid) {
        int total = 0;
        for (Table t : allTables()) {
            total += t.purgeUuid(uuid);
        }
        return total;
    }

    public int purgeByGlobEverywhere(String glob) {
        int total = 0;
        for (Table t : allTables()) {
            total += t.purgeByGlob(glob);
        }
        return total;
    }

    public int mergeUuid(String fromUuid, String toUuid) {
        flushAll();
        java.util.List<Table.RawRow> rows = new java.util.ArrayList<>();
        for (Table t : allTables()) {
            rows.addAll(t.readRowsContaining(fromUuid));
        }
        int moved = 0;
        for (Table.RawRow row : rows) {
            String newName = row.name().replace(fromUuid, toUuid);
            if (newName.equals(row.name())) continue;
            Table target = route(newName);
            if (target == null) continue;

            if (target.has(newName)) continue;
            if (target.save(newName, row.type(), row.value())) moved++;
        }
        flushAll();
        purgeUuidEverywhere(fromUuid);
        return moved;
    }

    @Override
    protected boolean requiresFile() {
        return false;
    }

    @Override
    protected File getFile(String fileName) {
        return new File(fileName);
    }

    @Override
    protected boolean load_i(SectionNode node) {

        try {
            if (!TableConfig.build(SkStoragePlugin.get(), this)) {
                Log.hard("persistence disabled (database: false); no tables built");
                return false;
            }

            File dataDir = SkStoragePlugin.get().getDataFolder();
            if (SkStoragePlugin.get().getConfig()
                    .getBoolean("automatically_delete_disabled_db_on_startup", true)) {
                File orphan = new File(dataDir, "_disabled.db");
                if (orphan.exists()) {
                    deleteDisabledDb(dataDir);
                    Log.soft("removed orphaned _disabled.db (writes made while " +
                        "the database was killed are discarded).");
                }
            }
            for (Table t : allTables()) {
                t.open();
            }
            installAcceptPattern();
            loadEverything();
            SkStoragePlugin.get().setRouter(this);
            return true;
        } catch (Exception e) {
            Log.hard("router load failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void installAcceptPattern() {
        SkriptReflect.setNamePattern(this, Pattern.compile(".*"));
    }

    private void loadEverything() {
        for (Table t : allTables()) {
            t.loadAll((name, value) -> SkriptReflect.variableLoaded(name, value, this), ignoreInvalid);
        }
    }

    @Override
    protected void allLoaded() {
    }

    @Override
    protected boolean connect() {
        return true;
    }

    @Override
    protected void disconnect() {
        for (Table t : allTables()) {
            t.close();
        }
    }

    @Override
    public void close() {
        super.close();
        disconnect();
    }

    public void flushAll() {
        if (killed) {
            if (disabledTable != null) {
                try { disabledTable.flush(); } catch (Throwable ignored) {}
            }
            return;
        }
        for (Table t : allTables()) {
            try {
                t.flush();
            } catch (Throwable th) {
                Log.soft("flush failed for table " + t.name() + ": " + th.getMessage());
            }
        }
    }

    @Override
    protected boolean save(String name, @Nullable String type, byte @Nullable [] value) {
        Table target = route(name);
        if (target == null) {

            return false;
        }
        return target.save(name, type, value);
    }
}
