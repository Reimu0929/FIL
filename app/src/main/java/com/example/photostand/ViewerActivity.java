package com.example.photostand;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全画面ビューア。
 * ピンチで拡大縮小、左右スワイプ / 画面の左右1/3タップで前後、中央タップでファイル名と枚数。
 * 前後の写真は画面サイズのディスクキャッシュから先読みして、切り替えをすばやくする。
 */
public class ViewerActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService exCur = Executors.newSingleThreadExecutor();
    private final ExecutorService exPre = Executors.newSingleThreadExecutor();
    private ArrayList<Photos.Item> items = new ArrayList<>();
    private LruCache<Integer, Bitmap> cache;
    private ZoomImageView iv;
    private TextView info, stat;
    private String title;
    private int index, token, target, thumbPx, preload;
    private boolean showCount;

    private final Runnable hideInfo = () -> info.setVisibility(View.GONE);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ThumbCache.init(getApplicationContext());
        ViewCache.init(getApplicationContext());
        thumbPx = ThumbCache.TIER_PX[ThumbCache.tier(this)];
        preload = Prefs.preload(this);
        showCount = Prefs.showCount(this);

        String bucket = getIntent().getStringExtra("bucket");
        title = getIntent().getStringExtra("name");
        items = Photos.load(getContentResolver(), bucket);
        if (items.isEmpty()) { finish(); return; }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        target = Math.max(dm.widthPixels, dm.heightPixels);
        cache = new LruCache<Integer, Bitmap>(Math.max(8 << 20, (int) (Runtime.getRuntime().maxMemory() / 6))) {
            @Override protected int sizeOf(Integer key, Bitmap value) {
                return value.getByteCount();
            }
        };

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        iv = new ZoomImageView(this);
        iv.setListener(new ZoomImageView.Listener() {
            @Override public void onTap(float x, float width) {
                if (x < width / 3) step(-1);
                else if (x > width * 2 / 3) step(1);
                else showInfo();
            }

            @Override public void onNav(int dir) { step(dir); }

            @Override public void onZoomed() { loadHi(index); }
        });
        root.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(16);
        info.setShadowLayer(6, 0, 0, Color.BLACK);
        info.setPadding(40, 24, 40, 40);
        info.setVisibility(View.GONE);
        root.addView(info, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        stat = new TextView(this);
        stat.setTextColor(Color.WHITE);
        stat.setTextSize(13);
        stat.setShadowLayer(6, 0, 0, Color.BLACK);
        stat.setPadding(40, 32, 40, 24);
        stat.setVisibility(showCount ? View.VISIBLE : View.GONE);
        root.addView(stat, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        setContentView(root);

        index = Math.max(0, Math.min(getIntent().getIntExtra("start", 0), items.size() - 1));
        show();
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override protected void onDestroy() {
        token++;
        h.removeCallbacksAndMessages(null);
        exCur.shutdownNow();
        exPre.shutdownNow();
        if (cache != null) cache.evictAll();
        super.onDestroy();
    }

    private void step(int d) {
        int n = items.size();
        index = ((index + d) % n + n) % n;
        show();
        if (info.getVisibility() == View.VISIBLE) showInfo();
    }

    private void showInfo() {
        info.setText(items.get(index).name + "\n" + title + "   " + (index + 1) + " / " + items.size());
        info.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideInfo);
        h.postDelayed(hideInfo, 2500);
    }

    /** 今の写真を表示する。先読み済みなら即表示、なければサムネイルを仮に出す。 */
    private void show() {
        final int my = ++token;
        final int idx = index;
        trimWindow();
        Bitmap ready = cache.get(idx);
        if (ready != null) {
            iv.setImageFit(ready);
            prefetch();
            updateStat();
            return;
        }
        Bitmap thumb = ThumbCache.memGet(items.get(idx), thumbPx);
        if (thumb != null) iv.setImageFit(thumb);
        exCur.execute(() -> {
            if (my != token) return;
            Bitmap b = ViewCache.get(items.get(idx), target);
            if (b == null) {
                h.post(() -> {
                    if (my == token) Toast.makeText(this, "読み込めません", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            cache.put(idx, b);
            h.post(() -> {
                if (my != token) return;
                iv.setImageFit(b);
                prefetch();
                updateStat();
            });
        });
    }

    /** 先読みの範囲(現在位置から左右 preload 枚)の外にある画像を、メモリから外す。 */
    private void trimWindow() {
        int n = items.size();
        for (Integer k : cache.snapshot().keySet()) {
            int d = Math.abs(k - index);
            d = Math.min(d, n - d);
            if (d > preload) cache.remove(k);
        }
    }

    /**
     * 前後の写真を近い順に裏で準備する。
     * メモリに入りきる分はメモリへ、残りは画面サイズのディスクキャッシュへ保存する。
     */
    private void prefetch() {
        final int n = items.size();
        final int my = token;
        for (int k = 1; k <= preload && k < n; k++) {
            queuePrefetch((index + k) % n, my);
            queuePrefetch(((index - k) % n + n) % n, my);
        }
    }

    private void queuePrefetch(final int idx, final int my) {
        if (idx == index || cache.get(idx) != null) return;
        exPre.execute(() -> {
            if (my != token || cache.get(idx) != null) return;
            if (cache.size() < cache.maxSize()) {
                Bitmap b = ViewCache.get(items.get(idx), target);
                if (b == null || my != token) {
                    if (b != null) b.recycle();
                    return;
                }
                if (cache.get(idx) == null && cache.size() + b.getByteCount() <= cache.maxSize()) {
                    cache.put(idx, b);
                } else {
                    b.recycle();
                }
            } else {
                ViewCache.ensure(items.get(idx), target);
            }
            if (showCount) h.post(this::updateStat);
        });
    }

    private void updateStat() {
        if (!showCount) return;
        stat.setText("全画面のRAMキャッシュ: " + cache.snapshot().size() + " 枚 / "
                + String.format("%.1f MB", cache.size() / 1048576.0)
                + "\n先読み範囲: 左右" + preload + "枚");
    }

    /** 拡大し始めたら、より細かい画像を読み込んで差し替える。 */
    private void loadHi(final int idx) {
        final int my = token;
        exCur.execute(() -> {
            if (my != token) return;
            Bitmap b = decodeHi(items.get(idx));
            if (b == null) return;
            h.post(() -> {
                if (my == token) iv.setImageKeepView(b);
            });
        });
    }

    /** 拡大用に、最大4096pxまでの細かさで読み込む。画面サイズより細かくならないときは null。 */
    private Bitmap decodeHi(Photos.Item it) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(it.path, o);
            int big = Math.max(o.outWidth, o.outHeight);
            int fit = 1;
            while (big / (fit * 2) >= target) fit *= 2;
            int s = 1;
            while (big / s > 4096) s *= 2;
            if (s >= fit) return null;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeFile(it.path, o);
            if (b != null && it.rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(it.rot);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }
}
