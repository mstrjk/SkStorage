package litebans.api;

import java.util.Collection;
import java.util.UUID;

public abstract class Database {

    public static Database get() {
        throw new UnsupportedOperationException("compile-only stub");
    }

    public abstract Collection<String> getUsersByIP(String ip);

    public abstract boolean isPlayerBanned(UUID uuid, String ip);

    public abstract String getPlayerName(UUID uuid);
}
