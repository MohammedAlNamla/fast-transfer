package com.fasttransfer.app;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the bank's USSD dialogs and answers them step by step while a Session is active.
 * Does nothing at all when no transfer was started from the app.
 */
public class UssdService extends AccessibilityService {

    static UssdService instance;
    private static final String TAG = "FastTransfer";

    private static final String[] SEND_WORDS = {"إرسال", "ارسال", "send", "reply", "رد", "موافق", "ok", "حسنا", "حسنًا"};
    private static final String[] CANCEL_WORDS = {"إلغاء", "الغاء", "cancel", "dismiss", "رفض", "خروج"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View overlay;
    /** Context with the chosen light/dark mode, refreshed before each overlay. */
    private Context ui;

    private final Runnable process = this::process;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (Session.current == null) return;
            process();
            main.postDelayed(this, 700);
        }
    };

    // --- lifecycle -------------------------------------------------------

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
    }

    /** Called by MainActivity right after it dials the bank code. */
    void begin() {
        removeOverlay();
        main.removeCallbacks(poll);
        main.postDelayed(poll, 700);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (Session.current == null) return;
        main.removeCallbacks(process);
        main.postDelayed(process, 250);
    }

    // --- the state machine ------------------------------------------------

    private void process() {
        Session s = Session.current;
        if (s == null) return;

        if (s.expired()) {
            finish("انتهى الوقت وما كملت العملية. افحص رسائل البنك قبل ما تعيد.", false);
            return;
        }

        Dialog d = findUssdDialog();
        if (d == null || d.positive == null) {
            // No dialog, or the "running USSD code…" spinner: the last answered dialog is gone.
            s.sawGap = true;
            return;
        }

        if (!s.sawGap && TextUtils.equals(d.text, s.lastText)) return; // already handled
        if (s.waitingUser && TextUtils.equals(d.text, s.lastText)) return;
        // The dialog we just answered can linger for a moment, still showing our answer
        // (MIUI reuses the same window). That is not a new question from the bank.
        if (d.input != null && TextUtils.equals(d.text, s.lastText)) {
            boolean holdsOurAnswer = s.lastSent != null && TextUtils.equals(d.inputText, s.lastSent);
            boolean justReplied = System.currentTimeMillis() - s.lastReplyAt < 4000;
            if (holdsOurAnswer || justReplied) return;
        }

        // MIUI fills the message a moment after the window opens: wait for it (up to ~3 s).
        if (!d.hasMessage) {
            if (s.messageWaitSince == 0) s.messageWaitSince = System.currentTimeMillis();
            if (System.currentTimeMillis() - s.messageWaitSince < 3000) return;
        }
        s.messageWaitSince = 0;
        Log.i(TAG, "step " + s.index + "/" + s.steps.size() + " input=" + (d.input != null) + " bank: " + d.text);

        s.lastText = d.text;
        s.sawGap = false;
        s.waitingUser = false;
        removeOverlay();

        // The bank complained (low balance, wrong number, limit...) but still shows an input box:
        // never answer it with the next planned value. Stop, close the bank session, show its words.
        if (d.input != null && isError(d.text)) {
            if (d.negative != null) click(d.negative);
            finish(d.text, true, History.FAILED);
            return;
        }

        if (d.input == null) {
            // A dialog with no input box is the bank's last word: success or an error.
            click(d.positive);
            finish(d.text, true);
            return;
        }

        Session.Step step = s.step();
        if (step == null) {
            if (s.finalSent) {
                // After the last code the bank asked again: the code was most likely refused.
                // Send nothing more; close the session and say so plainly.
                if (d.negative != null) click(d.negative);
                finish("البنك طلب رمز التأكيد مرة ثانية، يعني على الأغلب ما قبل الرمز. ما انبعت إشي زيادة. "
                        + "افحص رسائل البنك قبل ما تعيد.\n\n" + d.text, true, History.FAILED);
                return;
            }
            // More questions than we know answers to: stop and close, never guess.
            if (d.negative != null) click(d.negative);
            finish("البنك طلب خطوة ما بعرفها، فوقفت وما بعتت إشي:\n\n" + d.text, true, History.FAILED);
            return;
        }

        switch (step.kind) {
            case SEND:
                s.index++;
                reply(d, step.value);
                break;
            case ACCOUNT:
                // Learn the account names the bank lists, and refuse an account that isn't there.
                java.util.Map<String, String> opts = parseOptions(d.text);
                if (!opts.isEmpty()) new Prefs(this).learnAccounts(opts);
                if (!opts.isEmpty() && !opts.containsKey(step.value)) {
                    if (d.negative != null) click(d.negative);
                    finish("الحساب رقم " + step.value + " مش موجود عند البنك. الحسابات المتاحة:\n" + d.text,
                            true, History.FAILED);
                    return;
                }
                s.index++;
                reply(d, step.value);
                break;
            case NAME:
                // Remember who the bank says this is, answer "1", and confirm everything at the end.
                String n = Prefs.extractName(d.text);
                if (n != null) s.name = n;
                s.index++;
                reply(d, step.value);
                break;
            case FINAL:
                s.waitingUser = true;
                showConfirm(s);
                break;
        }
    }

    /** "1. توفير-0 (32.86) شيكل" lines → {"1": "توفير-0 (32.86) شيكل"}; "0. خروج" is skipped. */
    static java.util.Map<String, String> parseOptions(String text) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String line : text.split("\n")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^[\\s\\u200e\\u200f]*(\\d+)\\s*[.\\-)]\\s*(.+)$").matcher(line);
            if (m.find() && !m.group(1).equals("0")) out.put(m.group(1), m.group(2).trim());
        }
        return out;
    }

    private void reply(Dialog d, String value) {
        Session s = Session.current;
        if (!setText(d.input, value)) {
            finish("ما قدرت أكتب في نافذة البنك. كمّل بإيدك.", false);
            return;
        }
        if (s != null) {
            s.lastSent = value;
            s.lastReplyAt = System.currentTimeMillis();
        }
        AccessibilityNodeInfo btn = d.positive;
        main.postDelayed(() -> click(btn), 250);
    }

    /** The user answered the final summary. Yes = fingerprint, then the last code. */
    private void onConfirm(boolean yes) {
        Session s = Session.current;
        removeOverlay();
        if (s == null) return;
        if (!yes) {
            cancelBank();
            finish("لغيت التحويل.", false, History.CANCELLED);
            return;
        }
        String who = s.name != null ? s.name : s.phone;
        AuthActivity.start(this, "تأكيد التحويل",
                "₪ " + s.amount + " إلى " + who, new PinVault.Result() {
                    @Override
                    public void ok(String pin) {
                        sendFinal(pin);
                    }

                    @Override
                    public void fail(String message) {
                        cancelBank();
                        finish(message + "\nما انبعت إشي للبنك.", false, History.CANCELLED);
                    }
                });
    }

    private void sendFinal(String pin) {
        Session s = Session.current;
        if (s == null) return;
        Dialog d = findUssdDialog();
        if (d == null || d.input == null) {
            finish("انقطعت جلسة البنك قبل آخر خطوة، وما انبعت الرقم السري الأخير. جرّب مرة ثانية.",
                    false, History.FAILED);
            return;
        }
        new Prefs(this).setNameIfMissing(s.phone, s.name);
        if (s.test) {
            // Test mode: everything worked up to here. Leave without sending the last code.
            if (d.negative != null) click(d.negative);
            finish("وضع التجربة: كل الخطوات زبطت لحد آخر خطوة، والبصمة زبطت. ما انبعت الرقم السري الأخير، فما صار تحويل.",
                    false, History.TEST);
            return;
        }
        s.index++;
        s.waitingUser = false;
        s.finalSent = true;
        if (s.finalIsPin) {
            reply(d, pin);
        } else {
            s.waitingUser = true;
            showHint("اكتب رمز التأكيد في نافذة البنك واضغط إرسال");
        }
    }

    /** Closes the bank dialog; retries a few times because MIUI sometimes ignores the first tap. */
    private void cancelBank() {
        for (int i = 0; i < 4; i++) {
            main.postDelayed(() -> {
                Dialog d = findUssdDialog();
                if (d != null && d.negative != null) click(d.negative);
            }, i * 700L);
        }
    }

    private void finish(String message, boolean fromBank) {
        finish(message, fromBank, null);
    }

    private void finish(String message, boolean fromBank, String status) {
        Session s = Session.current;
        Session.current = null; // drops the PIN from memory
        main.removeCallbacks(poll);
        main.removeCallbacks(process);

        boolean success = fromBank && isSuccess(message);
        if (status == null) status = success ? History.SUCCESS : History.FAILED;
        if (s != null) {
            String who = s.name != null ? s.name : s.phone;
            new Prefs(this).setLastResult("₪ " + s.amount + " إلى " + who + "\n" + message);
            History.add(this, s.phone, s.name, s.amount, status, message);
        }
        showResult(message, success, status, s);
    }

    private static final String ERROR_WORDS =
            "(?s).*(لم تتم|لم يتم|فشل|خطأ|خاطئ|غير صحيح|غير صالح|مرفوض|رفض|غير كاف|لا يكفي|لا يوجد رصيد|"
                    + "تجاوز|الحد الأعلى|الحد الأدنى|غير متاح|غير مسجل|غير مشترك|موقوف|محظور|"
                    + "حاول لاحقا|حاول لاحقاً|انتهت|error|failed|insufficient|invalid).*";

    static boolean isError(String text) {
        return text != null && text.matches(ERROR_WORDS);
    }

    static boolean isSuccess(String text) {
        return text.matches("(?s).*(تم|نجاح|بنجاح|success|Success).*") && !isError(text);
    }

    // --- reading the USSD dialog -------------------------------------------

    private static final class Dialog {
        String text = "";
        boolean hasMessage;
        String inputText;
        AccessibilityNodeInfo input;
        AccessibilityNodeInfo positive;
        AccessibilityNodeInfo negative;
    }

    private Dialog findUssdDialog() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) roots.add(r);
            }
        } catch (Exception ignored) {
        }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) roots.add(active);

        // MIUI can stack two phone dialogs; prefer the one asking for input, then any with a message.
        Dialog fallback = null;
        for (AccessibilityNodeInfo root : roots) {
            CharSequence pkg = root.getPackageName();
            if (pkg == null) continue;
            String p = pkg.toString();
            if (p.equals(getPackageName())) continue;
            // USSD replies come from the phone process. Telecom (the SIM picker) is left alone on purpose.
            if (!(p.contains("phone") || p.contains("stk") || p.contains("ussd"))) continue;

            root.refresh();
            Dialog d = new Dialog();
            List<String> texts = new ArrayList<>();
            List<AccessibilityNodeInfo> buttons = new ArrayList<>();
            walk(root, d, texts, buttons);

            String msg = messageText(root);
            d.text = msg != null ? msg : TextUtils.join("\n", texts).trim();
            d.hasMessage = msg != null;
            if (d.text.isEmpty() && d.input == null) continue;
            pickButtons(root, d, buttons);

            if (d.input != null) return d;
            if (fallback == null || (!fallback.hasMessage && d.hasMessage)) fallback = d;
        }
        return fallback;
    }

    /** The bank's own words, from the dialog's message field (never the window title). */
    private static String messageText(AccessibilityNodeInfo root) {
        for (String id : new String[]{"com.android.phone:id/ussd_message", "android:id/message",
                "com.android.phone:id/message", "com.android.phone:id/dialog_message"}) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) {
                n.refresh();
                CharSequence t = n.getText();
                if (t != null && t.toString().trim().length() > 0) return t.toString().trim();
            }
        }
        return null;
    }

    private void walk(AccessibilityNodeInfo n, Dialog d, List<String> texts, List<AccessibilityNodeInfo> buttons) {
        if (n == null) return;
        CharSequence cls = n.getClassName();
        String c = cls == null ? "" : cls.toString();
        String vid = n.getViewIdResourceName();
        if (vid != null && (vid.endsWith("alertTitle") || vid.endsWith("title"))) {
            // window title ("carrier information"), not the bank's message
        } else if (n.isEditable() || c.endsWith("EditText")) {
            if (d.input == null) {
                d.input = n;
                d.inputText = n.getText() == null ? "" : n.getText().toString();
            }
        } else if (c.endsWith("Button") || (n.isClickable() && n.getChildCount() == 0 && n.getText() != null && n.getText().length() < 20)) {
            buttons.add(n);
        } else if (n.getText() != null && n.getText().length() > 0) {
            texts.add(n.getText().toString());
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), d, texts, buttons);
    }

    private void pickButtons(AccessibilityNodeInfo root, Dialog d, List<AccessibilityNodeInfo> buttons) {
        List<AccessibilityNodeInfo> b1 = root.findAccessibilityNodeInfosByViewId("android:id/button1");
        List<AccessibilityNodeInfo> b2 = root.findAccessibilityNodeInfosByViewId("android:id/button2");
        if (b1 != null && !b1.isEmpty()) d.positive = b1.get(0);
        if (b2 != null && !b2.isEmpty()) d.negative = b2.get(0);

        for (AccessibilityNodeInfo b : buttons) {
            String t = b.getText() == null ? "" : b.getText().toString().trim().toLowerCase();
            if (d.negative == null && matches(t, CANCEL_WORDS)) d.negative = b;
            else if (d.positive == null && matches(t, SEND_WORDS)) d.positive = b;
        }
        if (d.positive == null) {
            for (AccessibilityNodeInfo b : buttons) {
                if (b != d.negative) {
                    d.positive = b;
                    break;
                }
            }
        }
    }

    private static boolean matches(String t, String[] words) {
        for (String w : words) if (t.equals(w) || t.startsWith(w)) return true;
        return false;
    }

    private boolean setText(AccessibilityNodeInfo input, String value) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        if (input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true;

        // Fallback: paste, then wipe the clipboard right away.
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return false;
        cm.setPrimaryClip(ClipData.newPlainText("", value));
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean ok = input.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        main.postDelayed(() -> {
            try {
                cm.clearPrimaryClip();
            } catch (Exception ignored) {
            }
        }, 500);
        return ok;
    }

    private static void click(AccessibilityNodeInfo n) {
        if (n == null) return;
        AccessibilityNodeInfo cur = n;
        while (cur != null && !cur.isClickable()) cur = cur.getParent();
        (cur != null ? cur : n).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    // --- overlays ------------------------------------------------------------

    private void showConfirm(Session s) {
        ui = Appearance.wrap(this);
        String saved = new Prefs(this).name(s.phone);
        String shown = s.name != null ? s.name : saved;
        boolean bio = new PinVault(this).usesBio();

        LinearLayout card = card();
        card.addView(handle());

        if (s.test) {
            TextView badge = label("وضع التجربة: ما رح يصير تحويل", 12, color(R.color.warn), R.font.plex_medium);
            badge.setGravity(Gravity.CENTER);
            card.addView(badge, wrap(dp(12), 0));
        }

        // The question, in plain words
        TextView q = label("بدك تحوّل لـ " + (shown != null ? shown : MainActivity.LTR_START + s.phone + MainActivity.LTR_END)
                + " مبلغ " + MainActivity.LTR_START + s.amount + " ₪" + MainActivity.LTR_END + "؟",
                21, color(R.color.text), R.font.plex_semibold);
        q.setLineSpacing(dp(3), 1f);
        card.addView(q, wrap(dp(14), 0));

        // Who: gradient avatar + name + number
        LinearLayout who = new LinearLayout(ui);
        who.setGravity(Gravity.CENTER_VERTICAL);
        TextView avatar = label(shown != null ? Prefs.initials(shown) : "؟", 18, Color.WHITE, R.font.plex_semibold);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackgroundResource(R.drawable.grad_circle);
        who.addView(avatar, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout names = new LinearLayout(ui);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(14), 0, 0, 0);
        names.addView(label(shown != null ? shown : "تأكد من اسم المستلم", 19, color(R.color.text), R.font.plex_semibold));
        names.addView(label(MainActivity.LTR_START + s.phone + MainActivity.LTR_END, 14, color(R.color.text2), R.font.plex_regular));
        who.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        card.addView(who, wrap(dp(18), 0));

        // What
        LinearLayout details = new LinearLayout(ui);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(16), dp(4), dp(16), dp(4));
        GradientDrawable dbg = new GradientDrawable();
        dbg.setColor(color(R.color.card2));
        dbg.setCornerRadius(dp(16));
        details.setBackground(dbg);
        details.addView(detailRow("المبلغ", MainActivity.LTR_START + "₪ " + s.amount + MainActivity.LTR_END, true));
        details.addView(divider());
        details.addView(detailRow("من", accountName(s.account), false));
        card.addView(details, wrap(dp(18), 0));

        TextView note = label(bio ? "بعد التأكيد بتحط بصمتك، وبعدها بينبعت الرقم السري الأخير للبنك."
                        : "بعد التأكيد بينبعت الرقم السري الأخير للبنك.",
                12, color(R.color.text2), R.font.plex_regular);
        note.setLineSpacing(dp(2), 1f);
        card.addView(note, wrap(dp(12), 0));

        Button yes = button(bio ? "تأكيد بالبصمة" : "تأكيد التحويل", color(R.color.primary), color(R.color.onPrimary));
        yes.setTypeface(getResources().getFont(R.font.plex_semibold));
        card.addView(yes, full(dp(56), dp(18)));
        Button no = button("إلغاء", Color.TRANSPARENT, color(R.color.text2));
        card.addView(no, full(dp(46), dp(4)));

        no.setOnClickListener(v -> onConfirm(false));
        yes.setOnClickListener(v -> onConfirm(true));
        show(card, true);
    }

    private String accountName(String number) {
        String l = new Prefs(this).accounts().get(number);
        return l != null ? l : "حساب " + number;
    }

    private View divider() {
        View d = new View(ui);
        d.setBackgroundColor(color(R.color.line));
        d.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        return d;
    }

    private void showHint(String text) {
        ui = Appearance.wrap(this);
        LinearLayout card = card();
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        card.addView(label(text, 15, color(R.color.text), R.font.plex_medium));
        show(card, false);
    }

    private void showResult(String text, boolean success, String status, Session s) {
        ui = Appearance.wrap(this);
        boolean test = History.TEST.equals(status);
        boolean good = success || test;

        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(handle());

        ImageView icon = new ImageView(ui);
        icon.setImageResource(good ? R.drawable.ic_check : R.drawable.ic_info);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        if (good) {
            icon.setBackgroundResource(R.drawable.grad_circle);
        } else {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color(R.color.card2));
            icon.setBackground(g);
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(78), dp(78));
        ilp.topMargin = dp(22);
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(icon, ilp);

        TextView title = label(success ? "تم التحويل" : test ? "التجربة زبطت"
                        : History.CANCELLED.equals(status) ? "انلغى التحويل" : "ما تمّ التحويل",
                22, color(R.color.text), R.font.plex_semibold);
        title.setGravity(Gravity.CENTER);
        card.addView(title, wrap(dp(16), 0));

        if (success && s != null) {
            TextView amt = label(MainActivity.LTR_START + "₪ " + s.amount + MainActivity.LTR_END, 32,
                    color(R.color.text), R.font.plex_semibold);
            amt.setGravity(Gravity.CENTER);
            card.addView(amt, wrap(dp(2), 0));
            String who = s.name != null ? s.name : new Prefs(this).name(s.phone);
            TextView to = label("إلى " + (who != null ? who : s.phone), 15, color(R.color.text2), R.font.plex_regular);
            to.setGravity(Gravity.CENTER);
            card.addView(to, wrap(dp(4), 0));
        }

        TextView body = label(text, success ? 12 : 15, color(success ? R.color.text3 : R.color.text2), R.font.plex_regular);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(dp(3), 1f);
        card.addView(body, wrap(dp(success ? 14 : 8), 0));

        Button ok = button("تم", color(R.color.primary), color(R.color.onPrimary));
        ok.setTypeface(getResources().getFont(R.font.plex_semibold));
        card.addView(ok, full(dp(56), dp(22)));
        ok.setOnClickListener(v -> removeOverlay());
        show(card, true);
        main.postDelayed(this::removeOverlay, 20000);
    }

    private View handle() {
        View h = new View(ui);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color(R.color.line));
        g.setCornerRadius(dp(3));
        h.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(5));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        h.setLayoutParams(lp);
        return h;
    }

    private LinearLayout detailRow(String key, String value, boolean strong) {
        LinearLayout row = new LinearLayout(ui);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TextView k = label(key, 14, color(R.color.text2), R.font.plex_regular);
        TextView v = label(value, strong ? 17 : 15, color(R.color.text),
                strong ? R.font.plex_semibold : R.font.plex_medium);
        row.addView(k, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(v);
        return row;
    }

    private static LinearLayout.LayoutParams wrap(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        return lp;
    }

    private static LinearLayout.LayoutParams full(int height, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height);
        lp.topMargin = top;
        return lp;
    }

    /** blocking = dim backdrop that catches taps; otherwise a small banner that lets taps through. */
    private void show(LinearLayout card, boolean blocking) {
        removeOverlay();
        if (wm == null) return;

        View root;
        WindowManager.LayoutParams lp;
        if (blocking) {
            LinearLayout backdrop = new LinearLayout(ui);
            backdrop.setGravity(Gravity.BOTTOM);
            backdrop.setBackgroundColor(color(R.color.scrim));
            backdrop.setPadding(dp(10), 0, dp(10), dp(14));
            backdrop.addView(card, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            root = backdrop;
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
        } else {
            root = card;
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            lp.y = dp(40);
            lp.horizontalMargin = 0.03f;
        }
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        try {
            wm.addView(root, lp);
            overlay = root;
        } catch (Exception ignored) {
        }
    }

    private void removeOverlay() {
        if (overlay != null && wm != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception ignored) {
            }
        }
        overlay = null;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(ui);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(22), dp(10), dp(22), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.card));
        bg.setCornerRadius(dp(28));
        c.setBackground(bg);
        c.setElevation(dp(12));
        return c;
    }

    private TextView label(String text, int sp, int color, int font) {
        TextView t = new TextView(ui);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setTypeface(getResources().getFont(font));
        return t;
    }

    private Button button(String text, int bgColor, int fg) {
        Button b = new Button(ui);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(getResources().getFont(R.font.plex_medium));
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        return b;
    }

    private int color(int res) {
        return (ui != null ? ui : this).getColor(res);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
