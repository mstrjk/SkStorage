package net.teacommontea.skstorage.playerdata;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.SkriptParser;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.Date;
import java.util.UUID;

public class ExprPlayerData extends SimpleExpression<Object> {

    private static volatile PlayerDataStore store;

    public static void register() {
        Skript.registerExpression(ExprPlayerData.class, Object.class, ExpressionType.SIMPLE,
            "playerdata %string% of %offlineplayer%");
    }

    public static void setStore(PlayerDataStore s) {
        store = s;
    }

    private Expression<String> field;
    private Expression<OfflinePlayer> player;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed,
                        SkriptParser.ParseResult parseResult) {
        this.field = (Expression<String>) exprs[0];
        this.player = (Expression<OfflinePlayer>) exprs[1];
        return true;
    }

    @Override
    @Nullable
    protected Object[] get(Event event) {
        if (store == null) return null;
        String fieldName = field.getSingle(event);
        OfflinePlayer op = player.getSingle(event);
        if (fieldName == null || op == null) return null;

        UUID uuid = op.getUniqueId();
        PlayerDataStore.PlayerRecord rec = store.get(uuid);
        if (rec == null) return null;

        Object value = switch (fieldName.toLowerCase().replace('_', ' ').trim()) {
            case "uuid" -> rec.uuid().toString();
            case "name" -> rec.name();
            case "first join", "firstjoin" -> new Date(rec.firstJoin());
            case "last join", "lastjoin" -> new Date(rec.lastJoin());
            case "last quit", "lastquit" -> rec.lastQuit() != null ? new Date(rec.lastQuit()) : null;
            case "playtime", "total playtime" -> rec.totalPlaytime();
            case "sessions", "total sessions" -> rec.totalSessions();
            case "last world", "lastworld" -> rec.lastWorld();
            case "last ip hash", "ip hash", "iphash" -> rec.lastIpHash();
            default -> null;
        };
        return value == null ? null : new Object[] { value };
    }

    @Override
    public boolean isSingle() { return true; }

    @Override
    public Class<?> getReturnType() { return Object.class; }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "playerdata " + field.toString(event, debug) + " of " + player.toString(event, debug);
    }

}
