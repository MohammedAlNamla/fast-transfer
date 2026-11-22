package com.fasttransfer.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Plain app settings. The PIN itself is never stored here in clear text (see PinVault). */
final class Prefs {

    static final String DEFAULT_CODE = "*267#";
    static final String DEFAULT_STEPS = "PIN,#,6,PHONE,CONFIRM,AMOUNT,ACCOUNT,FINAL";

    static final int FINAL_MANUAL = 0;
    static final int FINAL_PIN = 1;

    private static final int MAX_RECENT = 6;

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    String code() { return sp.getString("code", DEFAULT_CODE); }
    String steps() { return sp.getString("steps", DEFAULT_STEPS); }
    String account() { return sp.getString("account", "1"); }
    int finalMode() { return sp.getInt("finalMode", FINAL_PIN); }
    /** 0 = system default, 1 = SIM 1, 2 = SIM 2. */
    int sim() { return sp.getInt("sim", 0); }
    boolean biometric() { return sp.getBoolean("bio", true); }

    void save(String code, String steps, String account, int finalMode, int sim) {
        sp.edit()
                .putString("code", code)
                .putString("steps", steps)
                .putString("account", account)
                .putInt("finalMode", finalMode)
                .putInt("sim", sim)
                .apply();
    }

    void setCode(String v) { sp.edit().putString("code", v).apply(); }
    void setSteps(String v) { sp.edit().putString("steps", v).apply(); }
    void setFinalMode(int v) { sp.edit().putInt("finalMode", v).apply(); }
    void setSim(int v) { sp.edit().putInt("sim", v).apply(); }

    void setBiometric(boolean on) { sp.edit().putBoolean("bio", on).apply(); }

    // --- accounts: number the bank expects -> label shown in the app ----------------

    java.util.LinkedHashMap<String, String> accounts() {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        try {
            org.json.JSONObject o = new org.json.JSONObject(sp.getString("accounts", "{}"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, o.optString(k));
            }
        } catch (Exception ignored) {
        }
        if (out.isEmpty()) out.put("1", "حساب 1");
        if (!out.containsKey(account())) out.put(account(), "حساب " + account());
        return out;
    }

    void setAccounts(java.util.Map<String, String> map) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            for (java.util.Map.Entry<String, String> e : map.entrySet()) o.put(e.getKey(), e.getValue());
        } catch (Exception ignored) {
        }
        sp.edit().putString("accounts", o.toString()).apply();
    }

    /** Keeps the names the bank lists ("توفير-0 (32.86) شيكل" → "توفير-0"), unless the user renamed one. */
    void learnAccounts(java.util.Map<String, String> fromBank) {
        java.util.LinkedHashMap<String, String> cur = accounts();
        java.util.Set<String> custom = new java.util.HashSet<>(
                java.util.Arrays.asList(sp.getString("accounts_custom", "").split(",")));
        for (java.util.Map.Entry<String, String> e : fromBank.entrySet()) {
            if (custom.contains(e.getKey())) continue;
            String label = e.getValue().replaceAll("\\s*\\(.*$", "").trim();
            if (!label.isEmpty()) cur.put(e.getKey(), label);
        }
        setAccounts(cur);
    }

    void markAccountCustom(String number) {
        String c = sp.getString("accounts_custom", "");
        if (!("," + c + ",").contains("," + number + ",")) {
            sp.edit().putString("accounts_custom", c.isEmpty() ? number : c + "," + number).apply();
        }
    }

    void setAccount(String number) { sp.edit().putString("account", number).apply(); }

    String accountLabel() {
        String l = accounts().get(account());
        return l != null ? l : "حساب " + account();
    }

    /** Test mode: run every step but stop before the last code, so no money moves. */
    boolean testMode() { return sp.getBoolean("test", false); }
    void setTestMode(boolean on) { sp.edit().putBoolean("test", on).apply(); }

    /** Turns +970 59..., 00972 59..., "059-xxx" into 059xxxxxxx. */
    static String normalizePhone(String raw) {
        if (raw == null) return "";
        String d = raw.replaceAll("[^0-9+]", "");
        if (d.startsWith("+")) d = d.substring(1);
        if (d.startsWith("00")) d = d.substring(2);
        if ((d.startsWith("970") || d.startsWith("972")) && d.length() >= 12) d = "0" + d.substring(3);
        return d.replaceAll("[^0-9]", "");
    }

    String lastResult() { return sp.getString("lastResult", null); }
    void setLastResult(String r) { sp.edit().putString("lastResult", r).apply(); }

    List<String> recent() {
        String raw = sp.getString("recent", "");
        if (raw.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(raw.split(",")));
    }

    void addRecent(String phone) {
        List<String> list = recent();
        list.remove(phone);
        list.add(0, phone);
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
        sp.edit().putString("recent", String.join(",", list)).apply();
    }

    void removeRecent(String phone) {
        List<String> list = recent();
        list.remove(phone);
        sp.edit().putString("recent", String.join(",", list)).remove("name_" + phone).apply();
    }

    /** Recipient name as the bank showed it, or as the user renamed it. */
    String name(String phone) {
        String n = sp.getString("name_" + phone, null);
        // An early version saved MIUI's window title as the name; ignore it.
        return n != null && n.contains("شركة الاتصالات") ? null : n;
    }

    void setName(String phone, String name) {
        if (name == null || name.trim().isEmpty()) sp.edit().remove("name_" + phone).apply();
        else sp.edit().putString("name_" + phone, name.trim()).apply();
    }

    /** Only stores the bank's name if the user hasn't already named this person. */
    void setNameIfMissing(String phone, String name) {
        if (name(phone) == null && name != null) setName(phone, name);
    }

    /** Pulls the recipient's name out of the bank's confirmation text, best effort. */
    static String extractName(String bankText) {
        if (bankText == null) return null;
        for (String raw : bankText.split("\n")) {
            String line = raw.trim();
            int colon = Math.max(line.lastIndexOf(':'), line.lastIndexOf('：'));
            if (colon >= 0) line = line.substring(colon + 1).trim();
            if (line.length() < 3 || line.length() > 40) continue;
            if (line.matches(".*\\d.*")) continue;
            if (line.matches(".*(اضغط|للتأكيد|تأكيد|للخروج|خروج|إلغاء|الغاء|رجوع|ادخل|أدخل|الرجاء|يرجى|هل|عملية|تحويل|حساب|مبلغ|معلومات|شركة|الرقم|press|confirm|cancel).*")) continue;
            return line;
        }
        return null;
    }

    static String initials(String name) {
        if (name == null || name.isEmpty()) return null;
        String[] parts = name.trim().split("\\s+");
        String a = parts[0].substring(0, 1);
        if (parts.length > 1) return a + parts[parts.length - 1].substring(0, 1);
        return a;
    }
}
