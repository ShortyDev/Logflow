package at.shorty.logflow.util;

import java.util.HashMap;
import java.util.Map;

public abstract class DataCache<R, V> {

    private final Map<R, Pair<Long, V>> cache;

    protected DataCache() {
        this.cache = new HashMap<>();
    }

    public V get(R reference, int interval) {
        return cache.compute(reference, (key, value) -> value == null ? new Pair<>(System.currentTimeMillis() + interval, getData(reference)) : value.key() - System.currentTimeMillis() > interval ? value : new Pair<>(System.currentTimeMillis() + interval, getData(reference))).value();
    }

    public abstract V getData(R reference);

}
