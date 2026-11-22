package com.fasttransfer.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Transfers made from the app during the last month. */
public class HistoryActivity extends Activity {

    private LinearLayout list;
    private TextView summary;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_history);
        list = findViewById(R.id.list);
        summary = findViewById(R.id.summary);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        build();
    }

    private void build() {
        list.removeAllViews();
        List<History.Entry> all = History.load(this);
        Prefs prefs = new Prefs(this);

        double total = 0;
        int count = 0;
        for (History.Entry e : all) {
            if (History.SUCCESS.equals(e.status)) {
                count++;
                try { total += Double.parseDouble(e.amount); } catch (Exception ignored) { }
            }
        }
        summary.setText(all.isEmpty() ? "آخر 31 يوم"
                : "آخر 31 يوم • " + count + " تحويل ناجح • " + MainActivity.LTR_START + fmt(total) + " ₪" + MainActivity.LTR_END);

        if (all.isEmpty()) {
            TextView empty = text("لسا ما في تحويلات. أي تحويل بتعمله من التطبيق بيظهر هون.", 15, R.color.text2, R.font.plex_regular);
            empty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(60);
            list.addView(empty, lp);
            return;
        }

        String lastDay = null;
        LinearLayout group = null;
        for (History.Entry e : all) {
            String day = dayLabel(e.time);
            if (!day.equals(lastDay)) {
                lastDay = day;
                TextView h = text(day, 13, R.color.text2, R.font.plex_medium);
                LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
                hlp.topMargin = dp(24);
                hlp.bottomMargin = dp(8);
                hlp.setMarginStart(dp(8));
                list.addView(h, hlp);
                group = new LinearLayout(this);
                group.setOrientation(LinearLayout.VERTICAL);
                group.setBackgroundResource(R.drawable.card);
                list.addView(group, new LinearLayout.LayoutParams(-1, -2));
            } else if (group != null) {
                View line = new View(this);
                line.setBackgroundColor(getColor(R.color.line));
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
                llp.setMarginStart(dp(72));
                group.addView(line, llp);
            }
            if (group != null) group.addView(row(e, prefs));
        }

        TextView clear = text("مسح السجل", 14, R.color.danger, R.font.plex_medium);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(0, dp(14), 0, dp(14));
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("تمسح السجل كله؟")
                .setPositiveButton("مسح", (d, w) -> {
                    History.clear(this);
                    build();
                })
                .setNegativeButton("لا", null)
                .show());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(20);
        list.addView(clear, clp);
    }

    private View row(History.Entry e, Prefs prefs) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        String name = e.name != null ? e.name : prefs.name(e.phone);
        TextView avatar = text(name != null ? Prefs.initials(name) : "#", 16, R.color.text, R.font.plex_medium);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable av = new GradientDrawable();
        av.setShape(GradientDrawable.OVAL);
        av.setColor(getColor(R.color.card2));
        avatar.setBackground(av);
        if (History.SUCCESS.equals(e.status)) avatar.setBackgroundResource(R.drawable.grad_circle);
        if (History.SUCCESS.equals(e.status)) avatar.setTextColor(0xFFFFFFFF);
        row.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(14), 0, dp(10), 0);
        mid.addView(text(name != null ? name : MainActivity.LTR_START + e.phone + MainActivity.LTR_END,
                15, R.color.text, R.font.plex_medium));
        String time = new SimpleDateFormat("h:mm a", new Locale("ar")).format(e.time);
        mid.addView(text(time + " • " + statusLabel(e.status), 12, statusColor(e.status), R.font.plex_regular));
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));

        TextView amt = text(MainActivity.LTR_START + e.amount + " ₪" + MainActivity.LTR_END, 16,
                History.SUCCESS.equals(e.status) ? R.color.text : R.color.text3, R.font.plex_semibold);
        row.addView(amt);

        row.setOnClickListener(v -> {
            AlertDialog.Builder d = new AlertDialog.Builder(this)
                    .setTitle(statusLabel(e.status))
                    .setMessage((name != null ? name + "\n" : "") + e.phone + "\n"
                            + e.amount + " ₪\n\n" + e.message)
                    .setPositiveButton("تمام", null)
                    .setNeutralButton("حذف", (x, w) -> {
                        History.remove(this, e.time);
                        build();
                    });
            if (!History.SUCCESS.equals(e.status) && !History.TEST.equals(e.status)) {
                // e.g. the bank confirmed by SMS but the app missed the final screen
                d.setNegativeButton("البنك أكّد إنها تمّت", (x, w) -> {
                    History.setStatus(this, e.time, History.SUCCESS);
                    build();
                });
            }
            d.show();
        });
        return row;
    }

    private static String statusLabel(String s) {
        switch (s) {
            case History.SUCCESS: return "تمّ";
            case History.CANCELLED: return "انلغى";
            case History.TEST: return "تجربة";
            default: return "ما تمّ";
        }
    }

    private static int statusColor(String s) {
        switch (s) {
            case History.SUCCESS: return R.color.text2;
            case History.TEST: return R.color.warn;
            default: return R.color.danger;
        }
    }

    private static String dayLabel(long t) {
        if (DateUtils.isToday(t)) return "اليوم";
        Calendar y = Calendar.getInstance();
        y.add(Calendar.DAY_OF_YEAR, -1);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        if (c.get(Calendar.YEAR) == y.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)) {
            return "مبارح";
        }
        return new SimpleDateFormat("EEEE d MMMM", new Locale("ar")).format(t);
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.2f", v);
    }

    private TextView text(String s, int sp, int color, int font) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(color));
        t.setTypeface(getResources().getFont(font));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
