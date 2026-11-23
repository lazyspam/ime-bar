package com.lazyexec.imebar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Clipboard / frequent phrases panel over the keys, laid out like HyperOS Phrase:
 *
 *   <   [ Clipboard | Frequent phrases ]   clear / +
 *   [ one-line card ...................... (link) ]
 *
 * Swipe a card left for pin / delete, right to save it as a phrase. Tap to paste.
 */
final class Panel {
    static final int CLIPBOARD = 0;
    static final int PHRASES = 1;

    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private final ImeBar mBar;
    private final Context mCtx;
    private final PopupWindow mPopup;
    private final LinearLayout mRoot;
    private final ImageView mClose, mAction;
    private final LinearLayout mSegment;
    private final TextView mSegClip, mSegPhrase;
    private final GradientDrawable mSegClipBg = new GradientDrawable();
    private final GradientDrawable mSegPhraseBg = new GradientDrawable();
    private final ScrollView mScroll;
    private final LinearLayout mList;
    private int mMode = CLIPBOARD;
    private boolean mClosing;
    private SwipeRow mOpenRow;

    private int cSurface, cCard, cText, cSub, cIcon, cSegBg, cSegOn, cChip;

    Panel(ImeBar bar) {
        mBar = bar;
        mCtx = bar.ctx();

        mRoot = new LinearLayout(mCtx);
        mRoot.setOrientation(LinearLayout.VERTICAL);

        // header
        LinearLayout head = new LinearLayout(mCtx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(6), dp(4), dp(6));
        mClose = plainIcon(Icon.BACK, "Back to keyboard");
        head.addView(mClose);
        head.addView(new View(mCtx), new LinearLayout.LayoutParams(0, 1, 1f));
        mSegment = new LinearLayout(mCtx);
        mSegment.setOrientation(LinearLayout.HORIZONTAL);
        mSegment.setPadding(dp(3), dp(3), dp(3), dp(3));
        mSegClip = segment("Clipboard", CLIPBOARD, mSegClipBg);
        mSegPhrase = segment("Frequent phrases", PHRASES, mSegPhraseBg);
        head.addView(mSegment, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        head.addView(new View(mCtx), new LinearLayout.LayoutParams(0, 1, 1f));
        mAction = plainIcon(Icon.BRUSH, "Clear clipboard history");
        head.addView(mAction);
        mRoot.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // list
        mScroll = new ScrollView(mCtx);
        mScroll.setVerticalScrollBarEnabled(false);
        mScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mScroll.setClipToPadding(false);
        mScroll.setFillViewport(true);
        mScroll.setPadding(dp(16), dp(4), dp(16), dp(8));
        mList = new LinearLayout(mCtx);
        mList.setOrientation(LinearLayout.VERTICAL);
        mScroll.addView(mList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mRoot.addView(mScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mClose.setOnClickListener(v -> ImeBar.safe(() -> {
            ImeBar.tick(v);
            dismiss();
        }));
        mAction.setOnClickListener(v -> ImeBar.safe(() -> {
            ImeBar.tick(v);
            primaryAction();
        }));

        mPopup = new PopupWindow(mRoot, ViewGroup.LayoutParams.MATCH_PARENT, dp(260), false);
        mPopup.setBackgroundDrawable(new ColorDrawable(0));
        mPopup.setOutsideTouchable(false);
        mPopup.setClippingEnabled(false);
        mPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        mPopup.setAnimationStyle(0);
        mPopup.setOnDismissListener(() -> ImeBar.safe(mBar::onPanelDismissed));
    }

    // ---------------------------------------------------------------- public

    boolean isShowing() {
        return mPopup.isShowing() && !mClosing;
    }

    int mode() {
        return mMode;
    }

    void show(int mode, int height) {
        boolean switching = mPopup.isShowing() && !mClosing && mMode != mode;
        int oldMode = mMode;
        mMode = mode;
        restyle();
        if (switching) {
            animateSegments(oldMode);
            switchList(mode > oldMode ? 1 : -1);
            return;
        }
        styleSegmentsNow();
        rebuild();
        mScroll.scrollTo(0, 0);
        int barH = mBar.mFrame.getHeight();
        mClosing = false;
        mRoot.animate().cancel();
        if (mPopup.isShowing()) {
            mPopup.update(0, barH, -1, height);
            mRoot.setAlpha(1f);
            mRoot.setTranslationY(0);
            return;
        }
        mPopup.setHeight(height);
        mRoot.setAlpha(0f);
        mRoot.setTranslationY(dp(28));
        mPopup.showAtLocation(mBar.mFrame, Gravity.BOTTOM | Gravity.START, 0, barH);
        // start once the popup window really draws, or the first frames are lost to its setup
        mRoot.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        mRoot.getViewTreeObserver().removeOnPreDrawListener(this);
                        mRoot.postOnAnimation(() -> mRoot.animate().alpha(1f).translationY(0)
                                .setDuration(260).setInterpolator(EASE).setListener(null).start());
                        return true;
                    }
                });
    }

    /** Animated close. */
    void dismiss() {
        if (!mPopup.isShowing() || mClosing) return;
        mClosing = true;
        mRoot.animate().cancel();
        mRoot.animate().alpha(0f).translationY(dp(20)).setDuration(170).setInterpolator(EASE)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        mRoot.animate().setListener(null);
                        dismissNow();
                    }
                }).start();
    }

    /** Immediate close (keyboard window going away). */
    void dismissNow() {
        mClosing = false;
        mRoot.animate().cancel();
        if (mPopup.isShowing()) mPopup.dismiss();
        mRoot.setAlpha(1f);
        mRoot.setTranslationY(0);
    }

    private void primaryAction() {
        if (mMode == PHRASES) {
            String sel = null;
            InputConnection ic = mBar.mIms.getCurrentInputConnection();
            if (ic != null) {
                CharSequence s = ic.getSelectedText(0);
                if (s != null && s.toString().trim().length() > 0) sel = s.toString();
            }
            openEditor(null, sel);
        } else {
            store().clearUnpinned();
            fadeRebuild();
        }
    }

    void restyle() {
        int bg = mBar.keyboardBg();
        boolean dark = lum(bg) < 0.5f;
        cSurface = bg;
        cCard = dark ? mix(bg, 0xFFFFFFFF, 0.13f) : mix(bg, 0xFFFFFFFF, 0.85f);
        cText = dark ? 0xFFEDEDED : 0xFF1A1A1A;
        cSub = dark ? 0xFF9A9A9F : 0xFF6E6E73;
        cIcon = dark ? 0xFFDADADA : 0xFF3A3A3C;
        cSegBg = dark ? mix(bg, 0xFFFFFFFF, 0.06f) : mix(bg, 0xFF000000, 0.05f);
        cSegOn = dark ? mix(bg, 0xFFFFFFFF, 0.20f) : 0xFFFFFFFF;
        cChip = dark ? mix(bg, 0xFFFFFFFF, 0.22f) : mix(bg, 0xFF000000, 0.07f);
        mRoot.setBackgroundColor(cSurface);
        for (ImageView c : new ImageView[]{mClose, mAction}) {
            ((Icon) c.getDrawable()).setColor(cIcon);
        }
        GradientDrawable seg = new GradientDrawable();
        seg.setCornerRadius(dp(19));
        seg.setColor(cSegBg);
        mSegment.setBackground(seg);
        if (mMode == PHRASES) {
            ((Icon) mAction.getDrawable()).setType(Icon.PLUS);
            mAction.setContentDescription("New phrase");
        } else {
            ((Icon) mAction.getDrawable()).setType(Icon.BRUSH);
            mAction.setContentDescription("Clear clipboard history");
        }
    }

    // ---------------------------------------------------------------- tabs

    private void styleSegmentsNow() {
        mSegClipBg.setColor(mMode == CLIPBOARD ? cSegOn : 0);
        mSegPhraseBg.setColor(mMode == PHRASES ? cSegOn : 0);
        styleSegmentText(mSegClip, mMode == CLIPBOARD);
        styleSegmentText(mSegPhrase, mMode == PHRASES);
    }

    private void animateSegments(int oldMode) {
        final GradientDrawable from = oldMode == CLIPBOARD ? mSegClipBg : mSegPhraseBg;
        final GradientDrawable to = mMode == CLIPBOARD ? mSegClipBg : mSegPhraseBg;
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(200);
        va.setInterpolator(EASE);
        ArgbEvaluator ev = new ArgbEvaluator();
        int clear = cSegOn & 0x00FFFFFF;
        va.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            from.setColor((int) ev.evaluate(f, cSegOn, clear));
            to.setColor((int) ev.evaluate(f, clear, cSegOn));
        });
        va.start();
        styleSegmentText(mSegClip, mMode == CLIPBOARD);
        styleSegmentText(mSegPhrase, mMode == PHRASES);
    }

    /** Slide the list out and the new tab's list in. */
    private void switchList(int dir) {
        mList.animate().cancel();
        mList.animate().alpha(0f).translationX(-dir * dp(28)).setDuration(110)
                .setInterpolator(EASE).setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        mList.animate().setListener(null);
                        ImeBar.safe(Panel.this::rebuild);
                        mScroll.scrollTo(0, 0);
                        mList.setTranslationX(dir * dp(28));
                        mList.animate().alpha(1f).translationX(0).setDuration(200)
                                .setInterpolator(EASE).start();
                    }
                }).start();
    }

    private void fadeRebuild() {
        mList.animate().cancel();
        mList.animate().alpha(0f).setDuration(100).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                mList.animate().setListener(null);
                ImeBar.safe(Panel.this::rebuild);
                mList.animate().alpha(1f).setDuration(160).start();
            }
        }).start();
    }

    // ---------------------------------------------------------------- content

    private Store store() {
        return Store.get(mCtx);
    }

    private void rebuild() {
        mOpenRow = null;
        mList.removeAllViews();
        mList.setAlpha(1f);
        if (mMode == CLIPBOARD) {
            store().captureCurrent();
            List<Store.Clip> clips = store().orderedClips();
            boolean clearable = false;
            for (Store.Clip c : clips) clearable |= !c.pinned;
            mAction.setVisibility(clearable ? View.VISIBLE : View.INVISIBLE);
            if (clips.isEmpty()) {
                empty("Nothing copied yet", "Text you copy shows up here. Tap it to paste.");
                return;
            }
            for (Store.Clip c : clips) clipItem(c);
        } else {
            mAction.setVisibility(View.VISIBLE);
            List<String> list = new ArrayList<>(store().phrases);
            if (list.isEmpty()) {
                empty("No phrases yet", "Tap + to write one. "
                        + "Or swipe a clipboard item right to save it here.");
                return;
            }
            for (String p : list) phraseItem(p);
        }
    }

    private void clipItem(Store.Clip c) {
        View front = cardFront(c.text, c.pinned);
        LinearLayout right = actions(
                action(Icon.PIN, c.pinned ? "Unpin" : "Pin", c.pinned ? cChip : 0xFFF2A93B,
                        c.pinned ? cIcon : 0xFFFFFFFF),
                action(Icon.TRASH, "Delete", 0xFFE5484D, 0xFFFFFFFF));
        LinearLayout left = actions(
                action(Icon.PHRASES, "Save as phrase", 0xFF3B82F6, 0xFFFFFFFF));
        SwipeRow row = new SwipeRow(front, left, right);
        right.getChildAt(0).setOnClickListener(v -> ImeBar.safe(() -> {
            ImeBar.confirm(v);
            store().togglePin(c);
            fadeRebuild();
        }));
        right.getChildAt(1).setOnClickListener(v -> ImeBar.safe(() ->
                removeRow(row, () -> store().removeClip(c))));
        left.getChildAt(0).setOnClickListener(v -> ImeBar.safe(() -> {
            ImeBar.confirm(v);
            boolean added = store().addPhrase(c.text);
            toast(added ? "Saved to phrases" : "Already in phrases");
            row.close();
        }));
        front.setOnClickListener(v -> ImeBar.safe(() -> {
            if (row.isOpen()) row.close();
            else insert(c.text);
        }));
        mList.addView(row, rowLp());
    }

    private void phraseItem(String p) {
        View front = cardFront(p, false);
        LinearLayout right = actions(
                action(Icon.PENCIL, "Edit", 0xFF3B82F6, 0xFFFFFFFF),
                action(Icon.TRASH, "Delete", 0xFFE5484D, 0xFFFFFFFF));
        SwipeRow row = new SwipeRow(front, null, right);
        right.getChildAt(0).setOnClickListener(v -> ImeBar.safe(() -> {
            ImeBar.tick(v);
            openEditor(p, null);
        }));
        right.getChildAt(1).setOnClickListener(v -> ImeBar.safe(() ->
                removeRow(row, () -> store().removePhrase(p))));
        front.setOnClickListener(v -> ImeBar.safe(() -> {
            if (row.isOpen()) row.close();
            else insert(p);
        }));
        mList.addView(row, rowLp());
    }

    /** Slide a row away, collapse its space, then drop it from the store. */
    private void removeRow(SwipeRow row, Runnable remove) {
        ImeBar.confirm(row);
        remove.run();
        final int h = row.getHeight();
        row.animate().alpha(0f).translationX(-row.getWidth() * 0.3f).setDuration(160)
                .setInterpolator(EASE).setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        ValueAnimator va = ValueAnimator.ofInt(h, 0);
                        va.setDuration(180);
                        va.setInterpolator(EASE);
                        va.addUpdateListener(x -> {
                            ViewGroup.LayoutParams lp = row.getLayoutParams();
                            lp.height = (int) x.getAnimatedValue();
                            row.setLayoutParams(lp);
                        });
                        va.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator b) {
                                mList.removeView(row);
                                if (mOpenRow == row) mOpenRow = null;
                                boolean emptyNow = mMode == CLIPBOARD
                                        ? store().orderedClips().isEmpty()
                                        : store().phrases.isEmpty();
                                if (emptyNow || mMode == CLIPBOARD) fadeRebuild();
                            }
                        });
                        va.start();
                    }
                }).start();
    }

    private void insert(String text) {
        ImeBar.tick(mList);
        mBar.commit(text);
        dismiss();
    }

    /** Open the phrase editor sheet (a real text box the keyboard can type into). */
    private void openEditor(String old, String prefill) {
        Intent i = new Intent();
        i.setClassName("com.lazyexec.imebar", "com.lazyexec.imebar.PhraseEditor");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        i.putExtra("ime", mCtx.getPackageName());
        i.putExtra("bg", mBar.keyboardBg());
        if (old != null) i.putExtra("old", old);
        if (prefill != null) i.putExtra("text", prefill);
        dismissNow();
        mCtx.startActivity(i);
    }

    /** + on Phrases: selection, else the text typed in the field, else the clipboard. */
    private void addPhraseFromContext() {
        String text = null;
        String from = null;
        InputConnection ic = mBar.mIms.getCurrentInputConnection();
        if (ic != null) {
            CharSequence sel = ic.getSelectedText(0);
            if (sel != null && sel.toString().trim().length() > 0) {
                text = sel.toString();
                from = "selection";
            }
            if (text == null) {
                ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
                if (et != null && et.text != null && et.text.toString().trim().length() > 0) {
                    text = et.text.toString();
                    from = "text box";
                }
            }
        }
        if (text == null) {
            text = store().currentText();
            from = "clipboard";
        }
        if (text == null) {
            toast("Type, select or copy some text first");
            return;
        }
        boolean added = store().addPhrase(text);
        toast(added ? "Saved phrase from " + from : "Already in phrases");
        if (added) fadeRebuild();
    }

    private void toast(String s) {
        Toast.makeText(mCtx, s, Toast.LENGTH_SHORT).show();
    }

    private static String linkOf(String text) {
        String t = text.trim();
        if (t.indexOf(' ') >= 0 || t.indexOf('\n') >= 0) return null;
        if (!Patterns.WEB_URL.matcher(t).matches()) return null;
        return t.startsWith("http://") || t.startsWith("https://") ? t : "https://" + t;
    }

    // ---------------------------------------------------------------- widgets

    private View cardFront(String text, boolean pinned) {
        LinearLayout card = new LinearLayout(mCtx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(dp(56));
        card.setPadding(dp(18), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(cCard);
        card.setBackground(new RippleDrawable(
                ColorStateList.valueOf((cText & 0xFFFFFF) | 0x1E000000), bg, null));
        TextView t = new TextView(mCtx);
        t.setText(text.replace('\n', ' ').trim());
        t.setTextSize(16);
        t.setTextColor(cText);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (pinned) {
            ImageView pin = new ImageView(mCtx);
            pin.setImageDrawable(new Icon(Icon.PIN, cSub));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(16), dp(16));
            lp.setMarginStart(dp(10));
            card.addView(pin, lp);
        }
        String url = linkOf(text);
        if (url != null) {
            ImageView chip = new ImageView(mCtx);
            chip.setImageDrawable(new Icon(Icon.LINK, cIcon));
            chip.setPadding(dp(9), dp(9), dp(9), dp(9));
            chip.setContentDescription("Open link");
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(cChip);
            chip.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33888888), d, null));
            chip.setOnClickListener(v -> ImeBar.safe(() -> {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mCtx.startActivity(i);
                dismiss();
            }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
            lp.setMarginStart(dp(10));
            card.addView(chip, lp);
        }
        return card;
    }

    private ImageView action(int icon, String desc, int bg, int fg) {
        ImageView b = new ImageView(mCtx);
        b.setImageDrawable(new Icon(icon, fg));
        b.setContentDescription(desc);
        b.setPadding(dp(11), dp(11), dp(11), dp(11));
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(bg);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), d, null));
        return b;
    }

    private LinearLayout actions(ImageView... buttons) {
        LinearLayout l = new LinearLayout(mCtx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        for (ImageView b : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
            lp.setMarginStart(dp(6));
            lp.setMarginEnd(dp(6));
            l.addView(b, lp);
        }
        return l;
    }

    private ImageView plainIcon(int icon, String desc) {
        ImageView b = new ImageView(mCtx);
        b.setImageDrawable(new Icon(icon, 0xFF888888));
        b.setContentDescription(desc);
        b.setPadding(dp(11), dp(11), dp(11), dp(11));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33888888), null, mask));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
        return b;
    }

    private TextView segment(String label, int mode, GradientDrawable bg) {
        TextView t = new TextView(mCtx);
        t.setText(label);
        t.setTextSize(13f);
        t.setMaxLines(2);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setMinWidth(dp(80));
        bg.setCornerRadius(dp(16));
        t.setBackground(bg);
        t.setOnClickListener(v -> ImeBar.safe(() -> {
            if (mMode != mode) {
                ImeBar.tick(v);
                show(mode, mPopup.getHeight());
            }
        }));
        mSegment.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return t;
    }

    private void styleSegmentText(TextView t, boolean on) {
        t.setTextColor(on ? cText : cSub);
        t.setTypeface(Typeface.create(on ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        return lp;
    }

    private void empty(String title, String sub) {
        LinearLayout box = new LinearLayout(mCtx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), 0, dp(24), dp(12));
        TextView a = new TextView(mCtx);
        a.setText(title);
        a.setTextSize(16);
        a.setTextColor(cText);
        a.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        a.setGravity(Gravity.CENTER);
        TextView b = new TextView(mCtx);
        b.setText(sub);
        b.setTextSize(13.5f);
        b.setTextColor(cSub);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(6), 0, 0);
        box.addView(a);
        box.addView(b);
        mList.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    static float lum(int c) {
        return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
    }

    /** Blend a toward b by f (0..1), opaque. */
    static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
        int bl = Math.round((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private int dp(float v) {
        return mBar.dp(v);
    }

    // ---------------------------------------------------------------- swipe row

    /**
     * A card that slides horizontally to uncover icon actions behind it:
     * drag left for the right-hand actions, drag right for the left-hand ones.
     */
    private final class SwipeRow extends FrameLayout {
        private final View mFront;
        private final View mLeft, mRight;
        private final int mSlop;
        private float mDownX, mDownY, mStartTx;
        private boolean mDragging;
        private boolean mPast;

        SwipeRow(View front, View left, View right) {
            super(mCtx);
            mFront = front;
            mLeft = left;
            mRight = right;
            mSlop = ViewConfiguration.get(mCtx).getScaledTouchSlop();
            setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16));
                }
            });
            setClipToOutline(true);
            if (left != null) {
                addView(left, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.START | Gravity.CENTER_VERTICAL));
                left.setAlpha(0f);
            }
            if (right != null) {
                addView(right, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.END | Gravity.CENTER_VERTICAL));
                right.setAlpha(0f);
            }
            addView(front, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private int leftReveal() {
            return mLeft == null ? 0 : mLeft.getWidth() + dp(8);
        }

        private int rightReveal() {
            return mRight == null ? 0 : mRight.getWidth() + dp(8);
        }

        boolean isOpen() {
            return Math.abs(mFront.getTranslationX()) > 1f;
        }

        void close() {
            settle(0);
        }

        private void setTx(float tx) {
            mFront.setTranslationX(tx);
            if (mLeft != null) {
                float f = leftReveal() == 0 ? 0 : Math.max(0f, Math.min(1f, tx / leftReveal()));
                mLeft.setAlpha(f);
                mLeft.setScaleX(0.6f + 0.4f * f);
                mLeft.setScaleY(0.6f + 0.4f * f);
            }
            if (mRight != null) {
                float f = rightReveal() == 0 ? 0 : Math.max(0f, Math.min(1f, -tx / rightReveal()));
                mRight.setAlpha(f);
                mRight.setScaleX(0.6f + 0.4f * f);
                mRight.setScaleY(0.6f + 0.4f * f);
            }
        }

        private void settle(float target) {
            ValueAnimator va = ValueAnimator.ofFloat(mFront.getTranslationX(), target);
            va.setDuration(220);
            va.setInterpolator(new DecelerateInterpolator(1.6f));
            va.addUpdateListener(a -> setTx((float) a.getAnimatedValue()));
            va.start();
            if (target != 0) {
                if (mOpenRow != null && mOpenRow != this) mOpenRow.close();
                mOpenRow = this;
            } else if (mOpenRow == this) {
                mOpenRow = null;
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = ev.getX();
                    mDownY = ev.getY();
                    mStartTx = mFront.getTranslationX();
                    mDragging = false;
                    mPast = false;
                    if (mOpenRow != null && mOpenRow != this) mOpenRow.close();
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - mDownX, dy = ev.getY() - mDownY;
                    if (!mDragging && Math.abs(dx) > mSlop && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                        mDragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!mDragging) return true;
                    float tx = mStartTx + ev.getX() - mDownX;
                    float max = leftReveal(), min = -rightReveal();
                    // rubber band past the reveal width
                    if (tx > max) tx = max + (tx - max) * 0.25f;
                    if (tx < min) tx = min + (tx - min) * 0.25f;
                    setTx(tx);
                    boolean past = (leftReveal() > 0 && tx > leftReveal() * 0.45f)
                            || (rightReveal() > 0 && tx < -rightReveal() * 0.45f);
                    if (past != mPast) {
                        mPast = past;
                        if (past) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float tx = mFront.getTranslationX();
                    if (tx > leftReveal() * 0.45f && leftReveal() > 0) settle(leftReveal());
                    else if (tx < -rightReveal() * 0.45f && rightReveal() > 0) settle(-rightReveal());
                    else settle(0);
                    mDragging = false;
                    return true;
                }
                default:
                    return true;
            }
        }
    }
}
