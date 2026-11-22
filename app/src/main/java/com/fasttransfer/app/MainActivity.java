package com.fasttransfer.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1;
    /** Keeps numbers and codes reading left-to-right inside Arabic text. */
    static final String LTR_START = "⁦";
    static final String LTR_END = "⁩";

    private Prefs prefs;
    private PinVault vault;
    private EditText phone, amount;
    private TextView fromAccount, codeLabel, result, setupTitle, setupSub;
    private ImageView setupIcon;
    private LinearLayout people, resultCard, setupCard;
    private Button send;

    private int appliedTheme;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        appliedTheme = Appearance.get(this);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        vault = new PinVault(this);

        phone = findViewById(R.id.phone);
        amount = findViewById(R.id.amount);
        fromAccount = findViewById(R.id.fromAccount);
        codeLabel = findViewById(R.id.codeLabel);
        result = findViewById(R.id.result);
        resultCard = findViewById(R.id.resultCard);
        people = findViewById(R.id.people);
        setupCard = findViewById(R.id.setupCard);
        setupIcon = findViewById(R.id.setupIcon);
        setupTitle = findViewById(R.id.setupTitle);
        setupSub = findViewById(R.id.setupSub);
        send = findViewById(R.id.btnSend);

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.walletCard).setOnClickListener(v -> focus(amount));
        findViewById(R.id.btnHistory).setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        findViewById(R.id.resultCard).setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        send.setOnClickListener(v -> startTransfer());

        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void afterTextChanged(Editable s) {
                updateButton();
                highlightPerson();
            }
        };
        phone.addTextChangedListener(w);
        amount.addTextChangedListener(w);
        updateButton();

        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE}, REQ_PERMS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Appearance.get(this) != appliedTheme) {
            recreate();
            return;
        }
        recheckPosted = false;
        refresh();
    }

    private void updateButton() {
        boolean ready = phone.length() > 0 && amount.length() > 0;
        send.setAlpha(ready ? 1f : 0.35f);
    }

    private void refresh() {
        fromAccount.setText((prefs.testMode() ? "وضع التجربة • " : "من: ") + prefs.accountLabel() + "  ⌄");
        fromAccount.setOnClickListener(v -> chooseAccount());
        codeLabel.setText(LTR_START + prefs.code() + LTR_END);

        KeepAlive.State st = KeepAlive.state(this);
        if (st == KeepAlive.State.OFF) {
            setupIcon.setImageResource(R.drawable.ic_shield);
            setupTitle.setText("فعّل خدمة التحويل");
            setupSub.setText("اضغط هون وشغّل \"تحويل سريع\"");
            setupCard.setOnClickListener(v -> KeepAlive.openServicePage(this));
            setupCard.setVisibility(View.VISIBLE);
        } else if (st == KeepAlive.State.STOPPED) {
            setupIcon.setImageResource(R.drawable.ic_shield);
            setupTitle.setText("خدمة التحويل وقفت");
            setupSub.setText("اضغط هون عشان ترجّعها");
            setupCard.setOnClickListener(v -> showStoppedHelp(false));
            setupCard.setVisibility(View.VISIBLE);
            // Right after launch the system may still be connecting it.
            if (!recheckPosted) {
                recheckPosted = true;
                setupCard.postDelayed(() -> { if (!isFinishing()) refresh(); }, 1200);
            }
        } else if (KeepAlive.autostart(this) == 0) {
            setupIcon.setImageResource(R.drawable.ic_shield);
            setupTitle.setText("خليه يضل شغّال");
            setupSub.setText("شغّل \"التشغيل التلقائي\" للتطبيق عشان ما يوقف");
            setupCard.setOnClickListener(v -> KeepAlive.openAutostart(this));
            setupCard.setVisibility(View.VISIBLE);
        } else if (!vault.hasPin()) {
            setupIcon.setImageResource(R.drawable.ic_lock_warn);
            setupTitle.setText("احفظ الرقم السري");
            setupSub.setText("مشفّر على جوالك، وبيفتح بالبصمة");
            setupCard.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
            setupCard.setVisibility(View.VISIBLE);
        } else {
            setupCard.setVisibility(View.GONE);
        }

        String last = prefs.lastResult();
        resultCard.setVisibility(last == null ? View.GONE : View.VISIBLE);
        if (last != null) result.setText(last);

        buildPeople();
    }

    private void chooseAccount() {
        java.util.LinkedHashMap<String, String> acc = prefs.accounts();
        String[] numbers = acc.keySet().toArray(new String[0]);
        String[] labels = new String[numbers.length];
        int checked = 0;
        for (int i = 0; i < numbers.length; i++) {
            labels[i] = acc.get(numbers[i]) + "  (" + numbers[i] + ")";
            if (numbers[i].equals(prefs.account())) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("من أي حساب؟")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    prefs.setAccount(numbers[which]);
                    d.dismiss();
                    refresh();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // --- people row (Apple Pay style) ---------------------------------------

    private void buildPeople() {
        people.removeAllViews();
        people.addView(contactsButton());
        people.addView(person(null, null));
        for (String p : prefs.recent()) people.addView(person(p, prefs.name(p)));
        highlightPerson();
    }

    private View person(String number, String name) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(68), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        col.setLayoutParams(lp);

        FrameLayout circle = new FrameLayout(this);
        circle.setTag(number);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(56), dp(56));
        circle.setLayoutParams(clp);

        if (number == null) {
            circle.setBackgroundResource(R.drawable.circle_card2);
            ImageView plus = new ImageView(this);
            plus.setImageResource(R.drawable.ic_plus);
            plus.setScaleType(ImageView.ScaleType.CENTER);
            circle.addView(plus, new FrameLayout.LayoutParams(-1, -1));
        } else {
            circle.setBackgroundResource(R.drawable.circle_card);
            TextView t = new TextView(this);
            String ini = Prefs.initials(name);
            t.setText(ini != null ? ini : number.substring(Math.max(0, number.length() - 2)));
            t.setGravity(Gravity.CENTER);
            t.setTextColor(getColor(R.color.text));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setTypeface(getResources().getFont(R.font.plex_medium));
            circle.addView(t, new FrameLayout.LayoutParams(-1, -1));
        }
        col.addView(circle);

        TextView label = new TextView(this);
        String text = number == null ? "جديد"
                : (name != null ? firstName(name) : LTR_START + number.substring(Math.max(0, number.length() - 4)) + LTR_END);
        label.setText(text);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(getColor(R.color.text2));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(6);
        col.addView(label, tlp);

        if (number == null) {
            col.setOnClickListener(v -> {
                phone.setText("");
                focus(phone);
            });
        } else {
            col.setOnClickListener(v -> {
                phone.setText(number);
                phone.setSelection(number.length());
                if (amount.length() == 0) focus(amount);
            });
            col.setOnLongClickListener(v -> {
                personMenu(number, name);
                return true;
            });
        }
        return col;
    }

    private View contactsButton() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(68), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        col.setLayoutParams(lp);
        FrameLayout circle = new FrameLayout(this);
        circle.setBackgroundResource(R.drawable.circle_card2);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_contacts);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        circle.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        col.addView(circle, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView label = new TextView(this);
        label.setText("جهات الاتصال");
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(getColor(R.color.text2));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(6);
        col.addView(label, tlp);
        col.setOnClickListener(v -> pickContact());
        return col;
    }

    private void personMenu(String number, String name) {
        String title = name != null ? name : number;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(new String[]{"تغيير الاسم", "حذف من القائمة"}, (d, which) -> {
                    if (which == 0) rename(number, name);
                    else {
                        prefs.removeRecent(number);
                        buildPeople();
                    }
                })
                .show();
    }

    private void rename(String number, String name) {
        EditText input = new EditText(this);
        input.setText(name);
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("اسم " + LTR_START + number + LTR_END)
                .setView(box)
                .setPositiveButton("حفظ", (d, x) -> {
                    prefs.setName(number, input.getText().toString());
                    buildPeople();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void highlightPerson() {
        String current = phone.getText().toString().replaceAll("[^0-9]", "");
        for (int i = 0; i < people.getChildCount(); i++) {
            LinearLayout col = (LinearLayout) people.getChildAt(i);
            View circle = col.getChildAt(0);
            Object tag = circle.getTag();
            if (tag == null) continue;
            circle.setBackgroundResource(tag.equals(current) ? R.drawable.circle_selected : R.drawable.circle_card);
        }
    }

    private static String firstName(String name) {
        return name.trim().split("\\s+")[0];
    }

    // --- transfer -------------------------------------------------------------

    private void startTransfer() {
        String ph = phone.getText().toString().replaceAll("[^0-9]", "");
        String am = amount.getText().toString().trim();

        if (am.isEmpty() || !am.matches("\\d+(\\.\\d{1,2})?") || Double.parseDouble(am) <= 0) {
            toast("اكتب المبلغ");
            focus(amount);
            return;
        }
        if (ph.length() < 7 || ph.length() > 15) {
            phone.setError("اكتب رقم صحيح");
            focus(phone);
            return;
        }
        if (KeepAlive.state(this) != KeepAlive.State.RUNNING) {
            hideKeyboard();
            showStoppedHelp(true);
            return;
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE}, REQ_PERMS);
            return;
        }
        hideKeyboard();

        if (!vault.hasPin()) {
            toast("احفظ الرقم السري من الإعدادات أول.");
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        String loginPin;
        try {
            loginPin = vault.loadLogin();
        } catch (Exception e) {
            toast("ما قدرت أقرأ الرقم السري. احفظه من جديد من الإعدادات.");
            return;
        }
        // No fingerprint here: it's asked at the end, right before the last code is sent.
        Session.current = Session.build(prefs.steps(), loginPin, ph, am,
                prefs.account(), prefs.finalMode(), prefs.testMode());
        prefs.addRecent(ph);
        UssdService.instance.begin();
        dial(prefs.code());
    }

    // --- contacts ---------------------------------------------------------------

    private static final int REQ_CONTACT = 7;

    private void pickContact() {
        Intent i = new Intent(Intent.ACTION_PICK,
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        try {
            startActivityForResult(i, REQ_CONTACT);
        } catch (Exception e) {
            toast("ما في تطبيق جهات اتصال على الجوال.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CONTACT || res != RESULT_OK || data == null || data.getData() == null) return;
        String[] cols = {
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME};
        try (android.database.Cursor c = getContentResolver().query(data.getData(), cols, null, null, null)) {
            if (c == null || !c.moveToFirst()) return;
            String number = Prefs.normalizePhone(c.getString(0));
            String name = c.getString(1);
            phone.setText(number);
            phone.setSelection(number.length());
            if (name != null && !name.trim().isEmpty() && prefs.name(number) == null) prefs.setName(number, name);
            if (amount.length() == 0) focus(amount);
        } catch (Exception e) {
            toast("ما قدرت أقرأ الرقم من جهات الاتصال.");
        }
    }

    private void dial(String code) {
        Uri uri = Uri.fromParts("tel", code, null);
        TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        Bundle extras = new Bundle();
        int sim = prefs.sim();
        if (sim > 0 && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                List<PhoneAccountHandle> accounts = tm.getCallCapablePhoneAccounts();
                if (accounts.size() >= sim) {
                    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accounts.get(sim - 1));
                }
            } catch (SecurityException ignored) {
            }
        }
        try {
            tm.placeCall(uri, extras);
        } catch (SecurityException e) {
            Session.current = null;
            toast("لازم تسمح للتطبيق بإجراء المكالمات.");
        }
    }

    private boolean recheckPosted;

    /**
     * The service is off, or MIUI killed it and won't restart it.
     * Offers to bring it back, or to do this transfer by hand right now.
     */
    private void showStoppedHelp(boolean fromTransfer) {
        boolean off = KeepAlive.state(this) == KeepAlive.State.OFF;
        boolean noAutostart = KeepAlive.autostart(this) == 0;
        StringBuilder msg = new StringBuilder();
        if (off) {
            msg.append("خدمة التحويل مطفية.\n\nاضغط \"شغّلها\" وفعّل \"تحويل سريع\" من \"التطبيقات التي تم تنزيلها\".");
        } else {
            msg.append("الجوال سكّر الخدمة (بيصير لما تمسح التطبيق من التطبيقات المفتوحة).\n\n");
            if (noAutostart) msg.append("١. شغّل \"التشغيل التلقائي\" للتطبيق، عشان ما تتكرر.\n٢. ");
            msg.append("ادخل \"التطبيقات التي تم تنزيلها\"، اطفي \"تحويل سريع\" وارجع شغّله.");
        }
        if (fromTransfer) msg.append("\n\nأو حوّل هلأ يدوي: بفتحلك كود البنك وبتكمّل الخطوات بإيدك.");

        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(off ? "فعّل خدمة التحويل" : "خدمة التحويل وقفت")
                .setMessage(msg.toString());
        if (!off && noAutostart) {
            d.setPositiveButton("التشغيل التلقائي", (x, w) -> KeepAlive.openAutostart(this));
            d.setNeutralButton("الخدمة", (x, w) -> KeepAlive.openServicePage(this));
        } else {
            d.setPositiveButton("شغّلها", (x, w) -> KeepAlive.openServicePage(this));
        }
        if (fromTransfer) {
            d.setNegativeButton("حوّل يدوي", (x, w) -> dialManual());
        } else {
            d.setNegativeButton("لاحقاً", null);
        }
        d.show();
    }

    /** Opens the dialer with the bank code; the user goes through the steps by hand. */
    private void dialManual() {
        Session.current = null;
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", prefs.code(), null)));
        } catch (Exception e) {
            toast("اطلب " + prefs.code() + " من الهاتف.");
        }
    }

    private void focus(EditText e) {
        e.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
