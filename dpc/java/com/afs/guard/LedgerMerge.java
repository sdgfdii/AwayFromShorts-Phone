package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Common-baseline merge: record IDs deduplicate, deletions remain deleted. */
final class LedgerMerge {
    private LedgerMerge() { }

    private static Map<String, JSONObject> index(JSONArray rows) {
        Map<String, JSONObject> out = new LinkedHashMap<>();
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && !row.optString("id").isEmpty()) out.put(row.optString("id"), row);
        }
        return out;
    }

    static boolean same(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number)
            return Double.compare(((Number)a).doubleValue(), ((Number)b).doubleValue()) == 0;
        return a.toString().equals(b.toString());
    }

    static JSONObject merge(JSONObject local, JSONObject remote, JSONObject base, boolean firstLocalWins) throws Exception {
        JSONObject out = new JSONObject(remote.toString());
        for (String table : new String[]{"goals", "recs"}) {
            Map<String, JSONObject> lm = index(local.optJSONArray(table));
            Map<String, JSONObject> rm = index(remote.optJSONArray(table));
            Map<String, JSONObject> bm = index(base == null ? null : base.optJSONArray(table));
            LinkedHashSet<String> keys = new LinkedHashSet<>(rm.keySet());
            keys.addAll(lm.keySet());
            JSONArray rows = new JSONArray();
            for (String id : keys) {
                JSONObject l = lm.get(id), r = rm.get(id), b = bm.get(id);
                if (b != null && (l == null || r == null)) continue;
                if (l == null || r == null) { rows.put(l == null ? r : l); continue; }
                JSONObject row = new JSONObject(r.toString());
                Iterator<String> fields = l.keys();
                while (fields.hasNext()) {
                    String field = fields.next();
                    if ((b == null && firstLocalWins) || (b != null && !same(l.opt(field), b.opt(field))))
                        row.put(field, l.opt(field));
                }
                rows.put(row);
            }
            out.put(table, rows);
        }
        return out;
    }

    static boolean sameData(JSONObject a, JSONObject b) {
        for (String table : new String[]{"goals", "recs"}) {
            Map<String, JSONObject> am = index(a.optJSONArray(table)), bm = index(b.optJSONArray(table));
            if (!am.keySet().equals(bm.keySet())) return false;
            for (String id : am.keySet()) {
                JSONObject ar = am.get(id), br = bm.get(id);
                if (ar.length() != br.length()) return false;
                Iterator<String> fields = ar.keys();
                while (fields.hasNext()) {
                    String field = fields.next();
                    if (!br.has(field) || !same(ar.opt(field), br.opt(field))) return false;
                }
            }
        }
        return true;
    }
}
