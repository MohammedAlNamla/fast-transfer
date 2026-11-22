package com.fasttransfer.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings in the style of iOS Settings: a short main list where every row shows
 * its current value, and a page per topic. Every change is saved right away.
 */
public class SettingsActivity extends Activity {

    private interface Builder { void build(LinearLayout body); }

    private static final class Page {
        final String title;
        final Builder builder;
        Page(String title, Builder builder) { this.title = title; this.builder = builder; }
    }

    private static final String[] TOKENS = {"PIN", "PHONE", "CONFIRM", "AMOUNT", "ACCOUNT", "FINAL"};
    private static final String[] SIMS = {"تلقائي", "شريحة 1", "شريحة 2"};
    private static final String[] THEMES = {"تلقائي", "فاتح", "داكن"};
    private static final String[] FINAL_MODES = {"نفس الرقم السري", "بكتبه بإيدي"};

    private Prefs prefs;
    private PinVault vault;
    private FrameLayout pages;
    private View current;
    private final ArrayDeque<Page> stack = new ArrayDeque<>();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);
        vault = new PinVault(this);
        pages = findViewById(R.id.pages);
        push(mainPage(), false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from system settings: show the fresh state.
        if (stack.size() == 1 && current != null) rerender();
    }

    @Override
    public void onBackPressed() {
        if (stack.size() > 1) pop();
        else super.onBackPressed();
    }

    // =========================================================================
    // Pages
    // =========================================================================

    private Page mainPage() {
        return new Page("الإعدادات", body -> {
            body.addView(statusCard());

            LinearLayout g = group(body, null, null);
            add(g, row(R.drawable.ic_s_lock, R.color.tPink, "الرقم السري", null,
                    vault.hasPin() ? (vault.usesBio() ? "محفوظ • بصمة" : "محفوظ") : "مش محفوظ",
                    vault.hasPin() ? R.color.text2 : R.color.danger, true, null,
                    v -> push(pinPage(), true)));
            add(g, row(R.drawable.ic_s_wallet, R.color.tBlue, "الحساب", null,
                    prefs.accountLabel(), R.color.text2, true, null,
                    v -> push(accountsPage(), true)));
            add(g, row(R.drawable.ic_s_sim, R.color.tGreen, "الشريحة", null,
                    SIMS[clamp(prefs.sim(), SIMS.length)], R.color.text2, true, null,
                    v -> push(choicePage("الشريحة", SIMS, prefs.sim(),
                            "من أي شريحة يطلب التطبيق كود البنك. \"تلقائي\" بيستعمل الشريحة الافتراضية للمكالمات.",
                            prefs::setSim), true)));

            g = group(body, "التحويل",
                    "وضع التجربة بيمشي كل الخطوات مع البنك وبيوقف قبل آخر رمز، فما بيصير تحويل.");
            int fm = prefs.finalMode() == Prefs.FINAL_PIN ? 0 : 1;
            add(g, row(R.drawable.ic_s_shield, R.color.tIndigo, "رمز التأكيد الأخير", null,
                    FINAL_MODES[fm], R.color.text2, true, null,
                    v -> push(choicePage("رمز التأكيد الأخير", FINAL_MODES,
                            prefs.finalMode() == Prefs.FINAL_PIN ? 0 : 1,
                            "آخر رمز بيطلبه البنك قبل التحويل. لو اخترت \"بكتبه بإيدي\" التطبيق بيوقف وبتكتبه إنت.",
                            i -> prefs.setFinalMode(i == 0 ? Prefs.FINAL_PIN : Prefs.FINAL_MANUAL)), true)));
            add(g, switchRow(R.drawable.ic_s_flask, R.color.tOrange, "وضع التجربة", prefs.testMode(),
                    prefs::setTestMode));

            g = group(body, "المظهر", null);
            add(g, row(R.drawable.ic_s_moon, R.color.tPurple, "المظهر", null,
                    THEMES[clamp(Appearance.get(this), THEMES.length)], R.color.text2, true, null,
                    v -> push(choicePage("المظهر", THEMES, Appearance.get(this),
                            "\"تلقائي\" بيمشي مع وضع الجوال الفاتح أو الداكن.",
                            i -> {
                                if (i != Appearance.get(this)) {
                                    Appearance.set(this, i);
                                    recreate();
                                }
                            }), true)));

            g = group(body, "التشغيل",
                    "شاومي بيسكّر التطبيقات لما تمسحها من التطبيقات المفتوحة. التشغيل التلقائي بيخلي الخدمة ترجع لحالها.");
            KeepAlive.State st = KeepAlive.state(this);
            add(g, row(R.drawable.ic_s_bolt, R.color.tGreen, "خدمة التحويل", null,
                    st == KeepAlive.State.RUNNING ? "شغّالة" : st == KeepAlive.State.STOPPED ? "واقفة" : "مطفية",
                    st == KeepAlive.State.RUNNING ? R.color.text2 : R.color.danger, true, null,
                    v -> KeepAlive.openServicePage(this)));
            int auto = KeepAlive.autostart(this);
            if (KeepAlive.isMiui()) {
                add(g, row(R.drawable.ic_s_restart, R.color.tTeal, "التشغيل التلقائي", null,
                        auto == 1 ? "شغّال" : auto == 0 ? "مطفي" : "", auto == 0 ? R.color.danger : R.color.text2,
                        true, null, v -> KeepAlive.openAutostart(this)));
            }
            add(g, row(R.drawable.ic_s_battery, R.color.tGreen, "البطارية", "خليها \"بلا قيود\"",
                    null, 0, true, null, v -> KeepAlive.openAppInfo(this)));

            g = group(body, "متقدم", null);
            add(g, row(R.drawable.ic_s_code, R.color.tGray, "كود البنك والخطوات", null,
                    MainActivity.LTR_START + prefs.code() + MainActivity.LTR_END, R.color.text2, true, null,
                    v -> push(advancedPage(), true)));

            TextView ver = text("تحويل سريع • " + version(), 12, R.color.text3, R.font.plex_regular);
            ver.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, -2);
            vlp.topMargin = dp(28);
            body.addView(ver, vlp);
        });
    }

    /** Big card at the top: is the app ready to transfer, and if not, what to do. */
    private View statusCard() {
        KeepAlive.State st = KeepAlive.state(this);
        int auto = KeepAlive.autostart(this);
        int color, icon;
        String title, sub;
        View.OnClickListener click = null;
        if (st != KeepAlive.State.RUNNING) {
            color = R.color.danger;
            icon = R.drawable.ic_s_bolt;
            title = st == KeepAlive.State.OFF ? "خدمة التحويل مطفية" : "خدمة التحويل وقفت";
            sub = "اضغط هون، وشغّل \"تحويل سريع\" من \"التطبيقات التي تم تنزيلها\".";
            click = v -> KeepAlive.openServicePage(this);
        } else if (!vault.hasPin()) {
            color = R.color.warn;
            icon = R.drawable.ic_s_lock;
            title = "احفظ الرقم السري";
            sub = "بيضل مشفّر على جوالك، وبيفتح بالبصمة.";
            click = v -> push(pinPage(), true);
        } else if (auto == 0) {
            color = R.color.warn;
            icon = R.drawable.ic_s_restart;
            title = "شغّل التشغيل التلقائي";
            sub = "عشان الخدمة ما توقف لما تمسح التطبيق.";
            click = v -> KeepAlive.openAutostart(this);
        } else {
            color = R.color.primary;
            icon = R.drawable.ic_s_shield;
            title = "جاهز للتحويل";
            sub = prefs.testMode() ? "وضع التجربة شغّال: ما رح ينبعت مصاري." : "الخدمة شغّالة والرقم السري محفوظ.";
        }

        // Ready: the brand gradient, same as the wallet card. Problems: a plain card with a colored mark.
        boolean ready = click == null;
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(roundRect(R.color.card, 22));
        card.setClipToOutline(true);
        View mark;
        if (ready) {
            // Only the small mark carries the brand gradient, like the dots in the logo.
            ImageView iv = new ImageView(this);
            iv.setImageResource(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(11), dp(11), dp(11), dp(11));
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{getColor(R.color.g1), getColor(R.color.g2), getColor(R.color.g3)});
            g.setCornerRadius(dp(12));
            iv.setBackground(g);
            mark = iv;
        } else {
            mark = tile(icon, color, android.R.color.white, 44, 12);
        }
        card.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(14), 0, dp(8), 0);
        TextView t = text(title, 17, R.color.text, R.font.plex_semibold);
        TextView s = text(sub, 13, R.color.text2, R.font.plex_regular);
        mid.addView(t);
        s.setPadding(0, dp(2), 0, 0);
        mid.addView(s);
        card.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));

        if (click != null) {
            card.addView(chevron());
            card.setForeground(selectable());
            card.setOnClickListener(click);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(20);
        card.setLayoutParams(lp);
        return card;
    }

    // --- PIN --------------------------------------------------------------------

    private Page pinPage() {
        return new Page("الرقم السري", body -> {
            boolean has = vault.hasPin();
            final boolean[] bio = {has ? vault.usesBio() : prefs.biometric()};

            LinearLayout g = group(body, has ? "تغيير الرقم السري" : "احفظ الرقم السري", null);
            EditText field = input("الرقم السري", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, false);
            add(g, fieldRow(R.drawable.ic_s_lock, R.color.tPink, field));
            add(g, switchRow(R.drawable.ic_s_finger, R.color.tBlue, "البصمة قبل التحويل", bio[0], on -> bio[0] = on));

            g = group(body, null, has
                    ? "الرقم السري محفوظ ومشفّر على جوالك بس. عشان تغيّر وضع البصمة، اكتب الرقم السري واحفظه من جديد."
                    : "بيضل مشفّر على جوالك بس، والبصمة بتنطلب قبل ما ينبعت آخر رمز للبنك.");
            add(g, actionRow("حفظ الرقم السري", R.color.accent, v -> {
                String p = field.getText().toString().trim();
                if (p.length() < 4) {
                    field.setError("الرقم السري قصير");
                    return;
                }
                hideKeyboard();
                vault.clear();
                vault.save(this, p, bio[0], new PinVault.Done() {
                    @Override public void ok() {
                        prefs.setBiometric(bio[0]);
                        toast("انحفظ الرقم السري");
                        pop();
                    }
                    @Override public void fail(String message) {
                        toast(message);
                        rerender();
                    }
                });
            }));

            if (has) {
                g = group(body, null, null);
                add(g, actionRow("مسح الرقم السري", R.color.danger, v -> new AlertDialog.Builder(this)
                        .setMessage("تمسح الرقم السري من الجوال؟ بعدها لازم تكتبه من جديد عشان تحوّل.")
                        .setPositiveButton("مسح", (d, w) -> {
                            vault.clear();
                            toast("انمسح الرقم السري");
                            rerender();
                        })
                        .setNegativeButton("لا", null)
                        .show()));
            }
        });
    }

    // --- accounts -----------------------------------------------------------------

    private Page accountsPage() {
        return new Page("الحساب", body -> {
            LinkedHashMap<String, String> acc = prefs.accounts();
            String selected = prefs.account();

            LinearLayout g = group(body, "بتحوّل من",
                    "الرقم هو ترتيب الحساب بقائمة البنك. التطبيق بيتعلّم أسماء حساباتك من البنك لحاله.");
            for (Map.Entry<String, String> e : acc.entrySet()) {
                String number = e.getKey();
                boolean on = number.equals(selected);

                LinearLayout trailing = new LinearLayout(this);
                trailing.setGravity(Gravity.CENTER_VERTICAL);
                TextView edit = text("تعديل", 14, R.color.accent, R.font.plex_medium);
                edit.setPadding(dp(12), dp(8), dp(4), dp(8));
                edit.setOnClickListener(v -> editAccount(number));
                ImageView tick = new ImageView(this);
                tick.setImageResource(R.drawable.ic_tick);
                tick.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
                trailing.addView(tick, new LinearLayout.LayoutParams(dp(24), dp(24)));
                trailing.addView(edit);

                add(g, row(0, 0, e.getValue(), "رقم " + number + " بقائمة البنك", null, 0, false, trailing, v -> {
                    prefs.setAccount(number);
                    rerender();
                }));
            }

            g = group(body, null, null);
            add(g, actionRow("إضافة حساب", R.color.accent, v -> editAccount(null)));
        });
    }

    private void editAccount(String number) {
        LinkedHashMap<String, String> acc = prefs.accounts();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        EditText name = new EditText(this);
        name.setHint("اسم الحساب (مثلاً: توفير شيكل)");
        EditText num = new EditText(this);
        num.setHint("رقمه بقائمة البنك (1، 2، 3...)");
        num.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (number != null) {
            name.setText(acc.get(number));
            num.setText(number);
        }
        box.addView(name);
        box.addView(num);

        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(number == null ? "حساب جديد" : "تعديل الحساب")
                .setView(box)
                .setPositiveButton("حفظ", (x, w) -> {
                    String n = num.getText().toString().trim();
                    String l = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        toast("لازم تكتب رقم الحساب");
                        return;
                    }
                    if (l.isEmpty()) l = "حساب " + n;
                    if (number != null && !number.equals(n)) acc.remove(number);
                    acc.put(n, l);
                    prefs.setAccounts(acc);
                    prefs.markAccountCustom(n);
                    if (number != null && number.equals(prefs.account())) prefs.setAccount(n);
                    rerender();
                })
                .setNegativeButton("إلغاء", null);
        if (number != null && acc.size() > 1) {
            d.setNeutralButton("حذف", (x, w) -> {
                acc.remove(number);
                prefs.setAccounts(acc);
                if (number.equals(prefs.account())) prefs.setAccount(acc.keySet().iterator().next());
                rerender();
            });
        }
        d.show();
    }

    // --- one-of-many choice ---------------------------------------------------------

    private interface Pick { void pick(int index); }

    private Page choicePage(String title, String[] options, int selected, String footer, Pick onPick) {
        return new Page(title, body -> {
            LinearLayout g = group(body, null, footer);
            for (int i = 0; i < options.length; i++) {
                final int idx = i;
                ImageView tick = new ImageView(this);
                tick.setImageResource(R.drawable.ic_tick);
                tick.setVisibility(i == selected ? View.VISIBLE : View.INVISIBLE);
                tick.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
                add(g, row(0, 0, options[i], null, null, 0, false, tick, v -> {
                    onPick.pick(idx);
                    if (!isFinishing()) pop();
                }));
            }
        });
    }

    // --- bank code and steps -----------------------------------------------------------

    private Page advancedPage() {
        return new Page("كود البنك والخطوات", body -> {
            List<String> steps = new ArrayList<>(Arrays.asList(prefs.steps().split(",")));

            LinearLayout g = group(body, "كود البنك", null);
            EditText code = input(Prefs.DEFAULT_CODE, InputType.TYPE_CLASS_PHONE, true);
            code.setText(prefs.code());
            code.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) {
                    String v = s.toString().trim();
                    if (!v.isEmpty()) prefs.setCode(v);
                }
            });
            add(g, fieldRow(R.drawable.ic_s_code, R.color.tGray, code));

            TextView stepsHeader = sectionHeader("الخطوات بالترتيب");
            body.addView(stepsHeader);
            LinearLayout stepsGroup = groupCard();
            body.addView(stepsGroup);
            TextView stepsFooter = footer("");
            body.addView(stepsFooter);
            buildSteps(stepsGroup, stepsFooter, steps);

            g = group(body, null, null);
            add(g, actionRow("إضافة خطوة", R.color.accent, v -> addStep(steps, () -> buildSteps(stepsGroup, stepsFooter, steps))));

            g = group(body, null, null);
            add(g, actionRow("رجّع الافتراضي", R.color.danger, v -> new AlertDialog.Builder(this)
                    .setMessage("ترجّع كود البنك والخطوات زي ما كانوا أول مرة؟")
                    .setPositiveButton("رجّع", (d, w) -> {
                        prefs.setCode(Prefs.DEFAULT_CODE);
                        prefs.setSteps(Prefs.DEFAULT_STEPS);
                        rerender();
                    })
                    .setNegativeButton("لا", null)
                    .show()));
        });
    }

    static String stepLabel(String t) {
        switch (t.trim().toUpperCase()) {
            case "PIN": return "الرقم السري";
            case "PHONE": return "رقم المستلم";
            case "CONFIRM": return "تأكيد الاسم (بيبعت 1)";
            case "AMOUNT": return "المبلغ";
            case "ACCOUNT": return "رقم الحساب";
            case "FINAL": return "رمز التأكيد الأخير";
            default: return null;
        }
    }

    /** Rebuilds the steps list and saves it when it's valid (the last step must be FINAL). */
    private void buildSteps(LinearLayout g, TextView footer, List<String> steps) {
        g.removeAllViews();
        for (int i = 0; i < steps.size(); i++) {
            final int idx = i;
            String t = steps.get(i).trim();
            String label = stepLabel(t);

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(52));
            row.setPadding(dp(16), 0, dp(8), 0);

            TextView n = text(String.valueOf(i + 1), 13, R.color.text2, R.font.plex_semibold);
            n.setGravity(Gravity.CENTER);
            n.setBackground(roundRect(R.color.card2, 13));
            row.addView(n, new LinearLayout.LayoutParams(dp(26), dp(26)));

            TextView l;
            if (label != null) {
                l = text(label, 16, R.color.text, R.font.plex_regular);
            } else {
                l = text("أرسل  " + MainActivity.LTR_START + t + MainActivity.LTR_END, 16, R.color.text, R.font.plex_regular);
                row.setForeground(selectable());
                row.setOnClickListener(v -> askLiteral(t, val -> {
                    steps.set(idx, val);
                    buildSteps(g, footer, steps);
                }));
            }
            l.setPadding(dp(14), dp(12), dp(8), dp(12));
            row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));

            TextView up = iconText("↑", idx == 0 ? R.color.text3 : R.color.accent);
            if (idx > 0) up.setOnClickListener(v -> {
                steps.add(idx - 1, steps.remove(idx));
                buildSteps(g, footer, steps);
            });
            row.addView(up);
            TextView del = iconText("×", R.color.danger);
            del.setOnClickListener(v -> {
                steps.remove(idx);
                buildSteps(g, footer, steps);
            });
            row.addView(del);
            add(g, row, dp(56));
        }

        List<String> clean = new ArrayList<>();
        for (String s : steps) if (!s.trim().isEmpty()) clean.add(s.trim());
        boolean ok = !clean.isEmpty() && clean.get(clean.size() - 1).equalsIgnoreCase("FINAL");
        if (ok) {
            prefs.setSteps(String.join(",", clean));
            footer.setText("التطبيق بيمشي هاي الخطوات مع البنك بالترتيب. اضغط على أي رقم أو رمز عشان تغيّره.");
            footer.setTextColor(getColor(R.color.text2));
        } else {
            footer.setText("ما انحفظت: آخر خطوة لازم تكون \"رمز التأكيد الأخير\" عشان البصمة تضل قبل ما ينبعت.");
            footer.setTextColor(getColor(R.color.danger));
        }
    }

    private void addStep(List<String> steps, Runnable done) {
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        labels.add("رقم أو رمز (مثل 6 أو #)");
        values.add("");
        for (String t : TOKENS) {
            if (indexOfToken(steps, t) < 0) {
                labels.add(stepLabel(t));
                values.add(t);
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("إضافة خطوة")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        askLiteral(null, val -> {
                            int finalAt = indexOfToken(steps, "FINAL");
                            if (finalAt >= 0) steps.add(finalAt, val); else steps.add(val);
                            done.run();
                        });
                    } else {
                        steps.add(values.get(which));
                        done.run();
                    }
                })
                .show();
    }

    private interface Literal { void set(String value); }

    private void askLiteral(String current, Literal onSet) {
        EditText v = new EditText(this);
        v.setInputType(InputType.TYPE_CLASS_PHONE);
        v.setHint("مثلاً 6");
        if (current != null) v.setText(current);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        box.addView(v);
        new AlertDialog.Builder(this)
                .setTitle("شو بدك يبعت التطبيق بهاي الخطوة؟")
                .setView(box)
                .setPositiveButton(current == null ? "إضافة" : "حفظ", (d, w) -> {
                    String val = v.getText().toString().trim().replace(",", "");
                    if (!val.isEmpty()) onSet.set(val);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private static int indexOfToken(List<String> steps, String t) {
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).trim().equalsIgnoreCase(t)) return i;
        return -1;
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    private void push(Page p, boolean animate) {
        hideKeyboard();
        stack.push(p);
        show(render(p), animate ? 1 : 0);
    }

    private void pop() {
        hideKeyboard();
        stack.pop();
        show(render(stack.peek()), -1);
    }

    /** Redraws the page on top, keeping the scroll position. */
    private void rerender() {
        int y = current instanceof ScrollView ? current.getScrollY() : 0;
        View v = render(stack.peek());
        show(v, 0);
        v.post(() -> v.scrollTo(0, y));
    }

    private void show(View v, int dir) {
        View old = current;
        current = v;
        if (old == null || dir == 0) {
            pages.addView(v);
            if (old != null) pages.removeView(old);
            return;
        }
        int w = pages.getWidth();
        DecelerateInterpolator ease = new DecelerateInterpolator(2.2f);
        if (dir > 0) {
            // RTL: the new page comes in from the left, the old one drifts right.
            pages.addView(v);
            v.setTranslationX(-w);
            v.animate().translationX(0).setDuration(320).setInterpolator(ease).start();
            old.animate().translationX(w * 0.3f).alpha(0f).setDuration(320).setInterpolator(ease)
                    .withEndAction(() -> pages.removeView(old)).start();
        } else {
            pages.addView(v, 0);
            v.setTranslationX(w * 0.3f);
            v.setAlpha(0f);
            v.animate().translationX(0).alpha(1f).setDuration(300).setInterpolator(ease).start();
            old.animate().translationX(-w).setDuration(300).setInterpolator(ease)
                    .withEndAction(() -> pages.removeView(old)).start();
        }
    }

    private View render(Page p) {
        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.bg));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), 0, dp(20), dp(36));
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));

        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setBackgroundResource(R.drawable.circle_card);
        back.setContentDescription("رجوع");
        back.setOnClickListener(v -> onBackPressed());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(46), dp(46));
        blp.topMargin = dp(20);
        body.addView(back, blp);

        TextView title = text(p.title, stack.size() > 1 ? 28 : 34, R.color.text, R.font.plex_semibold);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(14);
        tlp.setMarginStart(dp(4));
        body.addView(title, tlp);

        p.builder.build(body);
        return scroll;
    }

    // =========================================================================
    // Building blocks
    // =========================================================================

    /** Section header + rounded group + optional footer. Returns the group to add rows to. */
    private LinearLayout group(LinearLayout body, String header, String footerText) {
        if (header != null) body.addView(sectionHeader(header));
        LinearLayout g = groupCard();
        if (header == null) ((LinearLayout.LayoutParams) g.getLayoutParams()).topMargin = dp(24);
        body.addView(g);
        if (footerText != null) body.addView(footer(footerText));
        return g;
    }

    private LinearLayout groupCard() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(roundRect(R.color.card, 16));
        g.setClipToOutline(true);
        g.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return g;
    }

    private TextView sectionHeader(String s) {
        TextView h = text(s, 13, R.color.text2, R.font.plex_medium);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(26);
        lp.bottomMargin = dp(8);
        lp.setMarginStart(dp(16));
        h.setLayoutParams(lp);
        return h;
    }

    private TextView footer(String s) {
        TextView f = text(s, 13, R.color.text2, R.font.plex_regular);
        f.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        f.setPadding(dp(16), 0, dp(16), 0);
        f.setLayoutParams(lp);
        return f;
    }

    /** Adds a row to a group, with a hairline between rows (inset past the icon). */
    private void add(LinearLayout g, View row) {
        add(g, row, row.getTag() instanceof Integer ? (Integer) row.getTag() : dp(16));
    }

    private void add(LinearLayout g, View row, int inset) {
        if (g.getChildCount() > 0) {
            View line = new View(this);
            line.setBackgroundColor(getColor(R.color.line));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
            lp.setMarginStart(inset);
            g.addView(line, lp);
        }
        g.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private View row(int icon, int tileColor, String title, String sub, String value, int valueColor,
                     boolean chevron, View trailing, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(sub != null ? 62 : 54));
        row.setPadding(dp(16), dp(8), dp(icon != 0 || chevron ? 14 : 16), dp(8));

        if (icon != 0) {
            row.addView(tile(icon, R.color.accentSoft, R.color.accent, 30, 8), new LinearLayout.LayoutParams(dp(30), dp(30)));
            row.setTag(dp(16 + 30 + 14));
        }

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(icon != 0 ? dp(14) : 0, 0, dp(8), 0);
        mid.addView(text(title, 16, R.color.text, R.font.plex_regular));
        if (sub != null) mid.addView(text(sub, 12, R.color.text2, R.font.plex_regular));
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));

        if (value != null && !value.isEmpty()) {
            TextView v = text(value, 15, valueColor, R.font.plex_regular);
            v.setSingleLine(true);
            v.setMaxWidth(dp(170));
            v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(v);
        }
        if (trailing != null) row.addView(trailing);
        if (chevron) row.addView(chevron());
        if (click != null) {
            row.setForeground(selectable());
            row.setOnClickListener(click);
        }
        return row;
    }

    private interface Toggle { void set(boolean on); }

    private View switchRow(int icon, int tileColor, String title, boolean checked, Toggle onChange) {
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, on) -> onChange.set(on));
        View row = row(icon, tileColor, title, null, null, 0, false, sw, v -> sw.toggle());
        row.setForeground(null);
        return row;
    }

    private View fieldRow(int icon, int tileColor, EditText field) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), 0, dp(16), 0);
        row.addView(tile(icon, R.color.accentSoft, R.color.accent, 30, 8), new LinearLayout.LayoutParams(dp(30), dp(30)));
        row.setTag(dp(16 + 30 + 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
        lp.setMarginStart(dp(14));
        row.addView(field, lp);
        return row;
    }

    private EditText input(String hint, int type, boolean ltr) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setBackground(null);
        e.setSingleLine(true);
        e.setPadding(0, 0, 0, 0);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setTextColor(getColor(R.color.text));
        e.setHintTextColor(getColor(R.color.text3));
        e.setTypeface(getResources().getFont(R.font.plex_regular));
        e.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        if (ltr) e.setTextDirection(View.TEXT_DIRECTION_LTR);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return e;
    }

    private View actionRow(String label, int color, View.OnClickListener click) {
        TextView t = text(label, 16, color, R.font.plex_medium);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(52));
        t.setForeground(selectable());
        t.setOnClickListener(click);
        return t;
    }

    private View tile(int icon, int color, int fg, int sizeDp, int radiusDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(getColor(fg));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        if (sizeDp > 36) {
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = dp(sizeDp / 4);
            iv.setPadding(pad, pad, pad, pad);
        }
        iv.setBackground(roundRect(color, radiusDp));
        return iv;
    }

    private ImageView chevron() {
        ImageView c = new ImageView(this);
        c.setImageResource(R.drawable.ic_chevron);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(18), dp(18));
        lp.setMarginStart(dp(4));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView iconText(String s, int color) {
        TextView t = text(s, 20, color, R.font.plex_medium);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(44)));
        return t;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(getColor(color));
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private android.graphics.drawable.Drawable selectable() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return getDrawable(tv.resourceId);
    }

    private TextView text(String s, int sp, int color, int font) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(color));
        Typeface tf = getResources().getFont(font);
        t.setTypeface(tf);
        return t;
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static int clamp(int i, int n) {
        return i < 0 || i >= n ? 0 : i;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) {
            imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
            f.clearFocus();
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
