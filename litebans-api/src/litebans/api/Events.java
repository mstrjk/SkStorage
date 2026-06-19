package litebans.api;

public abstract class Events {

    public static Events get() {
        throw new UnsupportedOperationException("compile-only stub");
    }

    public abstract void register(Listener listener);

    public abstract void unregister(Listener listener);

    public static class Listener {

        public void entryAdded(Entry entry) {}

        public void entryRemoved(Entry entry) {}

        public void broadcastSent(String type, String message) {}
    }
}
