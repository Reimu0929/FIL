package com.example.photostand;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
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
    private Bitmap displayed;
    private int displayedIndex = -1;
    /** 直前の displayed が LruCache / ThumbCache に無い固有のビットマップ(拡大表示)か。 */
    private boolean displayedUnique;
    private long listBytes;
    private boolean keepZoomBitmap;
    private TextView info, stat, errorView;
    private final Set<Integer> preloadedRam = new HashSet<>();
    private final Set<Integer> preloadedDisk = new HashSet<>();
    /** Settings から参照する、最後に測定した全画面RAMの状態。 */
    private static volatile long lastFullRamBytes;
    private static volatile int lastFullRamCount;
    /** LruCache.snapshot() のコピーなしでキーを追跡する。 */
    private final Set<Integer> cachedKeys = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    /** 同じ idx の先読みタスクを積まないためのキュー。 */
    private final Set<Integer> prefetchQueued = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private String title;
    private volatile int index, token, target, thumbPx, preload, preloadTotal;
    private volatile int queryToken;
    private boolean showCount;

    private final Runnable hideInfo = () -> info.setVisibility(View.GONE);

    static long fullRamBytes() { return lastFullRamBytes; }
    static int fullRamCount() { return lastFullRamCount; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ThumbCache.init(getApplicationContext());
        ViewCache.init(getApplicationContext());
        thumbPx = ThumbCache.TIER_PX[ThumbCache.tier(this)];
        preload = Prefs.preload(this);
        keepZoomBitmap = Prefs.keepZoomBitmap(this);
        showCount = Prefs.showCount(this);

        String bucket = getIntent().getStringExtra("bucket");
        title = getIntent().getStringExtra("name");
        // 写真一覧のクエリは裏スレッドで行う(メインだと数千枚で ANR 級)。

        target = ViewCache.targetPx(this);
        cache = new LruCache<Integer, Bitmap>(Integer.MAX_VALUE) {
            @Override protected int sizeOf(Integer key, Bitmap value) {
                return value.getByteCount();
            }
            @Override protected void entryRemoved(boolean evicted, Integer key, Bitmap oldValue, Bitmap newValue) {
                cachedKeys.remove(key);
            }
        };

        synchronized (RamBudget.LOCK) {
            RamBudget.attachViewer(this);
            ThumbCache.enterViewer(this, bucket);
        }

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

            @Override public void onZoomed() { if (keepZoomBitmap) loadHi(index); }
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
        errorView = new TextView(this);
        errorView.setTextColor(Color.WHITE);
        errorView.setTextSize(20);
        errorView.setShadowLayer(6, 0, 0, Color.BLACK);
        errorView.setGravity(Gravity.CENTER);
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        setContentView(root);
        loadItems(bucket);
    }

    private void cacheDecoded(int idx, Bitmap bitmap) {
        synchronized (RamBudget.LOCK) {
            if (exCur.isShutdown() || !isInRamWindow(idx, index) || cache.get(idx) != null) return;
            int bytes = bitmap.getByteCount();
            int priority = idx == index ? RamBudget.CURRENT : RamBudget.PREFETCH;
            if (bytes > RamBudget.limit() || !RamBudget.makeRoom(bytes, priority)) return;
            cachedKeys.add(idx);
            cache.put(idx, bitmap);
            recordRamState();
        }
    }

    /** RamBudget.LOCK の保持中に呼ぶ。 */
    long retainedRamBytes() {
        return cache.size() + (displayed == null || cache.get(displayedIndex) == displayed
                ? 0L : displayed.getByteCount());
    }

    /** RamBudget.LOCK の保持中に呼ぶ。表示画像を残し、古い先読みから外す。 */
    long trimPrefetchRam(long bytes) {
        long removed = 0;
        // snapshot() は LinkedHashMap を古い順に返す。cachedKeys はハッシュ順序なので
        // ここでは置き換えない（get() もアクセス順を書き換えてしまう）。
        for (Map.Entry<Integer, Bitmap> entry : cache.snapshot().entrySet()) {
            if (removed >= bytes) break;
            if (entry.getKey() == index || entry.getValue() == displayed) continue;
            cache.remove(entry.getKey());
            removed += entry.getValue().getByteCount();
        }
        recordRamState();
        return removed;
    }

    private void displayBitmap(int idx, Bitmap bitmap, boolean zoom) {
        synchronized (RamBudget.LOCK) {
            Bitmap old = displayed;
            boolean oldUnique = displayedUnique;
            displayed = bitmap;
            displayedIndex = idx;
            displayedUnique = zoom;
            if (zoom) iv.setImageKeepView(bitmap);
            else iv.setImageFit(bitmap);
            RamBudget.makeRoom(0, RamBudget.CURRENT);
            recordRamState();
            RamBudget.LOCK.notifyAll();
            // 拡大用のビットマップだけが固有。LruCache / ThumbCache と共有している物は recycle しない。
            if (oldUnique && old != null && old != bitmap) old.recycle();
        }
    }

    /** Settings 表示用に、最後の全画面RAM状態を記録する。 */
    private void recordRamState() {
        lastFullRamBytes = retainedRamBytes();
        lastFullRamCount = cachedKeys.size()
                + (displayed != null && cache.get(displayedIndex) != displayed ? 1 : 0);
    }

    /** 一覧を裏で読み込み、終わってから最初の画像を表示する。 */
    private void loadItems(final String bucket) {
        final int my = ++queryToken;
        exQuery.execute(() -> {
            final ArrayList<Photos.Item> loaded = Photos.load(getContentResolver(), bucket);
            final long bytes = Photos.estimatedBytes(loaded);
            h.post(() -> {
                if (my != queryToken || isFinishing() || isDestroyed()) return;
                items = loaded;
                RamBudget.replaceListBytes(listBytes, bytes);
                listBytes = bytes;
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
        synchronized (RamBudget.LOCK) {
            if (cache != null) cache.evictAll();
            if (iv != null) iv.setImageDrawable(null);
            if (displayedUnique && displayed != null) displayed.recycle();
            displayed = null;
            displayedUnique = false;
            if (cache != null) recordRamState();
            RamBudget.replaceListBytes(listBytes, 0);
            listBytes = 0;
            ThumbCache.leaveViewer(this);
            RamBudget.detachViewer(this);
        }
        super.onDestroy();
    }

    private void step(int d) {
        if (items.isEmpty()) return;   // 一覧読み込み完了前のタップ/フリックは無視
        int n = items.size();
        index = ((index + d) % n + n) % n;
        show();
        if (info.getVisibility() == View.VISIBLE) showInfo();
    }

    private void showInfo() {
        if (items.isEmpty()) return;
        info.setText(items.get(index).name + "\n" + title + "   " + (index + 1) + " / " + items.size());
        info.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideInfo);
        h.postDelayed(hideInfo, 2500);
    }

    /** 今の写真を表示する。先読み済みなら即表示、なければサムネイルを仮に出す。 */
    private void show() {
        final int my = ++token;
        final int idx = index;
        if (errorView != null) errorView.setVisibility(View.GONE);
        trimWindow();
        Bitmap ready = cache.get(idx);
        if (ready != null) {
            displayBitmap(idx, ready, false);
            prefetch();
            return;
        }
        // 前画像の拡大・移動状態を残さないようにしてから、次の全画面画像を読み込む。
        iv.resetView();
        Bitmap thumb = ThumbCache.memGet(items.get(idx), thumbPx);
        // 移動中に低解像度サムネイルへ差し替えると画像が荒く見えるため、前の全画面画像を保つ。
        if (thumb != null && iv.getDrawable() == null) displayBitmap(idx, thumb, false);
        exCur.execute(() -> {
            if (my != token) return;
            Bitmap cached = cache.get(idx);
            Bitmap b = cached != null ? cached : ViewCache.get(items.get(idx), target, RamBudget.CURRENT);
            if (b == null) {
                h.post(() -> {
                    if (my != token) return;
                    errorView.setText("読み込めません: " + items.get(idx).name);
                    errorView.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "読み込めません", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            if (cached == null) cacheDecoded(idx, b);
            h.post(() -> {
                if (my != token) return;
                displayBitmap(idx, b, false);
                prefetch();
            });
        });
    }

    /** 先読みの範囲(現在位置から左右 preload 枚)の外にある画像を、メモリから外す。 */
    private void trimWindow() {
        synchronized (RamBudget.LOCK) {
            for (Integer k : new ArrayList<>(cachedKeys)) {
                if (!isInRamWindow(k, index)) cache.remove(k);
            }
            recordRamState();
            RamBudget.LOCK.notifyAll();
        }
    }

    private boolean isInRamWindow(int idx, int center) {
        int distance = Math.abs(idx - center);
        return Math.min(distance, items.size() - distance) <= preload;
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
        preloadedRam.clear();
        preloadedDisk.clear();
        preloadTotal = wanted.size();
        for (Integer idx : wanted) {
            if (cache.get(idx) != null) preloadedRam.add(idx);
            else queuePrefetch(idx);
        }
        updateStat();
    }

    /** 同じ idx は同時に1つだけキューする(show() ごとの再投入でタスクが積もらないように)。 */
    private void queuePrefetch(final int idx) {
        if (idx == index || !prefetchQueued.add(idx)) return;
        exPre.execute(() -> {
            try {
                // 実行待ちの間に通り過ぎた写真はデコードしない。
                int center = index;
                if (exPre.isShutdown() || idx == center || !isInRamWindow(idx, center)) return;
                if (cache.get(idx) == null) {
                    Bitmap b = ViewCache.get(items.get(idx), target, RamBudget.PREFETCH);
                    if (b == null) return;
                    // デコード中に範囲外へ出た写真は RAM キャッシュに追加しない。
                    // 同じ Bitmap を表示処理が使う場合があるので recycle() はしない。
                    cacheDecoded(idx, b);
                    // RAM に入らない場合も参照を捨てるだけ。ディスクには保存済み。
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
        // 現在の先読み範囲外の結果が遅れて来てもカウントしない。
        int center = index;
        if (idx == center || !isInRamWindow(idx, center)) return;
        final boolean inRam = cache.get(idx) != null;
        final boolean onDisk = !inRam && ViewCache.has(items.get(idx), target);
        if (!inRam && !onDisk) return;
        h.post(() -> {
            if (my != token) return;
            if (cachedKeys.contains(idx)) {
                preloadedDisk.remove(idx);
                preloadedRam.add(idx);
            } else {
                preloadedRam.remove(idx);
                preloadedDisk.add(idx);
            }
            updateStat();
        });
    }

    private void updateStat() {
        if (!showCount) return;
        preloadedRam.retainAll(cachedKeys);
        stat.setText("先読み: RAM " + preloadedRam.size() + " / ディスク " + preloadedDisk.size()
                + " / " + preloadTotal + " 枚(左右" + preload + "枚)"
                + "\nRAM: " + cachedKeys.size() + " 枚 / "
                + String.format("%.1f MB", cache.size() / 1048576.0));
    }

    /** 拡大し始めたら、より細かい画像を読み込んで差し替える。 */
    private void loadHi(final int idx) {
        if (items.isEmpty()) return;
        final int my = token;
        exHi.execute(() -> {
            if (my != token) return;
            Bitmap b = decodeHi(items.get(idx));
            if (b == null) return;
            h.post(() -> {
                if (my == token) displayBitmap(idx, b, true);
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
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int big = Math.max(o.outWidth, o.outHeight);
            int s = 1;
            int copies = it.rot == 0 ? 1 : 2;
            long cap = Math.min(RamBudget.limit(), Runtime.getRuntime().maxMemory() / 3);
            while (big / s > 4096 || RamBudget.bitmapBytes(o.outWidth, o.outHeight, s, copies) > cap) {
                s *= 2;
            }
            if (big / s <= target) return null;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            long bytes = RamBudget.bitmapBytes(o.outWidth, o.outHeight, s, copies);
            if (!RamBudget.begin(bytes, RamBudget.CURRENT)) return null;
            try {
                Bitmap b = BitmapFactory.decodeFile(it.path, o);
                if (b != null && it.rot != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(it.rot);
                    Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                    if (r != b) b.recycle();
                    b = r;
                }
                return b;
            } finally {
                RamBudget.end(bytes);
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
