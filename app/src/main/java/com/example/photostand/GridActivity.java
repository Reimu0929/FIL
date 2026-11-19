package com.example.photostand;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** フォルダ内の写真をタイル(サムネイル)で一覧表示。タップで全画面表示。 */
public class GridActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService ex = Executors.newFixedThreadPool(3);     // 画面に見えている分
    private final ExecutorService exPre = Executors.newSingleThreadExecutor(); // 先読み
    private final ExecutorService exQuery = Executors.newSingleThreadExecutor(); // MediaStore クエリ
    private final Set<String> queued = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private ArrayList<Photos.Item> items = new ArrayList<>();
    private String bucket, name;
    private long listBytes;
    private volatile int tier, lastFirst = -1;
    private volatile int lo, hi = -1;
    private volatile int queryToken;
    private float pages = 1f;
    private boolean showCount;
    private GridView grid;
    private BaseAdapter adapter;
    private MenuItem sizeItem;
    private TextView stat;

    private final Runnable statTick = new Runnable() {
        @Override public void run() {
            stat.setText("サムネイルのRAMキャッシュ: " + ThumbCache.memCount() + " 枚 / "
                    + String.format("%.1f MB", ThumbCache.memBytes() / 1048576.0));
            h.postDelayed(this, 700);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ThumbCache.init(getApplicationContext());
        tier = ThumbCache.tier(this);
        bucket = getIntent().getStringExtra("bucket");
        name = getIntent().getStringExtra("name");
        // 写真一覧のクエリは裏スレッドで行う(写真が多いとメインでやると ANR 級)。

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(4), dp(12), 0);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView sub = new TextView(this);
        sub.setText("サムネイル保存先: " + ThumbCache.where);
        sub.setTextSize(12);
        sub.setTextColor(Color.GRAY);
        stat = new TextView(this);
        stat.setTextSize(12);
        stat.setTextColor(Color.GRAY);
        texts.addView(sub);
        texts.addView(stat);
        head.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        // 「サイズ」ボタンは 3ドットの左のアクションバーへ移動した。
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(3));
        grid.setVerticalSpacing(dp(3));
        grid.setPadding(0, dp(8), 0, 0);
        grid.setFastScrollEnabled(true);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> {
            Intent i = new Intent(this, ViewerActivity.class);
            i.putExtra("bucket", bucket);
            i.putExtra("name", name);
            i.putExtra("start", pos);
            startActivity(i);
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { }

            @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                if (first != lastFirst) {
                    lastFirst = first;
                    prefetch(first, visible, total);
                }
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        applySize();
        loadItems();
    }

    /** 一覧を裏で読み込み、結果が出てからタイトル・先読み・表示を更新する。 */
    private void loadItems() {
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
                setTitle(name + "  (" + items.size() + ")");
                if (Prefs.preloadAll(this) == 2) ViewPreloader.startFolder(this, bucket);
                adapter.notifyDataSetChanged();
                startStatAndPrefetch();
            });
        });
    }

    /** カウンタ表示と先読みを開始する(一覧読み込み完了後 / onResume の両方から呼ぶ)。 */
    private void startStatAndPrefetch() {
        if (items.isEmpty()) return;
        pages = Prefs.pages(this);
        showCount = Prefs.showCount(this);
        stat.setVisibility(showCount ? View.VISIBLE : View.GONE);
        h.removeCallbacks(statTick);
        if (showCount) h.post(statTick);
        lastFirst = -1;
        grid.post(() -> {
            int first = grid.getFirstVisiblePosition();
            int visible = grid.getLastVisiblePosition() - first + 1;
            lastFirst = first;
            prefetch(first, visible, items.size());
        });
    }

    @Override protected void onResume() {
        super.onResume();
        ThumbCache.setCurrentBucket(bucket);
        startStatAndPrefetch();
    }

    @Override protected void onPause() {
        h.removeCallbacks(statTick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        queryToken++;
        ex.shutdownNow();
        exPre.shutdownNow();
        exQuery.shutdownNow();
        h.removeCallbacksAndMessages(null);
        RamBudget.replaceListBytes(listBytes, 0);
        listBytes = 0;
        super.onDestroy();
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        sizeItem = menu.add(0, 2, 0, "サイズ: " + ThumbCache.TIER_NAME[tier]);
        sizeItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);  // 3ドットの左に表示
        menu.add(0, 1, 0, "設定").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == 1) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == 2) {
            tier = (tier + 1) % ThumbCache.TIER_PX.length;
            ThumbCache.setTier(this, tier);
            applySize();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void applySize() {
        grid.setColumnWidth(dp(ThumbCache.TIER_DP[tier]));
        if (sizeItem != null) sizeItem.setTitle("サイズ: " + ThumbCache.TIER_NAME[tier]);
        lastFirst = -1;
        adapter.notifyDataSetChanged();
        grid.requestLayout();
    }

    /** 見えている範囲の前後(設定したページ数ぶん)のサムネイルを、近い順に先に作っておく。 */
    private void prefetch(int first, int visible, int total) {
        if (visible <= 0 || total <= 0) return;
        int extra = Math.round(pages * visible);
        lo = Math.max(0, first - extra);
        hi = Math.min(total - 1, first + visible - 1 + extra);
        if (extra <= 0) return;
        final int px = ThumbCache.TIER_PX[tier];
        for (int k = 1; k <= extra; k++) {
            queue(first + visible - 1 + k, total, px);
            queue(first - k, total, px);
        }
    }

    private void queue(final int idx, int total, final int px) {
        if (idx < 0 || idx >= total) return;
        final Photos.Item it = items.get(idx);
        if (ThumbCache.memGet(it, px) != null) return;
        final String key = idx + "|" + px;   // 段階切替直後に古いキーでブロックされないように
        if (!queued.add(key)) return;
        exPre.execute(() -> {
            try {
                if (idx < lo || idx > hi) return;                    // もう範囲外
                if (px != ThumbCache.TIER_PX[tier]) return;          // サイズが変わった
                ThumbCache.get(it, px);
            } finally {
                queued.remove(key);
            }
        });
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            ImageView iv = (ImageView) convert;
            if (iv == null) {
                iv = new ImageView(GridActivity.this) {
                    @Override protected void onMeasure(int w, int hh) {
                        super.onMeasure(w, w); // 正方形のタイル
                    }
                };
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(Color.DKGRAY);
            }
            iv.setTag(pos);
            final int px = ThumbCache.TIER_PX[tier];
            final Photos.Item it = items.get(pos);
            Bitmap cached = ThumbCache.memGet(it, px);
            if (cached != null) {
                iv.setImageBitmap(cached);
            } else {
                iv.setImageBitmap(null);
                final ImageView target = iv;
                final int p = pos;
                ex.execute(() -> {
                    Object tag = target.getTag();
                    if (tag == null || (Integer) tag != p) return;      // もう画面外
                    if (px != ThumbCache.TIER_PX[tier]) return;          // サイズが変わった
                    Bitmap t = ThumbCache.get(it, px);
                    if (t == null) return;
                    h.post(() -> {
                        Object now = target.getTag();
                        if (now != null && (Integer) now == p) target.setImageBitmap(t);
                    });
                });
            }
            return iv;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
