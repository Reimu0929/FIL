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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final ExecutorService exQuery = Executors.newSingleThreadExecutor();   // MediaStore クエリ
    private final ExecutorService exHi = Executors.newSingleThreadExecutor();      // 拡大用の高解像度デコード
    private ArrayList<Photos.Item> items = new ArrayList<>();
    private LruCache<Integer, Bitmap> cache;
    private ZoomImageView iv;
    private TextView info, stat;
    private final Set<Integer> preloaded = new HashSet<>();
    /** LruCache.snapshot() のコピーなしでキーを追跡する。 */
    private final Set<Integer> cachedKeys = ConcurrentHashMap.newKeySet();
    /** 同じ idx の先読みタスクを積まないためのキュー。 */
    private final Set<Integer> prefetchQueued = ConcurrentHashMap.newKeySet();
    private String title;
    private volatile int index, token, target, thumbPx, preload, preloadTotal;
    private volatile int queryToken;
    private int maxBitmapBytes;
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
        // 写真一覧のクエリは裏スレッドで行う(メインだと数千枚で ANR 級)。

        DisplayMetrics dm = getResources().getDisplayMetrics();
        target = Math.max(dm.widthPixels, dm.heightPixels);
        cache = new LruCache<Integer, Bitmap>(initialRamLimit()) {
            @Override protected int sizeOf(Integer key, Bitmap value) {
                return value.getByteCount();
            }
            @Override protected void entryRemoved(boolean evicted, Integer key, Bitmap oldValue, Bitmap newValue) {
                cachedKeys.remove(key);
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
        loadItems(bucket);
    }

    /** RAM上限を先読み枚数ベースにする。画質(追加縮小なし)は維持したまま上限だけ見直す。 */
    private int ramLimitFor(long bytesPerImage) {
        int slots = Math.max(3, preload * 2 + 1);          // 現在の1枚＋左右 preload 枚
        long want = bytesPerImage * slots;
        long cap = Runtime.getRuntime().maxMemory() / 2L;  // OOM 回避の安全上限
        return (int) Math.max(16L << 20, Math.min(want, cap));
    }

    private int initialRamLimit() {
        // 実測前は画面長辺のデコード画像を見積もる。
        return ramLimitFor((long) target * target * 4L);
    }

    /** 実測した1枚のサイズが大きくなったときだけ上限を引き上げる。 */
    private void noteBitmapSize(int bytes) {
        if (bytes <= maxBitmapBytes) return;
        maxBitmapBytes = bytes;
        cache.resize(ramLimitFor(bytes));
    }

    private void cachePut(int idx, Bitmap b) {
        cache.put(idx, b);
        cachedKeys.add(idx);
    }

    /** 一覧を裏で読み込み、終わってから最初の画像を表示する。 */
    private void loadItems(final String bucket) {
        final int my = ++queryToken;
        exQuery.execute(() -> {
            final ArrayList<Photos.Item> loaded = Photos.load(getContentResolver(), bucket);
            h.post(() -> {
                if (my != queryToken || isFinishing() || isDestroyed()) return;
                items = loaded;
                if (items.isEmpty()) { finish(); return; }
                index = Math.max(0, Math.min(getIntent().getIntExtra("start", 0), items.size() - 1));
                show();
            });
        });
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
        queryToken++;
        h.removeCallbacksAndMessages(null);
        exCur.shutdownNow();
        exPre.shutdownNow();
        exQuery.shutdownNow();
        exHi.shutdownNow();
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
        // 前画像の拡大・移動状態を残さないようにしてから、次の全画面画像を読み込む。
        iv.resetView();
        Bitmap thumb = ThumbCache.memGet(items.get(idx), thumbPx);
        // 移動中に低解像度サムネイルへ差し替えると画像が荒く見えるため、前の全画面画像を保つ。
        if (thumb != null && iv.getDrawable() == null) iv.setImageFit(thumb);
        exCur.execute(() -> {
            if (my != token) return;
            Bitmap b = ViewCache.get(items.get(idx), target);
            if (b == null) {
                h.post(() -> {
                    if (my == token) Toast.makeText(this, "読み込めません", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            noteBitmapSize(b.getByteCount());
            cachePut(idx, b);
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
        for (Integer k : new ArrayList<>(cachedKeys)) {
            int d = Math.abs(k - index);
            d = Math.min(d, n - d);
            if (d > preload) cache.remove(k);
        }
    }

    /**
     * 前後の写真を近い順に裏で準備する。
     * すべてをディスクへ準備し、RAMにはバイト上限の範囲で保持する。
     */
    private void prefetch() {
        final int n = items.size();
        Set<Integer> wanted = new LinkedHashSet<>();
        for (int k = 1; k <= preload && k < n; k++) {
            wanted.add((index + k) % n);
            wanted.add(((index - k) % n + n) % n);
        }
        wanted.remove(index);
        preloaded.clear();
        preloadTotal = wanted.size();
        for (Integer idx : wanted) {
            if (cache.get(idx) != null) preloaded.add(idx);
            else queuePrefetch(idx);
        }
        updateStat();
    }

    /** 同じ idx は同時に1つだけキューする(show() ごとの再投入でタスクが積もらないように)。 */
    private void queuePrefetch(final int idx) {
        if (idx == index || !prefetchQueued.add(idx)) return;
        exPre.execute(() -> {
            final int my = token;
            try {
                if (cache.get(idx) == null) {
                    Bitmap b = ViewCache.get(items.get(idx), target);
                    if (b == null) return;
                    noteBitmapSize(b.getByteCount());
                    if (my != token) {
                        b.recycle();
                        return;
                    }
                    if (cache.get(idx) == null && cache.size() + b.getByteCount() <= cache.maxSize()) {
                        cachePut(idx, b);
                    } else {
                        // get() がディスクへ保存済みなので、RAMに入らなくても先読みは完了している。
                        b.recycle();
                    }
                }
                // 完了時点の token で表示を更新する(待機中に切り替わっていても最新の状態を反映)。
                markPreloaded(idx, token);
            } finally {
                prefetchQueued.remove(idx);
            }
        });
    }

    private void markPreloaded(final int idx, final int my) {
        if (!showCount) return;
        final boolean ready = cache.get(idx) != null || ViewCache.has(items.get(idx), target);
        h.post(() -> {
            if (my != token || !ready) return;
            preloaded.add(idx);
            updateStat();
        });
    }

    private void updateStat() {
        if (!showCount) return;
        stat.setText("先読み(RAM/ディスク): " + preloaded.size() + " / " + preloadTotal
                + " 枚(左右" + preload + "枚)"
                + "\nRAM: " + cache.size() + " 枚 / "
                + String.format("%.1f MB", cache.size() / 1048576.0));
    }

    /** 拡大し始めたら、より細かい画像を読み込んで差し替える。 */
    private void loadHi(final int idx) {
        final int my = token;
        exHi.execute(() -> {
            if (my != token) return;
            Bitmap b = decodeHi(items.get(idx));
            if (b == null) return;
            h.post(() -> {
                if (my == token) iv.setImageKeepView(b);
                else b.recycle();   // 表示が切り替わっていたら解放する
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
