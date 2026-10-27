package com.afs.guard;
import org.json.JSONObject;

public final class LedgerMergeTest {
    static JSONObject db(String goals, String recs) throws Exception {
        return new JSONObject("{\"goals\":" + goals + ",\"recs\":" + recs + "}");
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        JSONObject base = db("[{id:'g',name:'old',due:'2026',archived:false}]", "[{id:'r',amount:5}]");
        JSONObject pc = db("[{id:'g',name:'old',due:'2027',archived:true}]", "[{id:'r',amount:2},{id:'pc'}]");
        JSONObject phone = db("[{id:'g',name:'new',due:'2026',archived:false}]", "[{id:'r',amount:5},{id:'phone'}]");
        JSONObject merged = LedgerMerge.merge(phone, pc, base, false);
        JSONObject g = merged.getJSONArray("goals").getJSONObject(0);
        check(g.getString("name").equals("new"));
        check(g.getString("due").equals("2027"));
        check(g.getBoolean("archived"));
        check(merged.getJSONArray("recs").length() == 3);
        check(merged.getJSONArray("recs").getJSONObject(0).getInt("amount") == 2);
        check(LedgerMerge.sameData(merged, LedgerMerge.merge(merged, merged, merged, false)));
        JSONObject deleted = LedgerMerge.merge(db("[]", "[]"), base, base, false);
        check(deleted.getJSONArray("goals").length() == 0);
        check(deleted.getJSONArray("recs").length() == 0);
        JSONObject restored = LedgerMerge.merge(db("[]", "[]"), base, null, false);
        check(LedgerMerge.sameData(restored, base));
        System.out.println("LedgerMerge: 9 assertions passed");
    }
}
