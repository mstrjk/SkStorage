package dev.teacommontea.skstorage.scope;

import org.jetbrains.annotations.Nullable;

final class PendingOp {
    @Nullable final String type;
    final byte @Nullable [] value;

    PendingOp(@Nullable String type, byte @Nullable [] value) {
        this.type = type;
        this.value = value;
    }

    boolean isDelete() { return type == null || value == null; }
}
