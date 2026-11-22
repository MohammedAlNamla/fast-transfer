package com.fasttransfer.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keeps the PIN encrypted in the Android Keystore, in two copies:
 *  - "login": opens without a fingerprint, used for the bank's first PIN prompt.
 *  - "final": opens only after a fingerprint, used for the last code that sends the money.
 * So nothing can move money without the owner's fingerprint at the very end.
 */
final class PinVault {

    interface Result {
        void ok(String pin);
        void fail(String message);
    }

    interface Done {
        void ok();
        void fail(String message);
    }

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS_BIO = "pin_key_bio";
    private static final String ALIAS_PLAIN = "pin_key_plain";
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private final SharedPreferences sp;

    PinVault(Context c) {
        sp = c.getSharedPreferences("vault2", Context.MODE_PRIVATE);
        // Drop the single-copy format from the first version.
        c.getSharedPreferences("vault", Context.MODE_PRIVATE).edit().clear().apply();
    }

    boolean hasPin() {
        return sp.contains("login_ct") && (!usesBio() || sp.contains("final_ct"));
    }

    boolean usesBio() {
        return sp.getBoolean("bio", false);
    }

    void clear() {
        sp.edit().clear().apply();
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            ks.deleteEntry(ALIAS_BIO);
            ks.deleteEntry(ALIAS_PLAIN);
        } catch (Exception ignored) {
        }
    }

    /** Saves both copies; asks for a fingerprint once when bio is on. */
    void save(Activity a, String pin, boolean bio, Done done) {
        try {
            Cipher plain = Cipher.getInstance(TRANSFORM);
            plain.init(Cipher.ENCRYPT_MODE, getOrCreateKey(false));
            store("login", plain, pin);
            sp.edit().putBoolean("bio", bio).apply();
            if (!bio) {
                done.ok();
                return;
            }
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, getOrCreateKey(true));
            prompt(a, c, "تأكيد حفظ الرقم السري", null, new CipherCallback() {
                @Override public void ready(Cipher ready) {
                    try {
                        store("final", ready, pin);
                        done.ok();
                    } catch (Exception e) {
                        clear();
                        done.fail("ما زبط الحفظ. جرّب مرة ثانية.");
                    }
                }

                @Override public void failed(String msg) {
                    clear();
                    done.fail(msg);
                }
            });
        } catch (KeyPermanentlyInvalidatedException e) {
            clear();
            done.fail("تغيّرت البصمات على الجوال. جرّب الحفظ مرة ثانية.");
        } catch (Exception e) {
            clear();
            done.fail("ما زبط الحفظ: " + e.getMessage());
        }
    }

    /** The PIN for the bank's first prompt. No fingerprint needed. */
    String loadLogin() throws Exception {
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.DECRYPT_MODE, getOrCreateKey(false), new GCMParameterSpec(128, bytes("login_iv")));
        return new String(c.doFinal(bytes("login_ct")), StandardCharsets.UTF_8);
    }

    /** The PIN for the last step, after a fingerprint. Works from the accessibility service too. */
    void loadFinal(Context ctx, String title, String subtitle, Result result) {
        if (!usesBio()) {
            try {
                result.ok(loadLogin());
            } catch (Exception e) {
                result.fail("ما قدرت أقرأ الرقم السري. احفظه من جديد من الإعدادات.");
            }
            return;
        }
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, getOrCreateKey(true), new GCMParameterSpec(128, bytes("final_iv")));
            byte[] ct = bytes("final_ct");
            prompt(ctx, c, title, subtitle, new CipherCallback() {
                @Override public void ready(Cipher ready) {
                    try {
                        result.ok(new String(ready.doFinal(ct), StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        result.fail("ما قدرت أقرأ الرقم السري. احفظه من جديد من الإعدادات.");
                    }
                }

                @Override public void failed(String msg) {
                    result.fail(msg);
                }
            });
        } catch (KeyPermanentlyInvalidatedException e) {
            clear();
            result.fail("تغيّرت البصمات على الجوال، فانمسح الرقم السري للأمان. احفظه من جديد.");
        } catch (Exception e) {
            result.fail("ما قدرت أقرأ الرقم السري. احفظه من جديد من الإعدادات.");
        }
    }

    // ---------------------------------------------------------------------

    private interface CipherCallback {
        void ready(Cipher c);
        void failed(String msg);
    }

    private void prompt(Context ctx, Cipher cipher, String title, String subtitle, CipherCallback cb) {
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(ctx)
                .setTitle(title)
                .setNegativeButton("إلغاء", ctx.getMainExecutor(), (d, w) -> cb.failed("انلغت العملية."));
        b.setSubtitle(subtitle != null ? subtitle : "حط إصبعك على البصمة");
        b.build().authenticate(new BiometricPrompt.CryptoObject(cipher), new CancellationSignal(),
                ctx.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        cb.ready(r.getCryptoObject().getCipher());
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        if (code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                            cb.failed("انلغت العملية.");
                        } else {
                            cb.failed(String.valueOf(msg));
                        }
                    }
                });
    }

    private void store(String prefix, Cipher c, String pin) throws Exception {
        byte[] ct = c.doFinal(pin.getBytes(StandardCharsets.UTF_8));
        sp.edit()
                .putString(prefix + "_ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                .putString(prefix + "_iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                .apply();
    }

    private byte[] bytes(String key) {
        return Base64.decode(sp.getString(key, ""), Base64.NO_WRAP);
    }

    private SecretKey getOrCreateKey(boolean bio) throws Exception {
        String alias = bio ? ALIAS_BIO : ALIAS_PLAIN;
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(alias)) {
            return (SecretKey) ks.getKey(alias, null);
        }
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        if (bio) {
            b.setUserAuthenticationRequired(true);
            b.setInvalidatedByBiometricEnrollment(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            }
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(b.build());
        return kg.generateKey();
    }
}
