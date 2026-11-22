package com.fasttransfer.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Invisible screen that only shows the fingerprint prompt. Android refuses a fingerprint
 * prompt from a background service, so the service opens this on top of the bank dialog.
 */
public class AuthActivity extends Activity {

    private static PinVault.Result pending;
    private boolean done;

    static void start(Context c, String title, String subtitle, PinVault.Result result) {
        pending = result;
        Intent i = new Intent(c, AuthActivity.class)
                .putExtra("title", title)
                .putExtra("subtitle", subtitle)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        c.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        new PinVault(this).loadFinal(this, getIntent().getStringExtra("title"),
                getIntent().getStringExtra("subtitle"), new PinVault.Result() {
                    @Override public void ok(String pin) { deliver(pin, null); }
                    @Override public void fail(String message) { deliver(null, message); }
                });
    }

    private void deliver(String pin, String error) {
        if (done) return;
        done = true;
        PinVault.Result r = pending;
        pending = null;
        finish();
        overridePendingTransition(0, 0);
        if (r == null) return;
        if (pin != null) r.ok(pin);
        else r.fail(error);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!done) deliver(null, "انلغت العملية.");
    }
}
