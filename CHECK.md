# 修正項目チェックリスト

レビュー日: 2026-11-18 / 前提: 機能・UI・挙動はそのまま維持する

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
