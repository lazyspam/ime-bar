package com.lazyexec.imebar;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small line icons drawn on a 24x24 grid, so the bar needs no resources. */
final class Icon extends Drawable {
    static final int COLLAPSE = 0, CLIPBOARD = 1, PHRASES = 2, GLOBE = 3, KEYBOARD = 8, CARDS = 9, SELECT_ALL = 10, CHECK = 11, LINK = 12, BACK = 13, BRUSH = 14, MIC = 15,
            UNDO = 16, REDO = 17, COPY = 18, CUT = 19, TO_START = 20, TO_END = 21, CLEAR_TEXT = 22, PENCIL = 23,
            PIN = 4, CLOSE = 5, PLUS = 6, TRASH = 7;

    private int mType;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mTmp = new RectF();

    Icon(int type, int color) {
        mType = type;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setColor(color);
    }

    void setType(int type) {
        if (mType != type) {
            mType = type;
            invalidateSelf();
        }
    }

    /** Soft glow around the strokes (0 = none). */
    void setGlow(float radius, int color) {
        if (radius <= 0f) mPaint.clearShadowLayer();
        else mPaint.setShadowLayer(radius, 0f, 0f, color);
        invalidateSelf();
    }

    void setColor(int color) {
        if (mPaint.getColor() != color) {
            mPaint.setColor(color);
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 24f;
        canvas.save();
        canvas.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
        canvas.scale(s, s);
        mPaint.setStrokeWidth(1.8f);
        mPaint.setStyle(Paint.Style.STROKE);
        mPath.reset();
        switch (mType) {
            case COLLAPSE:
                mPath.moveTo(6, 9.5f);
                mPath.lineTo(12, 15.5f);
                mPath.lineTo(18, 9.5f);
                canvas.drawPath(mPath, mPaint);
                break;
            case CLIPBOARD:
                mTmp.set(5.5f, 4.5f, 18.5f, 21f);
                canvas.drawRoundRect(mTmp, 3f, 3f, mPaint);
                mTmp.set(9f, 2.5f, 15f, 6.5f);
                canvas.drawRoundRect(mTmp, 1.5f, 1.5f, mPaint);
                canvas.drawLine(9f, 11.5f, 15f, 11.5f, mPaint);
                canvas.drawLine(9f, 15.5f, 13f, 15.5f, mPaint);
                break;
            case PHRASES:
                mPath.moveTo(7f, 4.5f);
                mPath.lineTo(17f, 4.5f);
                mPath.quadTo(20.5f, 4.5f, 20.5f, 8f);
                mPath.lineTo(20.5f, 13f);
                mPath.quadTo(20.5f, 16.5f, 17f, 16.5f);
                mPath.lineTo(11f, 16.5f);
                mPath.lineTo(7f, 20f);
                mPath.lineTo(7f, 16.5f);
                mPath.quadTo(3.5f, 16.5f, 3.5f, 13f);
                mPath.lineTo(3.5f, 8f);
                mPath.quadTo(3.5f, 4.5f, 7f, 4.5f);
                mPath.close();
                canvas.drawPath(mPath, mPaint);
                canvas.drawLine(8f, 9f, 16f, 9f, mPaint);
                canvas.drawLine(8f, 12.5f, 13f, 12.5f, mPaint);
                break;
            case GLOBE:
                canvas.drawCircle(12f, 12f, 8.5f, mPaint);
                mTmp.set(8.3f, 3.5f, 15.7f, 20.5f);
                canvas.drawOval(mTmp, mPaint);
                canvas.drawLine(3.5f, 12f, 20.5f, 12f, mPaint);
                break;
            case PIN:
                mPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12f, 9f, 4.5f, mPaint);
                mPaint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(12f, 13f, 12f, 20f, mPaint);
                break;
            case CLOSE:
                canvas.drawLine(7f, 7f, 17f, 17f, mPaint);
                canvas.drawLine(17f, 7f, 7f, 17f, mPaint);
                break;
            case PLUS:
                canvas.drawLine(12f, 6f, 12f, 18f, mPaint);
                canvas.drawLine(6f, 12f, 18f, 12f, mPaint);
                break;
            case TRASH:
                canvas.drawLine(5f, 7f, 19f, 7f, mPaint);
                mPath.moveTo(7f, 7f);
                mPath.lineTo(8f, 20f);
                mPath.lineTo(16f, 20f);
                mPath.lineTo(17f, 7f);
                canvas.drawPath(mPath, mPaint);
                canvas.drawLine(10f, 4f, 14f, 4f, mPaint);
                break;
            case KEYBOARD:
                mTmp.set(2.75f, 5.25f, 21.25f, 18.75f);
                canvas.drawRoundRect(mTmp, 3f, 3f, mPaint);
                mPaint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 4; i++) {
                    canvas.drawRect(6.2f + i * 3.5f, 8.4f, 7.8f + i * 3.5f, 10f, mPaint);
                    canvas.drawRect(6.2f + i * 3.5f, 11.4f, 7.8f + i * 3.5f, 13f, mPaint);
                }
                mPaint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(8.5f, 15.6f, 15.5f, 15.6f, mPaint);
                break;
            case CARDS:
                mPath.moveTo(4f, 14.5f);
                mPath.lineTo(4f, 6f);
                mPath.quadTo(4f, 3.5f, 6.5f, 3.5f);
                mPath.lineTo(14.5f, 3.5f);
                mPath.quadTo(17f, 3.5f, 17f, 6f);
                canvas.drawPath(mPath, mPaint);
                mTmp.set(7.5f, 7.5f, 20.5f, 21f);
                canvas.drawRoundRect(mTmp, 2.5f, 2.5f, mPaint);
                canvas.drawLine(10.5f, 12.5f, 17.5f, 12.5f, mPaint);
                canvas.drawLine(10.5f, 16f, 17.5f, 16f, mPaint);
                break;
            case SELECT_ALL:
                mTmp.set(4f, 4f, 20f, 20f);
                mPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{2.5f, 2.5f}, 0));
                canvas.drawRoundRect(mTmp, 2f, 2f, mPaint);
                mPaint.setPathEffect(null);
                mTmp.set(8f, 8f, 16f, 16f);
                canvas.drawRoundRect(mTmp, 1.5f, 1.5f, mPaint);
                break;
            case CHECK:
                mPath.moveTo(5f, 12.5f);
                mPath.lineTo(10f, 17f);
                mPath.lineTo(19f, 7.5f);
                canvas.drawPath(mPath, mPaint);
                break;
            case LINK:
                mPath.moveTo(10.5f, 7.5f);
                mPath.lineTo(7.5f, 7.5f);
                mTmp.set(3f, 7.5f, 12f, 16.5f);
                mPath.arcTo(mTmp, 270f, -180f, false);
                mPath.lineTo(10.5f, 16.5f);
                mPath.moveTo(13.5f, 7.5f);
                mPath.lineTo(16.5f, 7.5f);
                mTmp.set(12f, 7.5f, 21f, 16.5f);
                mPath.arcTo(mTmp, 270f, 180f, false);
                mPath.lineTo(13.5f, 16.5f);
                canvas.drawPath(mPath, mPaint);
                canvas.drawLine(8.5f, 12f, 15.5f, 12f, mPaint);
                break;
            case BACK:
                mPath.moveTo(14.5f, 5.5f);
                mPath.lineTo(8f, 12f);
                mPath.lineTo(14.5f, 18.5f);
                canvas.drawPath(mPath, mPaint);
                break;
            case BRUSH:
                mTmp.set(10.5f, 2.75f, 13.5f, 9.5f);
                canvas.drawRoundRect(mTmp, 1.5f, 1.5f, mPaint);
                mTmp.set(6.5f, 9.5f, 17.5f, 13.25f);
                canvas.drawRoundRect(mTmp, 1.2f, 1.2f, mPaint);
                mPath.moveTo(7.25f, 13.25f);
                mPath.lineTo(6.25f, 21f);
                mPath.lineTo(17.75f, 21f);
                mPath.lineTo(16.75f, 13.25f);
                canvas.drawPath(mPath, mPaint);
                canvas.drawLine(10.25f, 17f, 10f, 21f, mPaint);
                canvas.drawLine(13.75f, 17f, 14f, 21f, mPaint);
                break;
            case MIC:
                mTmp.set(9f, 3f, 15f, 14.5f);
                canvas.drawRoundRect(mTmp, 3f, 3f, mPaint);
                mTmp.set(5.75f, 6.5f, 18.25f, 17f);
                canvas.drawArc(mTmp, 0f, 180f, false, mPaint);
                canvas.drawLine(12f, 17f, 12f, 21f, mPaint);
                break;
            case UNDO:
                mPath.moveTo(5f, 10f);
                mPath.lineTo(15f, 10f);
                mPath.quadTo(20f, 10f, 20f, 14.5f);
                mPath.quadTo(20f, 19f, 15f, 19f);
                mPath.lineTo(9f, 19f);
                mPath.moveTo(8.75f, 6.25f);
                mPath.lineTo(5f, 10f);
                mPath.lineTo(8.75f, 13.75f);
                canvas.drawPath(mPath, mPaint);
                break;
            case REDO:
                mPath.moveTo(19f, 10f);
                mPath.lineTo(9f, 10f);
                mPath.quadTo(4f, 10f, 4f, 14.5f);
                mPath.quadTo(4f, 19f, 9f, 19f);
                mPath.lineTo(15f, 19f);
                mPath.moveTo(15.25f, 6.25f);
                mPath.lineTo(19f, 10f);
                mPath.lineTo(15.25f, 13.75f);
                canvas.drawPath(mPath, mPaint);
                break;
            case COPY:
                mTmp.set(8.5f, 8f, 19.5f, 20.5f);
                canvas.drawRoundRect(mTmp, 2.5f, 2.5f, mPaint);
                mPath.moveTo(5f, 15.5f);
                mPath.lineTo(5f, 6f);
                mPath.quadTo(5f, 3.75f, 7.25f, 3.75f);
                mPath.lineTo(15.5f, 3.75f);
                canvas.drawPath(mPath, mPaint);
                break;
            case CUT:
                canvas.drawCircle(7f, 17.25f, 2.75f, mPaint);
                canvas.drawCircle(17f, 17.25f, 2.75f, mPaint);
                canvas.drawLine(8.9f, 15.2f, 16.5f, 3.75f, mPaint);
                canvas.drawLine(15.1f, 15.2f, 7.5f, 3.75f, mPaint);
                break;
            case TO_START:
                canvas.drawLine(5f, 5.5f, 5f, 18.5f, mPaint);
                canvas.drawLine(19.5f, 12f, 9f, 12f, mPaint);
                mPath.moveTo(13f, 8f);
                mPath.lineTo(9f, 12f);
                mPath.lineTo(13f, 16f);
                canvas.drawPath(mPath, mPaint);
                break;
            case TO_END:
                canvas.drawLine(19f, 5.5f, 19f, 18.5f, mPaint);
                canvas.drawLine(4.5f, 12f, 15f, 12f, mPaint);
                mPath.moveTo(11f, 8f);
                mPath.lineTo(15f, 12f);
                mPath.lineTo(11f, 16f);
                canvas.drawPath(mPath, mPaint);
                break;
            case CLEAR_TEXT:
                mPath.moveTo(8.5f, 5.75f);
                mPath.lineTo(19f, 5.75f);
                mPath.quadTo(21.25f, 5.75f, 21.25f, 8f);
                mPath.lineTo(21.25f, 16f);
                mPath.quadTo(21.25f, 18.25f, 19f, 18.25f);
                mPath.lineTo(8.5f, 18.25f);
                mPath.lineTo(2.75f, 12f);
                mPath.close();
                canvas.drawPath(mPath, mPaint);
                canvas.drawLine(11.5f, 9.5f, 16.5f, 14.5f, mPaint);
                canvas.drawLine(16.5f, 9.5f, 11.5f, 14.5f, mPaint);
                break;
            case PENCIL:
                mPath.moveTo(4.5f, 19.5f);
                mPath.lineTo(5.25f, 15.75f);
                mPath.lineTo(15.5f, 5.5f);
                mPath.quadTo(16.75f, 4.25f, 18f, 5.5f);
                mPath.lineTo(18.5f, 6f);
                mPath.quadTo(19.75f, 7.25f, 18.5f, 8.5f);
                mPath.lineTo(8.25f, 18.75f);
                mPath.close();
                canvas.drawPath(mPath, mPaint);
                canvas.drawLine(13.5f, 7.5f, 16.5f, 10.5f, mPaint);
                break;
            default:
                break;
        }
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mPaint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return -1;
    }

    @Override
    public int getIntrinsicHeight() {
        return -1;
    }
}
