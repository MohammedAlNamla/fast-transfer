package com.fasttransfer.app;

import java.util.ArrayList;
import java.util.List;

/** One transfer in progress: the replies to send, in order, and where we are. */
final class Session {

    enum Kind {
        /** Send a fixed value. */
        SEND,
        /** Pick the account by number; the bank lists them first. */
        ACCOUNT,
        /** Bank shows the recipient's name: remember it and answer "1". */
        NAME,
        /** Last step: stop, show the summary, fingerprint, then send the final code. */
        FINAL
    }

    static final class Step {
        final Kind kind;
        final String value;

        Step(Kind kind, String value) {
            this.kind = kind;
            this.value = value;
        }
    }

    /** The single active session, or null. Only touched on the main thread. */
    static Session current;

    static final long TIMEOUT_MS = 4 * 60 * 1000;

    final String phone;
    final String amount;
    final String account;
    final boolean finalIsPin;
    final boolean test;
    final List<Step> steps;
    final long startedAt = System.currentTimeMillis();

    int index = 0;
    /** Recipient name read from the bank, if we could find it. */
    String name;
    /** Text of the last USSD dialog we already acted on, to avoid answering it twice. */
    String lastText;
    /** True once the handled dialog has gone (progress dialog or nothing), so an identical text counts as new. */
    boolean sawGap = true;
    /** Waiting for the user (final confirmation, fingerprint, or a manual code). */
    boolean waitingUser = false;
    /** When we started waiting for MIUI to fill in the dialog's message, 0 if not waiting. */
    long messageWaitSince = 0;
    /** Last value typed into the bank dialog, and when. */
    String lastSent;
    long lastReplyAt;
    /** True once the last code has been sent. */
    boolean finalSent;

    private Session(String phone, String amount, String account, boolean finalIsPin, boolean test, List<Step> steps) {
        this.phone = phone;
        this.amount = amount;
        this.account = account;
        this.finalIsPin = finalIsPin;
        this.test = test;
        this.steps = steps;
    }

    static Session build(String template, String loginPin, String phone, String amount,
                         String account, int finalMode, boolean test) {
        List<Step> steps = new ArrayList<>();
        for (String raw : template.split(",")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            switch (t.toUpperCase()) {
                case "PIN": steps.add(new Step(Kind.SEND, loginPin)); break;
                case "PHONE": steps.add(new Step(Kind.SEND, phone)); break;
                case "AMOUNT": steps.add(new Step(Kind.SEND, amount)); break;
                case "ACCOUNT": steps.add(new Step(Kind.ACCOUNT, account)); break;
                case "CONFIRM": steps.add(new Step(Kind.NAME, "1")); break;
                case "FINAL": steps.add(new Step(Kind.FINAL, null)); break;
                default: steps.add(new Step(Kind.SEND, t));
            }
        }
        return new Session(phone, amount, account, finalMode == Prefs.FINAL_PIN, test, steps);
    }

    Step step() {
        return index < steps.size() ? steps.get(index) : null;
    }

    boolean expired() {
        return System.currentTimeMillis() - startedAt > TIMEOUT_MS;
    }
}
