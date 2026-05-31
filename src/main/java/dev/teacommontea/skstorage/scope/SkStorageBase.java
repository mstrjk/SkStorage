package dev.teacommontea.skstorage.scope;

import ch.njol.skript.variables.VariablesStorage;
import dev.teacommontea.skstorage.util.SkriptReflect;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public abstract class SkStorageBase extends VariablesStorage {

    protected final AtomicLong writesTotal  = new AtomicLong();
    protected final AtomicLong writesFailed = new AtomicLong();

    protected SkStorageBase(String type) {
        super(type);
    }

    protected abstract Pattern scopePattern();

    protected final void installScopePattern() {
        SkriptReflect.setNamePattern(this, scopePattern());
    }

    protected final boolean feedLoaded(String name, @Nullable Object value) {
        return SkriptReflect.variableLoaded(name, value, this);
    }

    public long writesTotal()  { return writesTotal.get(); }
    public long writesFailed() { return writesFailed.get(); }

    public void flush() {}

    public boolean migrationSave(String name, @Nullable String type, @Nullable byte[] value) {
        return save(name, type, value);
    }
}
