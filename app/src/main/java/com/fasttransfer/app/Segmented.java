package com.fasttransfer.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A calm iOS-style segmented control. */
public class Segmented extends LinearLayout {

    interface OnChange { void changed(int index); }

    private int selected = 0;
    private OnChange onChange;

    void setOnChange(OnChange l) { onChange = l; }

    public Segmented(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(HORIZONTAL);
        int p = dp(3);
        setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c.getColor(R.color.card2));
        bg.setCornerRadius(dp(13));
        setBackground(bg);
    }

    void setOptions(String... options) {
        removeAllViews();
        for (int i = 0; i < options.length; i++) {
            TextView t = new TextView(getContext());
            t.setText(options[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setSingleLine(true);
            final int idx = i;
            t.setOnClickListener(v -> {
                setSelected(idx);
                if (onChange != null) onChange.changed(idx);
            });
            addView(t, new LayoutParams(0, dp(38), 1));
        }
        setSelected(selected);
    }

    void setSelected(int index) {
        selected = index;
        for (int i = 0; i < getChildCount(); i++) {
            TextView t = (TextView) getChildAt(i);
            boolean on = i == index;
            if (on) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(getContext().getColor(R.color.seg));
                g.setCornerRadius(dp(10));
                t.setBackground(g);
                t.setElevation(dp(1));
            } else {
                t.setBackground(null);
                t.setElevation(0);
            }
            t.setTextColor(getContext().getColor(on ? R.color.text : R.color.text2));
            t.setTypeface(getResources().getFont(on ? R.font.plex_medium : R.font.plex_regular));
        }
    }

    int getSelected() {
        return selected;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
