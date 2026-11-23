package com.lazyexec.imebar;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Bottom sheet with a real text box for adding / editing a phrase. The keyboard can't type
 * into its own panel, so the bar opens this; Save hands the phrase back to the keyboard's
 * process with a broadcast only apps signed like this one may send.
 */
public final class PhraseEditor extends Activity {
    static final String ACTION = "com.lazyexec.imebar.PHRASE";
    static final String PERMISSION = "com.lazyexec.imebar.permission.PHRASES";

    private EditText mEdit;
    private LinearLayout mCard;
    private boolean mClosing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        overridePendingTransition(0, 0);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        Intent in = getIntent();
        final String old = in.getStringExtra("old");
        String text = in.getStringExtra("text");
        final String ime = in.getStringExtra("ime");
        int bg = in.getIntExtra("bg", 0);
        boolean dark;
        if (bg != 0) {
            dark = Panel.lum(bg) < 0.5f;
        } else {
            dark = (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            bg = dark ? 0xFF1F2024 : 0xFFF1F3F4;
        }
        int cText = dark ? 0xFFEDEDED : 0xFF1A1A1A;
        int cSub = dark ? 0xFF9A9A9F : 0xFF6E6E73;
        int cField = dark ? Panel.mix(bg, 0xFFFFFFFF, 0.10f) : 0xFFFFFFFF;
        int cAccent = dark ? 0xFF8AB4F8 : 0xFF1A73E8;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x66000000);
        root.setOnClickListener(v -> close());

        mCard = new LinearLayout(this);
        mCard.setOrientation(LinearLayout.VERTICAL);
        mCard.setPadding(dp(20), dp(18), dp(20), dp(14));
        GradientDrawable cardBg = new GradientDrawable();
        float r = dp(26);
        cardBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        cardBg.setColor(bg);
        mCard.setBackground(cardBg);
        mCard.setClickable(true);

        TextView title = new TextView(this);
        title.setText(old != null ? "Edit phrase" : "New phrase");
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(cText);
        mCard.addView(title);

        mEdit = new EditText(this);
        mEdit.setText(text != null ? text : (old != null ? old : ""));
        mEdit.setSelection(mEdit.getText().length());
        mEdit.setHint("Type a phrase");
        mEdit.setHintTextColor(cSub);
        mEdit.setTextColor(cText);
        mEdit.setTextSize(16);
        mEdit.setMinLines(3);
        mEdit.setMaxLines(7);
        mEdit.setGravity(Gravity.TOP | Gravity.START);
        mEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        mEdit.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mEdit.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable fb = new GradientDrawable();
        fb.setCornerRadius(dp(16));
        fb.setColor(cField);
        mEdit.setBackground(fb);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        el.topMargin = dp(14);
        mCard.addView(mEdit, el);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView cancel = button("Cancel", 0, cAccent);
        final TextView save = button("Save", cAccent, dark ? 0xFF0B1E3A : 0xFFFFFFFF);
        buttons.addView(cancel);
        buttons.addView(save);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(12);
        mCard.addView(buttons, bl);

        cancel.setOnClickListener(v -> close());
        save.setOnClickListener(v -> {
            String t = mEdit.getText().toString().trim();
            if (t.isEmpty()) return;
            Intent b = new Intent(ACTION);
            if (ime != null) b.setPackage(ime);
            b.putExtra("text", t);
            if (old != null) b.putExtra("old", old);
            sendBroadcast(b);
            close();
        });
        Runnable updateSave = () -> {
            boolean ok = mEdit.getText().toString().trim().length() > 0;
            save.setEnabled(ok);
            save.setAlpha(ok ? 1f : 0.45f);
        };
        updateSave.run();
        mEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSave.run();
            }
        });

        root.addView(mCard, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        // slide the sheet up
        mCard.setTranslationY(dp(260));
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(180).start();
        mCard.animate().translationY(0).setDuration(300)
                .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        mEdit.requestFocus();
        mEdit.postDelayed(() -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(mEdit, 0);
        }, 150);
    }

    private TextView button(String label, int bg, int fg) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(22), dp(10), dp(22), dp(10));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(22));
        d.setColor(bg);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(22));
        mask.setColor(0xFFFFFFFF);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33888888), d, mask));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(8));
        t.setLayoutParams(lp);
        return t;
    }

    private void close() {
        if (mClosing) return;
        mClosing = true;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(mEdit.getWindowToken(), 0);
        mCard.animate().translationY(mCard.getHeight()).setDuration(200).start();
        ((View) mCard.getParent()).animate().alpha(0f).setDuration(200)
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, 0);
                }).start();
    }

    @Override
    public void onBackPressed() {
        close();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
