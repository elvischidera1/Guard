package android.content;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * JVM stand-in for the SharedPreferences a real Context returns: an in-memory map, like Android's
 * implementation, which also serves every read from memory and persists asynchronously.
 */
public class InMemorySharedPreferences implements SharedPreferences {
    private final Map<String, Object> map = new HashMap<>();

    @Override public synchronized Map<String, ?> getAll() { return new HashMap<>(map); }
    @Override public synchronized String getString(String k, String d) { Object v = map.get(k); return v == null ? d : (String) v; }
    @SuppressWarnings("unchecked")
    @Override public synchronized Set<String> getStringSet(String k, Set<String> d) { Object v = map.get(k); return v == null ? d : (Set<String>) v; }
    @Override public synchronized int getInt(String k, int d) { Object v = map.get(k); return v == null ? d : (Integer) v; }
    @Override public synchronized long getLong(String k, long d) { Object v = map.get(k); return v == null ? d : (Long) v; }
    @Override public synchronized float getFloat(String k, float d) { Object v = map.get(k); return v == null ? d : (Float) v; }
    @Override public synchronized boolean getBoolean(String k, boolean d) { Object v = map.get(k); return v == null ? d : (Boolean) v; }
    @Override public synchronized boolean contains(String k) { return map.containsKey(k); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

    @Override public Editor edit() {
        return new Editor() {
            private final Map<String, Object> puts = new HashMap<>();
            private final java.util.List<String> removes = new java.util.ArrayList<>();
            private boolean clear = false;
            @Override public Editor putString(String k, String v) { puts.put(k, v); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { puts.put(k, v); return this; }
            @Override public Editor putInt(String k, int v) { puts.put(k, v); return this; }
            @Override public Editor putLong(String k, long v) { puts.put(k, v); return this; }
            @Override public Editor putFloat(String k, float v) { puts.put(k, v); return this; }
            @Override public Editor putBoolean(String k, boolean v) { puts.put(k, v); return this; }
            @Override public Editor remove(String k) { removes.add(k); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() {
                synchronized (InMemorySharedPreferences.this) {
                    if (clear) map.clear();
                    for (String k : removes) map.remove(k);
                    map.putAll(puts);
                }
                return true;
            }
            @Override public void apply() { commit(); }
        };
    }
}
