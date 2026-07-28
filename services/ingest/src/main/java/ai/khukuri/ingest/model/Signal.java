package ai.khukuri.ingest.model;

public enum Signal {
    LOGS("logs"),
    METRICS("metrics"),
    TRACES("traces");

    private final String wireName;

    Signal(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
