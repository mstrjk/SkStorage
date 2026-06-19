package litebans.api;

public abstract class Entry {

    public abstract long getId();

    public abstract String getType();

    public abstract String getUuid();

    public abstract String getIp();

    public abstract String getReason();

    public abstract long getDateStart();

    public abstract long getDateEnd();

    public abstract boolean isSilent();

    public abstract boolean isIpban();

    public abstract boolean isActive();

    public abstract boolean isPermanent();

    public abstract long getDuration();
}
