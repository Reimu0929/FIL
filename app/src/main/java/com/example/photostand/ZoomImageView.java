package com.example.photostand;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** ピンチで拡大縮小、拡大中はドラッグで移動できる画像ビュー。 */
public class ZoomImageView extends ImageView {
    interface Listener {
        void onTap(float x, float width);
        void onNav(int dir);
        void onLong();
        void onZoomed();
    }

    private final Matrix m = new Matrix();
    private final float[] v = new float[9];
    private ScaleGestureDetector sgd;
    private GestureDetector gd;
    private Listener listener;
    private float base = 1f;
    private boolean zoomNotified;

    public ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);

        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (getDrawable() == null) return false;
                float cur = scale();
                float goal = Math.max(base, Math.min(cur * d.getScaleFactor(), base * 8f));
                float f = goal / cur;
                m.postScale(f, f, d.getFocusX(), d.getFocusY());
                fix();
                setImageMatrix(m);
                if (!zoomNotified && scale() > base * 1.05f) {
                    zoomNotified = true;
                    if (listener != null) listener.onZoomed();
                }
                return true;
            }
        });

        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (sgd.isInProgress() || !isZoomed()) return false;
                m.postTranslate(-dx, -dy);
                fix();
                setImageMatrix(m);
                return true;
            }

            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (!isZoomed() && Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 600) {
                    if (listener != null) listener.onNav(vx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (isZoomed()) resetFit();
                else if (listener != null) listener.onTap(e.getX(), getWidth());
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (!isZoomed() && listener != null) listener.onLong();
            }
        });
    }

    void setListener(Listener l) { listener = l; }

    /** 画面にぴったり収まる大きさで表示する。 */
    void setImageFit(Bitmap b) {
        setImageBitmap(b);
        zoomNotified = false;
        resetFit();
    }

    /** 高解像度の画像に差し替える。今の拡大・位置はそのまま保つ。 */
    void setImageKeepView(Bitmap nb) {
        Drawable d = getDrawable();
        if (d == null || d.getIntrinsicWidth() <= 0) {
            setImageFit(nb);
            return;
        }
        float k = (float) nb.getWidth() / d.getIntrinsicWidth();
        setImageBitmap(nb);
        m.preScale(1f / k, 1f / k);
        base /= k;
        fix();
        setImageMatrix(m);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (getDrawable() != null) resetFit();
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        sgd.onTouchEvent(ev);
        gd.onTouchEvent(ev);
        return true;
    }

    private boolean isZoomed() {
        return scale() > base * 1.02f;
    }

    private float scale() {
        m.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    private void resetFit() {
        Drawable d = getDrawable();
        int vw = getWidth(), vh = getHeight();
        if (d == null || vw == 0 || vh == 0 || d.getIntrinsicWidth() <= 0) return;
        float iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        float s = Math.min(vw / iw, vh / ih);
        m.reset();
        m.postScale(s, s);
        m.postTranslate((vw - iw * s) / 2f, (vh - ih * s) / 2f);
        base = s;
        setImageMatrix(m);
    }

    /** 画像が画面の外へ行きすぎないように位置を直す。 */
    private void fix() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        m.mapRect(r);
        float vw = getWidth(), vh = getHeight(), dx = 0, dy = 0;
        if (r.width() <= vw) dx = (vw - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < vw) dx = vw - r.right;
        if (r.height() <= vh) dy = (vh - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < vh) dy = vh - r.bottom;
        m.postTranslate(dx, dy);
    }
}
