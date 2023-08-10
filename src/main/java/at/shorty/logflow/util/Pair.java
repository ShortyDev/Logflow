package at.shorty.logflow.util;

import java.io.Serializable;
import java.util.Objects;

public record Pair<K, V>(K key, V value) implements Serializable {

    @Override
    public String toString() {
        return key + "=" + value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof Pair pair) {
            if (!Objects.equals(key, pair.key)) return false;
            return Objects.equals(value, pair.value);
        }
        return false;
    }
}
