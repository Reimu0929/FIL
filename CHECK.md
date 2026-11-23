# 修正項目チェックリスト

レビュー日: 2026-11-18 / 前提: 機能・UI・挙動はそのまま維持する

> **その後の変更 (2026-11-23, `perf/mediapad-t2`)**
> - 画像・一覧のRAM予算の既定を `Runtime.maxMemory()/2`（largeHeap 依存）から
>   `Prefs.DEFAULT_RAM_OVERRIDE_MB = 96` MiB に変更した。下記の `maxMemory/2` は
>   当時の設計を指す履歴であり、現在の既定値ではない。
> - `decodeHi()` の実効 cap は `min(RamBudget.limit(), maxMemory/3)` なので、
>   96MiB 既定では 12MP 写真の拡大用デコードが `inSampleSize=2` に落ちることがある
>   （画面長辺1920pxに対して約2016px。画質よりメモリ余裕を優先した判断）。
> - `trimPrefetchRam()` は LRU 順を保つため `cache.snapshot()` 走査のままである。
>   `cachedKeys` はハッシュ順序かつ `cache.get()` がアクセス順を書き換えるため置換しない。
> - 独自ランチャーアイコンを追加 (`mipmap-*` / `mipmap-anydpi-v26`)。

## 🔴 優先度: 高

- [x] **全画面キャッシュが実質フル解像度で、RAM先読みが機能しないケースがある**
  - `ViewCache.java:81-106` — `inSampleSize` を決めるだけなので、12MP写真(4032px)は2400px画面端末でもフル解像度のままデコード(~48MB/枚)・ディスク保存される
  - `ViewerActivity.java:62` — RAM上限が `maxMemory/6`(~85MB)のため1〜2枚しか保持できず、先読みしても毎回ディスク再デコードになる。ヒープが小さい端末では下限8MBより1枚が大きく、`cache.put` のたびに全追い出し
  - **仕様判断が必要**: RAM上限を先読み枚数ベースにする / 長辺を画面の1.5〜2倍に制限する、のどちらか
  - ※ `ViewCache.java:101` の「画質優先で追加縮小しない」は意図的な設計(v2移行)
  - **対応方針**: 画質優先(v2)を維持し、RAM上限だけを見直し。先読み枚数＋実測ビットマップサイズから算出し、`maxMemory/2` を安全上限として `LruCache.resize()` で反映

- [x] **MediaStoreクエリをメインスレッドで実行(ANRリスク)**
  - `MainActivity.java:107-136`(全画像行列挙)、`GridActivity.java:60`、`ViewerActivity.java:57`(フォルダ全件+自然順ソート)
  - 写真が数千〜数万枚あると起動・フォルダを開くたびにフリーズ ANR 級
  - `GridActivity` は Manifest に `configChanges` がないため回転のたびに再クエリも走る
  - 修正: 裏スレッド化して完了後にUIへ反映(表示内容は同一のまま)

- [x] **`.tmp` ファイルが永久に残る(ディスクリーク)**
  - `ThumbCache.java:149-165` / `ViewCache.java:108-127` — 書き込み中断・プロセスkillで残った `*.tmp` を `pruneNow`(ThumbCache.java:249-273)は `.jpg` しか扱わないため掃除されず、`diskStats` にも数えられず見えないまま蓄積
  - 修正: `pruneNow` で古い(例1時間超)`.tmp` を削除

- [x] **`loadHi` が `exCur` を占有してスワイプが詰まる**
  - `ViewerActivity.java:253-263` — 高解像度デコード(数秒)を本表示用の単一スレッドで行うため、ズーム直後にスワイプすると次の写真の表示が数秒待たされる
  - 修正: `loadHi` 専用executorに分離し、完了時の `token` 不一致なら `b.recycle()`

## 🟡 優先度: 中

- [x] **同一画像のデコードが多重化(single-flight なし)**
  - `ThumbCache.java:100-119` / `ViewCache.java:54-64` — キー単位の排他がなく、Grid の `ex`(3本)+`exPre`+`ViewPreloader` が同じ画像を同時デコード・同時JPEG書き込みする
  - `ViewerActivity.java:190-206` — `show()` のたびに同一 idx を `exPre` へ再投入しタスクが堆積
  - 修正: in-flight 用の `ConcurrentHashMap` で重複を弾く

- [x] **`Photos.natural` のアロケーション削減**
  - `Photos.java:71-92` — 数字のたびに `substring` 2個 + `replaceFirst` で正規表現を毎回コンパイル。数千枚のソートで数十万回呼ばれ体感遅延の一因
  - 修正: 部分文字列を作らない比較(先頭ゼロを読み飛ばし、有効桁数→辞書順)に置換

- [x] **`token` / `tier` の volatile 化**
  - `ViewerActivity.java:41`, `GridActivity.java:37` — JMM上はデータ競合。ただし最終表示の `token` 再チェックはUIスレッドで行われるため誤表示はほぼ起きない。無駄デコード防止の意味で付けるのが安全(変更は1語)

- [x] **`sha1` の高速化**
  - `ThumbCache.java:203-212` — 1バイトごとに `String.format`(Formatter生成)。`fileFor` 経由で頻呼び出し
  - 修正: hexテーブル参照(`char[] HEX`)による変換に置換

- [x] **権限許可直後に `load()` が2回走る**
  - `MainActivity.java:77-95` — `onRequestPermissionsResult` と `onResume` の両方で全画像クエリが実行される
  - 修正: コールバック側の `load()` を削る(`onResume` が必ず続くため表示結果は同じ)

- [x] **`LruCache.snapshot()` の多用(マップ全体コピー)**
  - `ThumbCache.java:91`、`GridActivity.java:48`(700ms毎・メインスレッド)、`ViewerActivity.java:179,248`
  - 修正: 自前カウンタ(`put`/`entryRemoved` で加減)に置換、または更新間隔を延ばす

- [x] **設定画面のポーリング間隔**
  - `SettingsActivity.java:44-63` — アイドル中も500ms毎のポーリング、2秒毎の `Debug.getMemoryInfo`(binder IPC)をメインスレッドで実行
  - 修正: ジョブ非実行時は間隔を伸ばす/状態変化時のみ反映、`getMemoryInfo` は裏スレッドへ

- [x] **`ViewPreloader.startFolder` が他ジョブ実行中に黙って無視される**
  - `ViewPreloader.java:46-49` — そのフォルダの未作成分が作られない
  - 修正: 要求を保留してジョブ完了後に再実行

## 🟢 優先度: 低(軽微・整理)

- [x] **未使用コードの削除** — `Prefs.CACHE_MAX_MB`(Prefs.java:13)、`ThumbCache.ensureAll(Item)` 1引数版(ThumbCache.java:125-127)
- [x] **小さい画像で3段階とも同一内容のJPEGが書かれる** — `ThumbCache.java:125-147` — 短辺160px未満のソースでディスク3倍消費。スキップ条件を追加で軽減
- [x] **`ZoomImageView.java:125-129`** — `performClick()` を呼ばない(lint/accessibility警告のみ、動作影響なし)
- [x] **`SettingsActivity.java:229-245`** — 破棄後に `h.post` する可能性(短命のActivity保持、実害は小)。`destroyed` フラグで抑止
- [x] **配布APKがdebugビルド** — `app/build.gradle:23-27` — `debuggable` 付きで配布されている。同じ `debug.keystore` で署名する `release` 型を追加すれば署名は同じまま(上書きインストール可)、デバッグフラグが外れて動作・サイズが改善。**READMEの手順更新が必要なため要判断**

## ❌ 非推奨(見た目が変わるため)

- サムネイルの `RGB_565` 化 — RAMは半減するがグラデーションのバンディングで見た目が変わる

## ✅ 検証済み: 問題なし(修正不要)

- `ViewPreloader.startAllOnce`(ViewPreloader.java:30-34)— `running` 判定が先にあり、スキップ時はフラグを立てないため次回 `onResume` で再試行される。バグではない
- `ThumbCache.fileFor` の `dir` null NPE 指摘 — `new File((File)null, name)` はJava仕様上NPEにならない。全呼び出し元は `init` 済み
- v1→v2のキャッシュ移行(legacy削除の正規表現)は `v2`+40桁に誤ヒットしない設計で正しい
- 自然順ソートの境界ケース(先頭ゼロ、同一桁比較等)は正しい
- `targetSdk 28`(レガシーストレージ)は現在の仕様どおり動作。Scoped Storage対応は大規模な仕様変更を伴うため対象外

## 推奨する着手順

1. MediaStore系のメインスレッド脱却 + `.tmp` 掃除
2. 全画面キャッシュのRAM戦略(仕様判断のみ)
3. `loadHi` のexecutor分離
4. single-flight化 + `natural()` のアロケーション削減
5. 以下、軽微なもの

---

# 追加レビュー(2026-11-18・第2回 / 上記修正反映後のコードが対象)

## 🔴 優先度: 高

- [x] **起動直後にタップ/フリックするとクラッシュする(ゼロ除算・境界外アクセス)**
  - `ViewerActivity.java:178-183` — `step()` の `int n = items.size(); ((index + d) % n + n) % n;`
  - `items` は `loadItems()` の裏スレッド完了後に入るため、それ以前は `n == 0`。写真が多い端末では数秒のウィンドウがあり、左右1/3タップやフリック(`onNav → step`)で `ArithmeticException`
  - 中央タップも `showInfo()`(`ViewerActivity.java:186`)で `items.get(index)` → `IndexOutOfBoundsException`
  - 修正: `step()` と `showInfo()` の冒頭で `if (items.isEmpty()) return;`

## 🟡 優先度: 中

- [x] **ディスク上限が「閲覧だけ」の間は一切執行されない**
  - `pruneNow()` は `ThumbCache.init` / CacheJob・ViewPreloader 完了時 / 上限変更時(`SettingsActivity.java:98-101`)にしか呼ばれない
  - `preloadAll` = オフ/起動時で、ブラウズ中の `ViewCache.get` / `ThumbCache.get` の `loadOrCreate` 書き込みには prune フックが無く、上限超過のまま増え続ける
  - 設定文言「超えたら古いものから削除」との食い違い。書き込み N 回ごと / `ViewerActivity.onDestroy` 等での実行を検討

- [x] **「ディスクキャッシュをすべて削除」が背後の裏スレッド書き込みと競合する**
  - `SettingsActivity.java:107-119` のガードは `CacheJob.running || ViewPreloader.running` のみ
  - Settings を前面に出しても `GridActivity` の `ex`/`exPre`、生存中の `ViewerActivity` の `exCur`/`exPre` は停止せずキャッシュを書き続ける。削除直後にファイルが復活し、表示カウントも矛盾する
  - 修正: 削除要求フラグを書き込み側へ渡す、または該当 executor を止める

- [x] **設定画面の「全画面RAMの上限(maxMemory/2)」が実効上限と一致しない**
  - `SettingsActivity.java:267-271` は cap を「上限」と表示するが、実効値は `ViewerActivity.ramLimitFor()`(`ViewerActivity.java:114-119`)の preload枚数×実測サイズ基準(最低16MB・cap=`maxMemory/2`)で通常はかなり小さい
  - 全画面RAMの**実測使用量**は Settings に依然として無く、ビューアのオーバーレイ(`showCount`)でしか見えない(◪ の経緯にある残課題)

## 🟢 優先度: 低(軽微・整理)

- [x] **`ViewCache.ensure` と `ViewCache.get` で single-flight が分離している**
  - `inFlight` と `ensureInFlight` が連動せず、ViewPreloader とビューアが同じ画像を二重デコード・二重書き込みする(原子 rename のため結果は壊れないが CPU/I/O の無駄)
- [x] **v1 キャッシュ削除が二重に走る** — `ViewCache.deleteLegacyCache()`(`:44-54`)と `ThumbCache.migrateFullCache()`(`:84-98`)が両 init スレッドから同じ `v[0-9a-f]{40}\.jpg` を削除
- [x] **重複コード** — `ViewCache.write()` / `ThumbCache.write()`(`:143-162` / `:234-250`)は実質同一、`generate()` もほぼ同一。`CacheJob` と `ViewPreloader` の worker/elapsed/status 構造もほぼコピー
- [x] **`ThumbCache.pruneNow()` の `complete` ArrayList コピーが無意味** — `allFiles(true)` 時点で `.jpg` のみ(`complete.add(f)` のコメントも古い)
- [x] **`Photos.natural()` のデッドコード** — `if (i < di) di = i;` / `if (j < dj) dj = j;`(`:84-85`)は `di = si ≤ i` が常に成り立つため発動しない
- [x] **先読みカウント表示が RAM/ディスクの混算** — `ViewerActivity.java:303-306` の `preloaded` は `cache.get()!=null || ViewCache.has()`(`markPreloaded`)。「RAM n / ディスク m」のように分けて表示する方が実態に合う
- [x] **先頭画像のデコード失敗時に黒画面のまま** — `ViewerActivity.java:213-217` は Toast のみ。仮サムネイルが残るため失敗が分かりにくい
- [x] **`ViewPreloader.status()` が保留中のフォルダ要求を表示しない** — `pendingBucket` 設定中も前回の「完了: X / Y」のまま。一覧取得中は「0 / 0 枚処理済み」になる
- [x] **`GridActivity.queue()` の重複排除キーが idx のみ**(`GridActivity.java:218`)— px を含まないためサイズ段階切替直後の prefetch が次回スクロールまで新しい段を準備しない(自己修復するので軽微)
- [x] **初期RAM見積りが最大4倍過小** — `ViewerActivity.java:121-124` の `target²×4` に対し、デコード結果は長辺最大2倍。`noteBitmapSize()` ですぐ補正されるが最初の数枚だけ追い出しが早い
- [x] **命名の不統一** — Manifest「フォトビューア」/ 設定フッター「PhotoViewer」(`SettingsActivity.java:196`)/ package `com.example.photostand`

## ✅ 第2回で確認済み(修正不要)

- 第1回のチェック項目はすべて反映済み(single-flight、exHi 分離、`.tmp` 掃除、非同期クエリ、`natural()` 高速化、sha1 hex 化、権限直後の二重 load 解消、snapshot カウンタ化、ポーリング間隔、`startFolder` の保留)
- `ViewPreloader.startAllOnce()` は `running` でスキップした場合にフラグを立てないため、次回 `onResume` で再試行される(バグではない)
- v1/v2 の正規表現、自然順ソートの境界ケース、`targetSdk 28` のレガシーストレージとしての動作は仕様どおり

## ⚠️ 既知の残存課題(今回の範囲外)

- `targetSdk 28` は Lint で Google Play 要件違反と報告されるが、**Google Play への提出予定はなし**。現状維持(lint の `ExpiredTargetSdkVersion` は除外済み)
- **実機での表示・キャッシュ生成動作は随時検証中**(エミュレータ/ビルドのみでは確認できない項目として継続ウォッチ)

## 第2回の推奨着手順

1. 起動直後のクラッシュ(ゼロ除算)を最優先で修正
2. 上限執行と「全削除」の競合(キャッシュ整合性)
3. RAM 表示の正確化(実効上限・全画面実測)
4. single-flight 統合と冗長コードの整理
5. 表示まわりの軽微な改善

---

# 追加レビュー(2026-11-18・第3回 / コミット d21080e 反映後のコードが対象)

## ✅ 第2回の15件: 全件反映済みを確認

- [x] クラッシュガード — `step()` / `showInfo()` / `loadHi()` の3箇所に `items.isEmpty()` ガード
- [x] 閲覧中の上限執行 — `writeJpeg()` が16回ごとに `pruneInternal()` を実行
- [x] 全削除の競合 — `diskLock` + `clearing` フラグで書き込みと完全に互斥
- [x] RAM表示の正確化 — 天井 / 実効上限 / 使用量の3行表示 + `ViewerActivity` の static 状態を参照
- [x] single-flight統合 — `ViewCache.ensure(false)` が `get()` 経由に一本化
- [x] v1掃除の一本化 — 加えてgit履歴で検証し、`full/` にv1名で書き込まれた実績は一度も無いため、thumbs側のみの掃除で漏れなし
- [x] 重複コード整理 — `Jobs` / `writeJpeg` / `decodeScaled` へ統合
- [x] 残り8件(デッドコード除去・先読みRAM/ディスク分離・errorView・`[保留]`表示・`idx|px`キー・見積り16t²・命名統一 ほか)もすべて反映

## 🔴 優先度: 高(新規)

- [x] **`Jobs.java` が API 24 以上を要求するクラスを使用し、Android 5.0〜6.x でクラッシュする**
  - `Jobs.java:5,17` — `import java.util.function.BooleanSupplier;` / 引数型として使用
  - `java.util.function` は Android API 24 で追加されたクラス。`app/build.gradle` は `minSdk 21` かつ `coreLibraryDesugaring` 無効のため、API 21〜23 では `CacheJob.start()` / ViewPreloader 開始時に `NoClassDefFoundError`
  - 「アプリ起動時に生成」モードなら起動直後にクラッシュ。README の「Android 5.0 以上」に反する
  - コンパイルは通るため GitHub Actions では検出されない(lint の NewApi 警告もビルドを失敗させない)
  - 修正: `Jobs` 内に独自の `interface Cancelled { boolean get(); }` を定義して置換する(数行)

## 🟡 優先度: 中〜低(残存・新規)

- [x] **`writeJpeg()` のロック範囲が広く、キャッシュ生成の並列性が低下した**
  - `ThumbCache.java:239-270` — JPEG圧縮(`b.compress`)まで `diskLock` 保持内で実行しており、ViewPreloader の2 worker の並列圧縮が効かなくなった
  - さらに16回ごとの `pruneInternal()`(全ファイル列挙 + ソート + 削除)もロック内で走り、書き込み側スレッドが瞬間的に停止する
  - 修正案: 一時ファイル作成はロック外で行い、`clearing` チェック + `Os.rename` のみをロック内に残す
- [ ] **全削除直後に数ファイルだけ復活する余地が残る** — 競合自体は解消済みだが、削除**後**に開始したキュー済み prefetch タスク(停止していない Grid / Viewer の裏スレッド)が新規書き込みする。実害は軽微
- [ ] **`ThumbCache.ensureAll()` と `ThumbCache.get()` の single-flight が別体系** — ViewCache 側は今回統合済みだが、Thumb 側は `allInFlight` が分離したまま(対称的な残存)

  ※ 上記2件は実害が軽微かつ他スレッドの世代受け渡し等が必要なため、今回は見送り(次回以降の候補)。

## 🟢 優先度: 低(軽微)

- [x] `writeJpeg()` 内の `clearing` チェック2箇所は `diskLock` 保持中は常に false となるため実質デッドコード(防御コードとしては可)
- [x] Settings の「全画面RAMの使用量」行が、ビューア未起動でも「0 枚 / 0.0 MB (ビューア終了時点)」と出る — 実効上限行の「—(ビューア未起動)」と表記が不統一
- [x] 「(ビューア終了時点)」という文言は不正確 — ビューアが背面で生存中は `cachePut()` のたびに値が随時更新される
- [x] `ViewPreloader.status()` の一覧取得中「0 / 0 枚処理済み」表記は残存
- [x] `ThumbCache.dir()` がどこからも参照されずデッドAPI化(第2回の掃除一本化の副作用)

## 第3回の推奨着手順

1. `BooleanSupplier` を独自インターフェースへ置換(Android 5.0〜6.x のクラッシュ回避)
2. 任意: `writeJpeg()` のロック範囲を rename 周りだけに縮小
3. その他は実害が軽微なため、必要になった時点で対応

**対応結果(2026-11-18)**: 1・2・軽微7件は反映済み(`Cancelled` / `diskGeneration` / ロック縮小 / 表記統一 / デッドAPI削除)。🟡の残り2件は保留。
