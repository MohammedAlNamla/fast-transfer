package com.fasttransfer.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Transfers made from the app, kept on the phone for 31 days. */
final class History {

    static final String SUCCESS = "success";
    static final String FAILED = "failed";
    static final String CANCELLED = "cancelled";
    static final String TEST = "test";

    private static final long KEEP_MS = 31L * 24 * 60 * 60 * 1000;
    private static final int MAX = 500;

    static final class Entry {
        long time;
        String phone, name, amount, status, message;
    }

    private History() { }

    static void add(Context c, String phone, String name, String amount, String status, String message) {
        List<Entry> all = load(c);
        Entry e = new Entry();
        e.time = System.currentTimeMillis();
        e.phone = phone;
        e.name = name;
        e.amount = amount;
        e.status = status;
        e.message = message;
        all.add(0, e);
        save(c, all);
    }

    /** Newest first, older than 31 days dropped. */
    static List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("items", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optLong("t") < cutoff) continue;
                Entry e = new Entry();
                e.time = o.optLong("t");
                e.phone = o.optString("p");
                e.name = o.has("n") ? o.optString("n") : null;
                e.amount = o.optString("a");
                e.status = o.optString("s");
                e.message = o.optString("m");
                out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Lets the user correct an entry (e.g. the bank confirmed by SMS). */
    static void setStatus(Context c, long time, String status) {
        List<Entry> all = load(c);
        for (Entry e : all) if (e.time == time) e.status = status;
        save(c, all);
    }

    static void remove(Context c, long time) {
        List<Entry> all = load(c);
        all.removeIf(e -> e.time == time);
        save(c, all);
    }

    static void clear(Context c) {
        prefs(c).edit().remove("items").apply();
    }

    private static void save(Context c, List<Entry> all) {
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < Math.min(all.size(), MAX); i++) {
                Entry e = all.get(i);
                JSONObject o = new JSONObject();
                o.put("t", e.time);
                o.put("p", e.phone);
                if (e.name != null) o.put("n", e.name);
                o.put("a", e.amount);
                o.put("s", e.status);
                o.put("m", e.message);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString("items", arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("history", Context.MODE_PRIVATE);
    }
}
