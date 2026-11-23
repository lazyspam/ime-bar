package com.lazyexec.imebar;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.function.DoubleSupplier;

/**
 * Keyboard bar laid over the IME navigation bar (the strip under the keys), MIUI style:
 *
 *   [ left shortcut ]            (handle)            [ right shortcut ]
 *
 * Long-press a shortcut to choose what it does. The clipboard / phrases panel opens over
 * the keys with its own header (see Panel); the strip keeps its two shortcuts meanwhile.
 * The strip is a little taller than the stock one, with the gesture handle at the bottom.
 *
 * Runs inside the keyboard's process (see XposedInit). Public API only.
 */
public final class ImeBar extends FrameLayout {
    static final String TAG = "ImeBar";
    private static final String BAR_TAG = "ime_bar";

    // shortcut actions
    static final int A_NONE = 0, A_SWITCH = 1, A_PANEL = 2, A_HIDE = 3, A_PASTE = 4,
            A_PICKER = 5, A_PHRASES = 6, A_SELECT_ALL = 7, A_VOICE = 8, A_UNDO = 9,
            A_REDO = 10, A_COPY = 11, A_CUT = 12, A_START = 13, A_END = 14, A_CLEAR = 15;
    private static final int[] CHOICES = {A_SWITCH, A_PICKER, A_VOICE, A_PANEL, A_PHRASES,
            A_PASTE, A_COPY, A_CUT, A_UNDO, A_REDO, A_START, A_END, A_CLEAR,
            A_SELECT_ALL, A_HIDE, A_NONE};

    final InputMethodService mIms;
    final ViewGroup mFrame;
    private DoubleSupplier mDarkSource;
    private float mDark = 0f; // 0 = light icons (dark keyboard), 1 = dark icons

    private final SharedPreferences mPrefs;
    private final LinearLayout mRow;
    private final ImageView mLeft, mRight;
    private View mOrigSwitch, mOrigBack;
    private Panel mPanel;
    private PopupWindow mChooser;
    private int mIconColor = 0;

    // taller strip
    private final int mExtra;
    private int mStockH = -1, mOurH = -1;
    private View mContent;
    private int mContentBase = -1, mContentSet = -1;

    // ---------------------------------------------------------------- entry

    /** Called each time the keyboard window is shown. */
    public static void onShown(InputMethodService ims, View frame, DoubleSupplier dark) {
        try {
            if (!(frame instanceof ViewGroup) || isVoiceIme(ims)) return;
            ViewGroup fg = (ViewGroup) frame;
            ImeBar bar = (ImeBar) fg.findViewWithTag(BAR_TAG);
            if (bar == null) {
                bar = new ImeBar(ims, fg);
                bar.setTag(BAR_TAG);
                fg.addView(bar, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            bar.mDarkSource = dark;
            bar.refresh();
            Store.get(ims).captureCurrent(); // record the clipboard (also copies made while hidden)
        } catch (Throwable t) {
            Log.e(TAG, "onShown", t);
        }
    }

    // ---------------------------------------------------------------- view

    private ImeBar(InputMethodService ims, ViewGroup frame) {
        super(ims);
        mIms = ims;
        mFrame = frame;
        mPrefs = ims.getSharedPreferences("ime_bar", Context.MODE_PRIVATE);
        mExtra = dp(14);
        setClickable(true); // blank areas must not reach the hidden stock buttons

        mRow = new LinearLayout(ims);
        mRow.setOrientation(LinearLayout.HORIZONTAL);
        mRow.setGravity(Gravity.CENTER_VERTICAL);
        mRow.setPadding(dp(14), 0, dp(14), 0);
        addView(mRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP));
        mLeft = icon(mRow, dp(56), dp(30));
        mHint = new TextView(ims);
        mHint.setGravity(Gravity.CENTER);
        mHint.setTextSize(12.5f);
        mHint.setLetterSpacing(0.06f);
        mHint.setAlpha(0f);
        mHint.setClickable(false);
        mRow.addView(mHint, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        mRight = icon(mRow, dp(56), dp(30));

        mLeft.setOnClickListener(v -> safe(() -> { tick(v); run(slot("left")); }));
        mRight.setOnClickListener(v -> safe(() -> { tick(v); run(slot("right")); }));
        mLeft.setOnLongClickListener(v -> {
            safe(() -> showChooser("left", mLeft));
            return true;
        });
        mRight.setOnLongClickListener(v -> {
            safe(() -> showChooser("right", mRight));
            return true;
        });

        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                safe(ImeBar.this::syncWithStock);
                return true;
            }
        });
    }

    private ImageView icon(LinearLayout parent, int w, int iconSize) {
        ImageView b = new ImageView(mIms);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int ph = (w - iconSize) / 2;
        b.setPadding(ph, 0, ph, 0);
        b.setFocusable(false);
        b.setHapticFeedbackEnabled(true);
        // no ripple (the keyboard window draws over anything that spills above the strip):
        // the icon itself glows and dips while pressed
        b.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) glow((ImageView) v, true);
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                glow((ImageView) v, false);
            }
            return false;
        });
        parent.addView(b, new LinearLayout.LayoutParams(w, iconSize + dp(14)));
        return b;
    }

    private void glow(ImageView v, boolean on) {
        if (!(v.getDrawable() instanceof Icon)) return;
        final Icon d = (Icon) v.getDrawable();
        final int base = iconColor();
        final int hot = isDark() ? 0xFFFFFFFF : 0xFF000000;
        final int halo = isDark() ? 0x99FFFFFF : 0x55000000;
        v.animate().cancel();
        v.animate().scaleX(on ? 0.88f : 1f).scaleY(on ? 0.88f : 1f)
                .setDuration(on ? 90 : 200).start();
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(
                on ? 0f : 1f, on ? 1f : 0f);
        va.setDuration(on ? 90 : 260);
        android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        va.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            d.setColor((int) ev.evaluate(f, base, hot));
            d.setGlow(3.2f * f, halo);
        });
        va.start();
    }

    /** Light haptic tick. */
    static void tick(View v) {
        if (v != null) v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
    }

    /** Firmer confirm (pin, save, delete). */
    static void confirm(View v) {
        if (v != null) v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
    }

    // ---------------------------------------------------------------- shortcuts

    private int slot(String which) {
        return mPrefs.getInt("slot_" + which, "left".equals(which) ? A_SWITCH : A_PANEL);
    }

    private void setSlot(String which, int action) {
        mPrefs.edit().putInt("slot_" + which, action).apply();
        bindSlots();
    }

    private void bindSlots() {
        bindSlot(mLeft, slot("left"));
        bindSlot(mRight, slot("right"));
    }

    private void bindSlot(ImageView v, int action) {
        if (action == A_SWITCH && !canSwitch()) action = A_PICKER;
        v.setImageDrawable(new Icon(iconFor(action), iconColor()));
        v.setContentDescription(labelFor(action) + " (long-press to change)");
        v.setImageAlpha(action == A_NONE ? 0 : 255); // empty slot stays long-pressable
    }

    private void run(int action) {
        if (mChooser != null) mChooser.dismiss();
        switch (action) {
            case A_SWITCH:
                closePanel();
                if (canSwitch()) {
                    if (mOrigSwitch != null && mOrigSwitch.isEnabled()) mOrigSwitch.performClick();
                    else mIms.switchToNextInputMethod(false);
                } else {
                    showPicker();
                }
                break;
            case A_VOICE:
                closePanel();
                voiceInput();
                break;
            case A_PICKER:
                closePanel();
                showPicker();
                break;
            case A_PANEL:
                togglePanel(Panel.CLIPBOARD);
                break;
            case A_PHRASES:
                togglePanel(Panel.PHRASES);
                break;
            case A_HIDE:
                closePanel();
                mIms.requestHideSelf(0);
                break;
            case A_PASTE: {
                String s = Store.get(mIms).currentText();
                if (s != null) commit(s);
                break;
            }
            case A_SELECT_ALL: {
                InputConnection ic = mIms.getCurrentInputConnection();
                if (ic != null) ic.performContextMenuAction(android.R.id.selectAll);
                break;
            }
            case A_UNDO:
                key(android.view.KeyEvent.KEYCODE_Z, android.view.KeyEvent.META_CTRL_ON);
                break;
            case A_REDO:
                key(android.view.KeyEvent.KEYCODE_Z,
                        android.view.KeyEvent.META_CTRL_ON | android.view.KeyEvent.META_SHIFT_ON);
                break;
            case A_COPY:
                copyOrCut(false);
                break;
            case A_CUT:
                copyOrCut(true);
                break;
            case A_START: {
                InputConnection ic = mIms.getCurrentInputConnection();
                if (ic != null) ic.setSelection(0, 0);
                break;
            }
            case A_END: {
                InputConnection ic = mIms.getCurrentInputConnection();
                int len = fieldLength();
                if (ic != null && len >= 0) ic.setSelection(len, len);
                break;
            }
            case A_CLEAR: {
                InputConnection ic = mIms.getCurrentInputConnection();
                int len = fieldLength();
                if (ic != null && len > 0) {
                    ic.beginBatchEdit();
                    ic.setSelection(0, len);
                    ic.commitText("", 1);
                    ic.endBatchEdit();
                }
                break;
            }
            default:
                break;
        }
    }

    /** Send a key chord (e.g. Ctrl+Z) to the focused field. */
    private void key(int code, int meta) {
        InputConnection ic = mIms.getCurrentInputConnection();
        if (ic == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        ic.sendKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN,
                code, 0, meta));
        ic.sendKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP,
                code, 0, meta));
    }

    private int fieldLength() {
        InputConnection ic = mIms.getCurrentInputConnection();
        if (ic == null) return -1;
        android.view.inputmethod.ExtractedText et =
                ic.getExtractedText(new android.view.inputmethod.ExtractedTextRequest(), 0);
        if (et != null && et.text != null) return et.text.length() + Math.max(0, et.startOffset);
        CharSequence before = ic.getTextBeforeCursor(100000, 0);
        CharSequence after = ic.getTextAfterCursor(100000, 0);
        return (before == null ? 0 : before.length()) + (after == null ? 0 : after.length());
    }

    /** Copy / cut the selection; with nothing selected, copy the whole field (cut clears it). */
    private void copyOrCut(boolean cut) {
        InputConnection ic = mIms.getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) {
            ic.performContextMenuAction(cut ? android.R.id.cut : android.R.id.copy);
            return;
        }
        android.view.inputmethod.ExtractedText et =
                ic.getExtractedText(new android.view.inputmethod.ExtractedTextRequest(), 0);
        if (et == null || et.text == null || et.text.length() == 0) return;
        android.content.ClipboardManager cm =
                mIms.getSystemService(android.content.ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("text", et.text));
        if (cut) run(A_CLEAR);
        android.widget.Toast.makeText(mIms, cut ? "Cut all text" : "Copied all text",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Voice-typing keyboards keep their own UI: no bar there. */
    private static boolean isVoiceIme(InputMethodService ims) {
        try {
            if ("com.google.android.tts".equals(ims.getPackageName())) return true;
            InputMethodManager imm = ims.getSystemService(InputMethodManager.class);
            android.view.inputmethod.InputMethodSubtype st =
                    imm != null ? imm.getCurrentInputMethodSubtype() : null;
            return st != null && "voice".equals(st.getMode());
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean canSwitch() {
        return mOrigSwitch == null || mOrigSwitch.getVisibility() == VISIBLE;
    }

    /** Switch to a voice-typing keyboard (any enabled IME subtype in "voice" mode). */
    private void voiceInput() {
        InputMethodManager imm = mIms.getSystemService(InputMethodManager.class);
        if (imm == null) return;
        for (android.view.inputmethod.InputMethodInfo info : imm.getEnabledInputMethodList()) {
            for (android.view.inputmethod.InputMethodSubtype st
                    : imm.getEnabledInputMethodSubtypeList(info, true)) {
                if ("voice".equals(st.getMode())) {
                    mIms.switchInputMethod(info.getId(), st);
                    return;
                }
            }
        }
        android.widget.Toast.makeText(mIms, "No voice typing keyboard enabled",
                android.widget.Toast.LENGTH_SHORT).show();
        showPicker();
    }

    private void showPicker() {
        InputMethodManager imm = mIms.getSystemService(InputMethodManager.class);
        if (imm != null) imm.showInputMethodPicker();
    }

    static int iconFor(int a) {
        switch (a) {
            case A_SWITCH: return Icon.KEYBOARD;
            case A_PICKER: return Icon.GLOBE;
            case A_PANEL: return Icon.CARDS;
            case A_PHRASES: return Icon.PHRASES;
            case A_HIDE: return Icon.COLLAPSE;
            case A_PASTE: return Icon.CLIPBOARD;
            case A_SELECT_ALL: return Icon.SELECT_ALL;
            case A_VOICE: return Icon.MIC;
            case A_UNDO: return Icon.UNDO;
            case A_REDO: return Icon.REDO;
            case A_COPY: return Icon.COPY;
            case A_CUT: return Icon.CUT;
            case A_START: return Icon.TO_START;
            case A_END: return Icon.TO_END;
            case A_CLEAR: return Icon.CLEAR_TEXT;
            default: return Icon.CLOSE;
        }
    }

    static String labelFor(int a) {
        switch (a) {
            case A_SWITCH: return "Switch keyboard";
            case A_PICKER: return "Choose keyboard";
            case A_PANEL: return "Clipboard & phrases";
            case A_PHRASES: return "Frequent phrases";
            case A_HIDE: return "Hide keyboard";
            case A_PASTE: return "Paste";
            case A_SELECT_ALL: return "Select all";
            case A_VOICE: return "Voice input";
            case A_UNDO: return "Undo";
            case A_REDO: return "Redo";
            case A_COPY: return "Copy";
            case A_CUT: return "Cut";
            case A_START: return "Jump to start";
            case A_END: return "Jump to end";
            case A_CLEAR: return "Clear text";
            default: return "Nothing";
        }
    }

    /** Long-press menu: a grid of every shortcut action; pick what this slot does. */
    private void showChooser(String which, View anchor) {
        if (mChooser != null && mChooser.isShowing()) mChooser.dismiss();
        int kb = keyboardBg();
        boolean dark = Panel.lum(kb) < 0.5f;
        int cBg = dark ? Panel.mix(kb, 0xFFFFFFFF, 0.10f) : Panel.mix(kb, 0xFFFFFFFF, 0.90f);
        int cText = dark ? 0xFFE6E6E8 : 0xFF1A1A1A;
        int cSub = dark ? 0xFF9A9A9F : 0xFF6E6E73;
        int cOn = accent();
        int current = slot(which);
        final int width = Math.max(dp(280), mFrame.getWidth() - dp(24));

        final LinearLayout box = new LinearLayout(mIms);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(12), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(cBg);
        box.setBackground(bg);

        TextView title = new TextView(mIms);
        title.setText(("left".equals(which) ? "Left" : "Right") + " shortcut");
        title.setTextSize(13);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(cSub);
        title.setPadding(dp(10), 0, dp(10), dp(8));
        box.addView(title);

        final PopupWindow pw = new PopupWindow(box, width,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        final int cols = 4;
        LinearLayout row = null;
        for (int i = 0; i < CHOICES.length; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(mIms);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(74)));
            }
            final int a = CHOICES[i];
            boolean on = a == current;
            LinearLayout tile = new LinearLayout(mIms);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(4), dp(6), dp(4), dp(4));
            GradientDrawable tb = new GradientDrawable();
            tb.setCornerRadius(dp(16));
            tb.setColor(on ? ((cOn & 0x00FFFFFF) | 0x2E000000) : 0);
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(dp(16));
            mask.setColor(0xFFFFFFFF);
            tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22888888), tb, mask));
            ImageView ic = new ImageView(mIms);
            ic.setImageDrawable(new Icon(iconFor(a), on ? cOn : cText));
            tile.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView t = new TextView(mIms);
            t.setText(labelFor(a));
            t.setTextSize(11.5f);
            t.setMaxLines(2);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(on ? cOn : cSub);
            t.setPadding(0, dp(5), 0, 0);
            tile.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.setOnClickListener(v -> safe(() -> {
                tick(v);
                setSlot(which, a);
                hideChooser(pw, box);
            }));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            tl.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(tile, tl);
        }
        // pad the last row so tiles keep their width
        while (row != null && row.getChildCount() < cols) {
            row.addView(new View(mIms), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        pw.setBackgroundDrawable(new ColorDrawable(0));
        pw.setOutsideTouchable(true);
        pw.setClippingEnabled(false);
        pw.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        mChooser = pw;

        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        box.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.UNSPECIFIED);
        int x = (getWidth() - width) / 2;
        int y = loc[1] - box.getMeasuredHeight() - dp(6);
        pw.showAtLocation(mFrame, Gravity.TOP | Gravity.START, x, Math.max(dp(8), y));
        // grow out of the shortcut it belongs to
        box.setPivotX("left".equals(which) ? dp(36) : width - dp(36));
        box.setPivotY(box.getMeasuredHeight());
        box.setAlpha(0f);
        box.setScaleX(0.9f);
        box.setScaleY(0.9f);
        box.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                box.getViewTreeObserver().removeOnPreDrawListener(this);
                box.postOnAnimation(() -> box.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(220).setInterpolator(new android.view.animation.PathInterpolator(
                                0.2f, 0f, 0f, 1f)).start());
                return true;
            }
        });
        pw.setTouchInterceptor((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                hideChooser(pw, box);
                return true;
            }
            return false;
        });
    }

    private void hideChooser(PopupWindow pw, View box) {
        if (!pw.isShowing()) return;
        box.animate().cancel();
        box.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(140)
                .withEndAction(() -> safe(pw::dismiss)).start();
    }

    // ---------------------------------------------------------------- panel

    private void togglePanel(int mode) {
        if (mPanel != null && mPanel.isShowing() && mPanel.mode() == mode) {
            mPanel.dismiss();
            return;
        }
        if (mPanel == null) mPanel = new Panel(this);
        mPanel.show(mode, keyboardHeight());
    }

    void closePanel() {
        if (mPanel != null) mPanel.dismiss();
    }

    void closePanelNow() {
        if (mPanel != null) mPanel.dismissNow();
    }

    void onPanelDismissed() {
    }

    /**
     * The keyboard's own background colour: sampled from the top-left corner of its content
     * (Gboard's toolbar row), else the IME window's navigation bar colour.
     */
    private int mKbColor = 0;

    /**
     * Read the keyboard's real background from its window surface (Gboard draws it on the
     * GPU, so a software draw can't see it): a pixel in the left margin, mid-keyboard.
     */
    private void sampleKeyboardColor() {
        try {
            android.view.Window w = mIms.getWindow().getWindow();
            if (w == null) return;
            int[] loc = new int[2];
            mFrame.getLocationInWindow(loc);
            InputMethodService.Insets in = new InputMethodService.Insets();
            mIms.onComputeInsets(in);
            int top = Math.max(0, in.contentTopInsets);
            if (loc[1] <= top) return;
            int x = dp(1), y = top + (loc[1] - top) / 2;
            final android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(1, 1,
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(w, new android.graphics.Rect(x, y, x + 1, y + 1), bm,
                    result -> safe(() -> {
                        if (result != android.view.PixelCopy.SUCCESS) return;
                        int px = bm.getPixel(0, 0);
                        bm.recycle();
                        if ((px >>> 24) < 0xC8) return;
                        int c = px | 0xFF000000;
                        if (c == mKbColor) return;
                        mKbColor = c;
                        paintStrip();
                        if (mPanel != null && mPanel.isShowing()) mPanel.restyle();
                    }), new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Throwable t) {
            Log.w(TAG, "pixel copy", t);
        }
    }

    int keyboardBg() {
        if (mKbColor != 0) return mKbColor;
        try {
            View root = mFrame.getRootView();
            InputMethodService.Insets in = new InputMethodService.Insets();
            mIms.onComputeInsets(in);
            int x = dp(3), y = Math.max(0, in.contentTopInsets) + dp(3);
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(1, 1,
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bm);
            c.translate(-x, -y);
            root.draw(c);
            int px = bm.getPixel(0, 0);
            bm.recycle();
            if ((px >>> 24) > 0xC8) return px | 0xFF000000;
        } catch (Throwable t) {
            Log.w(TAG, "sample bg", t);
        }
        try {
            int nc = mIms.getWindow().getWindow().getNavigationBarColor();
            if ((nc >>> 24) > 0xC8) return nc | 0xFF000000;
        } catch (Throwable ignored) {
        }
        return isDark() ? 0xFF202124 : 0xFFF1F3F4;
    }

    void commit(CharSequence text) {
        try {
            InputConnection ic = mIms.getCurrentInputConnection();
            if (ic != null) ic.commitText(text, 1);
        } catch (Throwable t) {
            Log.e(TAG, "commit", t);
        }
    }

    /** Height of the keys above the strip: the panel covers exactly that. */
    int keyboardHeight() {
        int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f);
        int min = dp(150);
        try {
            int[] loc = new int[2];
            mFrame.getLocationInWindow(loc);
            InputMethodService.Insets in = new InputMethodService.Insets();
            mIms.onComputeInsets(in);
            int top = Math.max(in.contentTopInsets, in.visibleTopInsets);
            int h = loc[1] - top;
            if (h >= min && h <= max) return h;
            Log.w(TAG, "insets height " + h + " (top " + top + ", bar " + loc[1] + ")");
        } catch (Throwable t) {
            Log.w(TAG, "insets", t);
        }
        try { // fallback: the keyboard's own input view
            int id = Resources.getSystem().getIdentifier("inputArea", "id", "android");
            View area = id != 0 ? mFrame.getRootView().findViewById(id) : null;
            if (area instanceof ViewGroup && ((ViewGroup) area).getChildCount() > 0) {
                int h = ((ViewGroup) area).getChildAt(0).getHeight();
                if (h >= min && h <= max) return h;
            }
        } catch (Throwable ignored) {
        }
        return dp(270);
    }

    // ---------------------------------------------------------------- taller strip

    /**
     * Grow the strip by mExtra: the frame gets taller, the keyboard content is padded up by
     * the same amount, and the stock bar (gesture handle) stays at its height at the bottom.
     * Re-applied before every draw, since the framework resets the frame on insets changes.
     */
    private void applyHeight() {
        ViewGroup.LayoutParams lp = mFrame.getLayoutParams();
        if (lp == null) return;
        if (lp.height != mOurH && lp.height > 0) mStockH = lp.height; // framework (re)set it
        if (mStockH <= 0) return;
        mOurH = mStockH + mExtra;
        if (lp.height != mOurH) {
            lp.height = mOurH;
            mFrame.setLayoutParams(lp);
        }
        for (int i = 0; i < mFrame.getChildCount(); i++) {
            View c = mFrame.getChildAt(i);
            if (c == this) continue;
            ViewGroup.LayoutParams clp = c.getLayoutParams();
            if (clp instanceof FrameLayout.LayoutParams
                    && (clp.height != mStockH
                    || ((FrameLayout.LayoutParams) clp).gravity != Gravity.BOTTOM)) {
                clp.height = mStockH;
                ((FrameLayout.LayoutParams) clp).gravity = Gravity.BOTTOM;
                c.setLayoutParams(clp);
            }
        }
        // icons sit above the handle zone
        int rowH = mOurH - Math.round(mStockH * 0.45f);
        ViewGroup.LayoutParams rlp = mRow.getLayoutParams();
        if (rlp.height != rowH) {
            rlp.height = rowH;
            mRow.setLayoutParams(rlp);
        }
        if (mContent == null) mContent = mFrame.getRootView().findViewById(android.R.id.content);
        if (mContent != null) {
            int pb = mContent.getPaddingBottom();
            if (pb != mContentSet) mContentBase = pb; // someone else changed it
            int want = mContentBase + mExtra;
            if (pb != want) {
                mContent.setPadding(mContent.getPaddingLeft(), mContent.getPaddingTop(),
                        mContent.getPaddingRight(), want);
            }
            mContentSet = want;
        }
    }

    // ---------------------------------------------------------------- styling

    void refresh() {
        postDelayed(() -> safe(this::sampleKeyboardColor), 180);
        safe(this::paintStrip);
        findStockButtons();
        readDark();
        mIconColor = 0;
        applyColors();
        syncWithStock();
    }

    private void findStockButtons() {
        if (mOrigBack == null) mOrigBack = stockView("input_method_nav_back", "back");
        if (mOrigSwitch == null) {
            mOrigSwitch = stockView("input_method_nav_ime_switcher", "ime_switcher");
        }
    }

    /** A stock nav-bar view by framework id name (IME nav bar names first). */
    private View stockView(String... names) {
        Resources sys = Resources.getSystem();
        for (String n : names) {
            int id = sys.getIdentifier(n, "id", "android");
            View v = id != 0 ? mFrame.findViewById(id) : null;
            if (v != null && v != this) return v;
        }
        return null;
    }

    private void syncWithStock() {
        if (mOrigBack != null && mOrigBack.getAlpha() != 0f) mOrigBack.setAlpha(0f);
        if (mOrigSwitch != null && mOrigSwitch.getAlpha() != 0f) mOrigSwitch.setAlpha(0f);
        applyHeight();
        float before = mDark;
        readDark();
        if (Math.abs(before - mDark) > 0.01f) applyColors();
    }

    private void readDark() {
        double d = mDarkSource != null ? mDarkSource.getAsDouble() : -1;
        if (d >= 0) {
            mDark = (float) d;
        } else {
            int night = mIms.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            mDark = night == Configuration.UI_MODE_NIGHT_YES ? 0f : 1f;
        }
    }

    boolean isDark() {
        return mDark < 0.5f;
    }

    /** Soft grey like MIUI: #C4C7C5 on dark keyboards, #5F6368 on light ones. */
    int iconColor() {
        int r = Math.round(0xC4 + (0x5F - 0xC4) * mDark);
        int g = Math.round(0xC7 + (0x63 - 0xC7) * mDark);
        int b = Math.round(0xC5 + (0x68 - 0xC5) * mDark);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    int accent() {
        return isDark() ? 0xFF8AB4F8 : 0xFF1A73E8;
    }

    private void applyColors() {
        int color = iconColor();
        if (color == mIconColor) return;
        mIconColor = color;
        bindSlots();
        if (mPanel != null) mPanel.restyle();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            safe(this::closePanelNow);
            if (mChooser != null) safe(mChooser::dismiss);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        safe(this::closePanelNow);
        if (mChooser != null) safe(mChooser::dismiss);
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- cursor slide

    private TextView mHint;
    private float mLastX;
    private float mAccum;
    private boolean mSliding, mSelecting;
    private final Runnable mStartSelect = () -> {
        mSelecting = true;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        showHint("‹  select  ›");
    };

    /** The blank strip between the shortcuts: slide to move the cursor, hold then slide to select. */
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int step = dp(9);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLastX = ev.getX();
                mAccum = 0;
                mAnchor = -1;
                mHead = -1;
                mSliding = false;
                mSelecting = false;
                postDelayed(mStartSelect, 380);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - mLastX;
                mLastX = ev.getX();
                mAccum += dx;
                if (!mSliding && Math.abs(mAccum) > dp(6)) {
                    mSliding = true;
                    removeCallbacks(mStartSelect);
                    if (!mSelecting) showHint("‹  cursor  ›");
                }
                if (!mSliding) return true;
                int moved = 0;
                while (Math.abs(mAccum) >= step) {
                    int dir = mAccum > 0 ? 1 : -1;
                    mAccum -= dir * step;
                    moved += dir;
                }
                if (moved != 0) {
                    moveCursor(moved);
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(mStartSelect);
                mSliding = false;
                mSelecting = false;
                hideHint();
                return true;
            default:
                return true;
        }
    }

    private int mAnchor = -1, mHead = -1;

    /** Move the cursor (or the selection's moving end) by n characters. */
    private void moveCursor(int n) {
        InputConnection ic = mIms.getCurrentInputConnection();
        if (ic == null) return;
        android.view.inputmethod.ExtractedText et =
                ic.getExtractedText(new android.view.inputmethod.ExtractedTextRequest(), 0);
        if (et == null || et.text == null || et.selectionStart < 0) {
            int code = n > 0 ? android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    : android.view.KeyEvent.KEYCODE_DPAD_LEFT;
            for (int i = 0; i < Math.abs(n); i++) {
                key(code, mSelecting ? android.view.KeyEvent.META_SHIFT_ON : 0);
            }
            return;
        }
        int off = Math.max(0, et.startOffset);
        int len = off + et.text.length();
        int s = off + Math.min(et.selectionStart, et.selectionEnd);
        int e = off + Math.max(et.selectionStart, et.selectionEnd);
        if (mSelecting) {
            if (mAnchor < 0) {
                mAnchor = n > 0 ? s : e;
                mHead = n > 0 ? e : s;
            }
            mHead = Math.max(0, Math.min(len, mHead + n));
            ic.setSelection(Math.min(mAnchor, mHead), Math.max(mAnchor, mHead));
        } else {
            int p = Math.max(0, Math.min(len, (n > 0 ? e : s) + n));
            ic.setSelection(p, p);
        }
    }

    private void showHint(String text) {
        mHint.setText(text);
        mHint.setTextColor(iconColor());
        mHint.animate().cancel();
        mHint.animate().alpha(0.85f).setDuration(120).start();
    }

    private void hideHint() {
        mHint.animate().cancel();
        mHint.animate().alpha(0f).setDuration(220).start();
    }

    /** One colour for the whole strip (the stock handle zone is a darker shade otherwise). */
    private void paintStrip() {
        int bg = keyboardBg();
        mFrame.setBackgroundColor(bg);
        for (int i = 0; i < mFrame.getChildCount(); i++) {
            View c = mFrame.getChildAt(i);
            if (c != this) c.setBackgroundColor(0);
        }
    }


    // ---------------------------------------------------------------- utils

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getContext().getResources().getDisplayMetrics()));
    }

    static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            Log.e(TAG, "action failed", t);
        }
    }

    Context ctx() {
        return mIms;
    }
}
