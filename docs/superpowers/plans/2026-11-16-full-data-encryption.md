# 全個人データ暗号化・Git移行 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for native execution, or superpowers:subagent-driven-development if the user selects delegation. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 写真の既存キーで全個人データを暗号化し、既存Gitの全履歴を別の非公開Gitリポジトリへ移行・復元・照合できるようにする。

**Architecture:** 既存画像鍵をルートとし、一般ファイルとバックアップに用途別HKDF＋AES-GCMを使う。主DB・キャッシュは同じSQLCipher実装を使い、旧データは検証付きで移行する。Git全履歴は暗号化したbundleで保存し、新保存先への接続と通常の差分バックアップを継続する。

**Tech Stack:** Odin、C++17/JNI、Java 17/Android、SQLCipher Community 4.20.0、Git/Git LFS、PowerShell。PC側の暗号化処理はJava標準ライブラリを再利用し、Python等の暗号ライブラリを追加しない。

**Spec:** `docs/superpowers/specs/2026-11-16-full-data-encryption-design.md`（承認済み）。

## Global Constraints

- 人物ごとの既存画像鍵をルート鍵とする。キーを再生成・置換しない。
- 従来のCIMG v1画像とconversation-images-v1復元キーファイルを引き続き読み込める。
- 新形式ではHKDF-SHA256で用途別の鍵を導出する。AES-256-GCM、毎回独立した96ビットnonce、128ビットタグ。
- キー紛失、改ざん、別人物のキー、未知の形式は停止条件。空データで初期化したり平文保存に戻したりしない。
- minSdk 29、arm64-v8a、16KiB ELFアラインメント、FTS5、JSON、既存スキーマを維持する。
- 移行先は別のPrivateリポジトリ。元リポジトリの削除・force push・参照削除は自動では行わない。
- ReleaseはJNI名検証と署名済みAPKのモデル推論・起動試験を通して公開する。署名継承を維持し、データ削除やアンインストールで更新失敗を回避しない。
- 全タスクで実装前に対象の既存テストへ失敗する回帰ケースを追加し、失敗理由を確認する。実装後に対象テストを実行し、意味のある完了単位で対象ファイルのみcommitする。

## Review Focus

1. 写真鍵のない人物と、暗号化DBがあるのに鍵だけ失った人物を区別する。後者は新鍵生成・DB削除を拒否する（Task 2/3）。
2. 未チェックポイントWAL、人物切替のロールバック、整理途中DBが同時にある更新でも内容を保持する（Task 1/3）。
3. 同じバッチの再送と、成功応答が失われたGit保存で、乱数nonceの違いを別内容と誤判定しない（Task 4）。
4. mirror内のGit設定・hook・filter・LFS設定から任意コマンド実行や別ホストへの認証漏えいを起こさない（Task 5）。
5. 元Gitと端末の保存内容が異なる場合も、履歴bundleを端末の最新状態で置き換えたり、切替途中で旧接続を失ったりしない（Task 6）。

## 計画時に確認した依存

Maven Centralの `net.zetetic:sqlcipher-android:4.20.0` AARを解析した。SHA256は `1e57857adfd29e2f5ddb4686e1e5ec6659920220d4bf182fdb5ef085b5577100`。arm64の共有ライブラリにsqlite3_open_v2/key/prepare_v2等のC APIが公開され、JavaのSQLiteDatabaseも同じAARにある。FTS5、TEMP_STORE=2、ELF LOADの0x4000アラインメント、minSdk 23を確認した。実行時のJSON・FTS・暗号化・JNI互換性はTask 1で確認する。

解析物は `build/planning-sqlcipher-4.20.0/` にあり、Gitへ追加しない。通常依存はSQLCipherとその既存AndroidX/Kotlin依存だけとする。

## Task 1: 共通SQLCipherと暗号化DBを接続する

**Files:** 新規 `scripts/setup-sqlcipher.ps1`, `android/src/jp/tekewo/conversation/EncryptedDatabase.java`, `android/compose-tests/jp/tekewo/conversation/EncryptedDatabaseTest.java`。変更 `native/CMakeLists.txt`, `native/native_bridge.cpp`, `native/conversation_core.h`, `native/tests/bridge_test.cpp`, `core/conversation.odin`, `scripts/build-android.ps1`, `android/build.gradle`, `android/proguard-rules.pro`, `MainActivity.java`, `MemoryStore.java` とnativeSwitchPersonを呼ぶプローブ。

**Interfaces:** `EncryptedDatabase.open(File person, File database, boolean readOnly)` → SQLCipher SQLiteDatabase。新C ABI `bool conversation_init_database(const char* storage_dir, void* database)` は受け取ったDBの所有権を移し、成功・失敗ともCoreがcloseする。native内部 `open_person_database(path, key)` はsqlite3_key後、スキーマを読む前にcipher_versionと鍵の有効性を検証する。Java/JNIの `nativeSwitchPerson(byte[] directory, byte[] payload, byte[] databaseKey)` に変更し、空人物へ切り替える場合だけ空鍵を受け入れる。

- [ ] **1. RED:** `EncryptedDatabaseTest` に「鍵なし・別鍵で読めない」「WALを含め試験文字列が出ない」「FTS5/JSON/transactionが動く」を追加。bridge_testに人物切替失敗後も元の鍵とDBへ戻るassertを追加し、既存スイッチ経路で失敗することを確認する。
- [ ] **2. 実装:** 固定SHAのAARをsetupで取得・照合し、arm64 SOをCMakeのIMPORTED共有ライブラリとして使う。Androidの通常sqlite3静的リンクを外し、同じSOをGradleからAPKへ一度だけ収録する。JNI keepルールを確認する。
- [ ] **3. 実装:** CoreのDB初期化を外から開いたDBでも共用できる形に分ける。従来conversation_initはWindowsのCoreテスト用に残し、Android製品ビルドでは平文初期化を拒否するdefineを有効にする。nativeSwitchPersonの全呼出元（MainActivity、MemoryStore、JNIとプローブ）を更新し、元人物への復帰用キーを保持・破棄する。派生DB鍵はTask 2のderiveへ接続するまでは試験専用の固定入力のみで検証する。SQLCipherのDB破損handlerは自動削除しない実装を指定する。
- [ ] **4. GREEN:** `pwsh -File scripts/test-native.ps1`、`pwsh -File scripts/test-core.ps1`、`pwsh -File scripts/build-android.ps1 -Configuration Debug`、`pwsh -File scripts/test-compose.ps1 -Serial <disposable-emulator>`。新DB試験PASSと既存試験の全成功、SO依存・16KiB確認を保存してcommitする。

## Task 2: 既存画像鍵で一般ファイル・状態を暗号化する

**Files:** 新規 `DataVault.java`, `DataFiles.java`, `PersonState.java`（いずれも `android/src/jp/tekewo/conversation/`）、対応する `android/tests/jp/tekewo/conversation/*Test.java`。変更 `ImageVault.java`, `ConversationApplication.java`, `PersonRegistry.java`, `AttachmentFiles.java`, `AttachmentMedia.java`, `ImageDownload.java`, `PersonIcon.java`, `MainActivity.java`, `MemoryWork.java`, `scripts/test-connection.ps1`。

**Interfaces:** `DataVault.derive(byte[] root, String person, String purpose)` → 32 bytes。`seal/open(byte[] root, String person, String purpose, String path, byte[] data)` → bytes。`DataFiles.read(File person, File file)` → bytes、`DataFiles.write(File person, File file, byte[] data)` → void は既存ImageVault.keysで鍵を取得し、原子的な認証付き保存を行う。`PersonState.load(File person)` → JSONObject、`PersonState.save(File person, JSONObject state)` → void は下書き・添付参照・進捗文字列を保存する。

- [ ] **1. RED:** RFC5869ベクタ、改ざん、切詰め、別用途・人物・パス、空データ、非ASCII名、旧CIMG復号のassertを追加。PersonRegistry/AttachmentFilesテストに動画・PDF・txtと人物資料が平文で残らず、読み出し内容が同一であるassertを加える。
- [ ] **2. RED:** 暗号化一般ファイルだけがある人物で鍵を消した場合、新鍵を作らない。draft保存失敗時は元draftを残す。PersonRegistryの起動用JSONに名前・repoが出ない。以上を確認してから実装する。
- [ ] **3. 実装:** 形式は `CDAT`＋バージョン1＋nonce12＋ciphertext/tag。HKDFのsaltはUTF-8 `Conversation.data.v1`、infoは長さ付きの人物ID・用途とし、AADも長さ付き要素で曖昧な連結を避ける。用途は `database`, `file`, `backup`, `history`。旧ImageVault形式は保持し、一般ファイルは新形式で保護する。
- [ ] **4. 実装:** 暗号化DB・一般ファイルの存在もキー欠落判定に含める。人物一覧をIDのみの索引と暗号化人物メタデータに分け、資料・全添付・下書き・Worker状態の全読み書きを置き換える。認証ストアと明示的ダウンロードは既存経路を維持する。
- [ ] **5. GREEN:** `pwsh -File scripts/test-connection.ps1` と対象Compose試験を実行し、変更した呼出元・新規sources/test登録も確認してcommitする。

## Task 3: 既存端末の全データを安全に移行する

**Files:** 新規 `android/src/jp/tekewo/conversation/DataMigration.java`, `android/compose-tests/jp/tekewo/conversation/DataMigrationTest.java`。変更 `EncryptedDatabase.java`, `ConversationApplication.java`, `MainActivity.java`, `MemoryStore.java`, `MemoryWork.java`, `SemanticMemory.java`, `MemoryRebuild.java`, `PersonRegistry.java` と各テスト。

**Interfaces:** `DataMigration.ensure(Context context)` は全人物の移行を完了して返るか例外を返す。UIはバックグラウンドで呼び、Workerはデータアクセス前に同じgateを通る。`EncryptedDatabase.migrate(File person, File database)` は旧DBを検証・暗号化して採用する。既存キー取得とDataFilesを使用する。

- [ ] **1. RED:** 旧人物資料、全種添付、下書き、未チェックポイントWAL、検索索引、未完了MemoryRebuild、旧exportを含むfixtureを作る。移行後の全ユーザーテーブルの論理内容・user_version・検索結果の一致をassertする。
- [ ] **2. RED:** 暗号化一時DB作成後・検証後・採用後の各段階で中断して再実行する。容量不足、未知スキーマ、別鍵、破損DB、symlink、移行中のWorker/人物切替も試験し、原本保持と平文利用停止をassertする。テスト用障害はテスト側のI/O・プロセス制御で発生させ、製品に試験専用スイッチを足さない。
- [ ] **3. 実装:** 旧DBのWALを反映してsqlcipher_exportし、全テーブルを順序を固定して照合する。FTS等の再生成可能な索引は検索結果も比較する。fsyncと原子的採用、暗号化した移行記録を使い、採用前の失敗では旧DBを残す。旧DB/WAL/SHM/journal除去後まで完了にしない。
- [ ] **4. 実装:** Javaの両キャッシュDBをEncryptedDatabaseへ移す。既存のinvalidCache例外処理が認証失敗・鍵欠落でDBを削除しないよう、認証エラーを再生成可能な索引不整合と区別する。個人情報を持つ旧SharedPreferencesと旧export等もDataFilesへ移行する。PersonRegistryの既存のroot legacy削除は移行前に実行させず、残存データを照合・保護する。未知の孤立履歴は削除せず停止する。
- [ ] **5. GREEN:** migration/semantic/rebuild/既存Compose試験を専用エミュレータで実行。成功後のアプリ保存領域を検査し、試験用秘密文字列・通常SQLiteヘッダ・平文.bak・旧設定がないことを確認してcommitする。

## Task 4: Git・ZIPを暗号化し、元端末なしで復元する

**Files:** 新規 `android/src/jp/tekewo/conversation/BackupRestore.java` とJavaテスト。変更 `GitArchiveFiles.java`, `GitHubBackup.java`, `BackupArchive.java`, `AttachmentFiles.java`, `MainActivity.java`, `core/conversation.odin`, `native/native_bridge.cpp`, `native/conversation_core.h`, 対応するCore/Javaテストとtest-connection sources登録。

**Interfaces:** `GitArchiveFiles.build(String personId, long repoId, String rawMd, JSONObject delta, byte[] root)` → Batch。`GitHubBackup(Transport transport, byte[] root)` も同じ鍵でremote manifestを検証する。C ABIのwriter型は `bool (*)(void* user, const char* logical_path, const uint8_t* data, int32_t size)`、新APIは `bool conversation_export_with_writer(writer, void* user)`。一貫したread transaction内で論理パスと最大16MiBのチャンクを渡し、callback成功後のみ次へ進む。`BackupRestore.main(String[] args)` は `verify|restore --key <path> --source <path> [--output <new-path>]`。

- [ ] **1. RED:** 人物資料・会話・全添付・manifestを含むGit/ZIPに平文が出ない。キー未書き出し時は画像の有無によらず停止する。改ざん・ファイル欠落・未知パス・ZIP traversal・過剰サイズを拒否する。旧平文リポジトリへは送信しない。
- [ ] **2. RED:** 同一差分再送で不要commitなし、成功応答喪失後の再確認、expected-HEAD競合拒否を既存GitHubBackupTestへ追加する。nonceが変わっても暗号化manifest内の同一論理指紋で再送を判定する。
- [ ] **3. 実装:** 外側を `format.json`（固定の形式識別のみ）、暗号化 `archive.enc`、`objects/<opaque-id>.enc` とする。論理パス・カーソル・SHA256・repo所有情報はarchive.encに置き、opaque-idは用途分離した鍵によるHMACから生成する。Git履歴の作者・メッセージに人物名やカーソルを入れない。HTTP/添付/JSONの既存上限を暗号化前後で維持する。
- [ ] **4. 実装:** Core exportをcallback方式へ移し、JNI callbackがメモリ上のチャンクを暗号化して書く。旧export APIはAndroid製品では拒否し、既存のnativeExport呼出元を全て切り替える。ZIPの内部一時出力も暗号文のみとし、完了manifestを最後に原子的に採用する。
- [ ] **5. 実装:** BackupRestoreは復元キーを読み、全チャンクとmanifestの認証・完全性を確認して専用出力へ復元する。旧CIMGも読める。空でない出力先への上書きを拒否する。主にJSONL/添付から復元できるためPCにSQLCipherを要求しない。
- [ ] **6. GREEN:** test-core/test-native/test-connectionとComposeのバックアップ経路を実行。別Javaプロセスで元端末鍵ストアなしの復元・照合、出力失敗と再送も検証してcommitする。

## Task 5: 全履歴を暗号化するPC移行コマンド

**Files:** 新規 `tools/git-migration/GitMigration.java`, `tools/git-migration/GitMigrationTest.java`, `scripts/migrate-git-backup.ps1`, `scripts/test-git-migration.ps1`, `docs/git-backup-migration.md`。DataVault/ImageVaultと既存GitHubBackupのリポジトリ検証を再利用する。

**Interfaces:** wrapperは `-Source <https-url-or-local-mirror> -Destination <owner/repo> -RecoveryKey <file> -WorkDirectory <new-path>`。Javaコマンドは `migrate|verify|restore` と同じ入力を受ける。restoreには新規 `--output` が必須。キーはファイル名だけを引数に渡す。Git subprocessのstdoutはProcessのバイナリstreamとして扱う。

- [ ] **1. RED:** ローカルfixtureに複数ブランチ、軽量/注釈/署名付きtag、merge、削除・改名されたファイル、旧CIMGを作る。移行先から復元した全refs/OIDと到達可能な全オブジェクトの一致、HEAD、git bundle verify/fsck成功をassertする。
- [ ] **2. RED:** チャンク欠落・入替え・切詰め・誤鍵、空でない移行先、公開repo、同一repo、取得中のref更新、未取得LFS実体、途中push・応答喪失、再開時の異なるsource/keyを拒否する。hook/filterや外部LFS URLのfixtureでプロセス実行・認証漏えいを防ぐ。
- [ ] **3. 実装:** URL入力ならcheckoutなしのmirrorを専用作業先へ取得し、公開refsを固定する。既存ローカルmirrorは読み取り入力として扱う。全履歴を `git bundle create - --all HEAD` のstdoutから8MiB単位で暗号化する。最後の暗号化索引が順序・総数・総bytes・全体指紋・全refs・HEAD（detachedの場合も含む）を認証する。外側はTask 4の形式識別と共存し、`history/`配下に保存する。
- [ ] **4. 実装:** LFSポインタを到達可能な全blobから検出し、許可した元GitHubホストの実体を全件取得、サイズ/SHA256を照合して暗号化する。LFS実体も索引で認証する。通常のGit mirror/復元検証領域は機密な平文入力として作業先に隔離し、検証成功後にその領域だけを安全な絶対パス検査の下で削除する。安全消去は保証しない。
- [ ] **5. 実装:** credential helperから秘密をstdin/stdoutパイプで受け、HTTP headerにのみ使う。移行先のPrivate・数値ID・空の全参照を検査し、暗号文だけの新Gitを固定作者・固定文言でpushする。forceを使わない。リモートから再取得して復元mirrorをfsck、全OID照合し、完了証明を暗号化する。元repoは変更しない。
- [ ] **6. GREEN:** `pwsh -File scripts/test-git-migration.ps1` で全fixtureの往復試験、再開試験、destinationの全blobを走査した平文秘密検出試験を行う。実GitHubの試験は専用の空Privateテストrepoが利用できる場合だけ実施し、ローカル試験と区別して記録する。操作説明と復元コマンドを記載してcommitする。

## Task 6: 移行先へ接続し、暗号化差分保存を再開する

**Files:** 変更 `PersonRegistry.java`, `GitHubBackup.java`, `MainActivity.java`, `ComposeUi.kt`, `ConversationApplication.java`, 対応するJava/Composeテスト。新規UI専用の大型コンポーネントは作らない。

**Interfaces:** `PersonRegistry.rebindRepository(String personId, String repository, long repositoryId)` は検証済みの切替を原子的に保存する。`GitHubBackup.verifyMigration(...)` はキー・人物・元repo/移行先repo・完了証明を検証し、新保存の初期SyncStateを返す。

- [ ] **1. RED:** 名前/人物ID/写真鍵を維持して旧repoから移行先へ切替。元履歴bundleは端末にない過去版も含めて保持される。repoId不一致・別人物証明・競合・初回保存失敗・Activity再作成では旧接続とローカルデータが失われない。
- [ ] **2. 実装:** 既存バックアップ画面に移行手順と新repo設定を追加する。人物UUIDに対応するキーを使い、移行先の証明を確認後、旧カーソルを流用せず全ジャーナルを暗号化初回保存する。history/は上書きしない。新保存の成功を確認して接続先とSyncStateを同時採用する。自動同期と切替を同じstoreGate/同期制御で直列化する。
- [ ] **3. 実装:** 「データの復元キー」の説明と全データ保護の範囲を更新し、通常画面の操作を維持する。ユーザーの明示的書き出しと外部コピーの境界も説明する。復元キー取込み経路を設け、失った鍵を同じ人物IDで復元できるよう検証する。
- [ ] **4. GREEN:** test-connectionと対象Compose試験で切替・中断再開・新差分・再送・別人物拒否を確認し、既存管理画面の操作試験も通してcommitする。

## Task 7: 更新・Release試験と公開

**Files:** 新規 `android/device-tests/jp/tekewo/conversation/DataSecurityProbe.java`。変更 `scripts/test-release-startup.ps1`, `scripts/test-release-bindings.ps1`（必要な場合のみ）, `android/AndroidManifest.xml`, `README.md`, `docs/image-security.md`, `docs/git-person-archive.md`, `docs/validation.md`, `docs/releases/<version>.md`。

**Interfaces:** `test-release-startup.ps1 -Serial <serial> -DataSecurity` は署名済みRelease自身の暗号化・移行・復元経路を試験する。既存ONNX起動プローブと併用できるよう、別呼出しでそれぞれ検証する。

- [ ] **1. RED/GREEN:** 旧版で作ったデータ入りの専用エミュレータへ署名継承したReleaseを上書きし、UID、既存人物/会話/添付/下書き/途中作業とキーの維持をプローブで確認する。鍵なし/誤鍵/中断/平文残存の回帰を含める。
- [ ] **2. 全体検証:** test-core、test-native、test-connection、test-git-migration、対象エミュレータの全Compose試験を実行する。失敗を名前付きで記録し、未解決のデータ保護失敗がある成果物は公開しない。
- [ ] **3. バージョン:** 現manifestは0.9.4/versionCode 35。公開済みtag/Releaseと再確認し、未使用なら0.10.0/versionCode 36へ更新する。既存公開版がある場合は次の未使用バージョンと増加したversionCodeを採る。
- [ ] **4. Release:** `pwsh -File scripts/build-android.ps1 -Configuration Release`、`test-release-bindings.ps1 -Mapping <generated-mapping>`、`test-release-startup.ps1 -Serial <emulator>`、同じ署名済みAPKに対して `-DataSecurity` を実行。APKのサイズ/SHA256、署名、native依存とアラインメントを確認する。
- [ ] **5. 公開:** 対応ソースをcommit/pushし、同じcommitを指すv<version> tagとtekewo000/ConversationのReleaseにAPKを添付する。再ダウンロードのSHA256/サイズ、tag参照、Release公開状態を確認する。APKや機密fixtureをGit履歴へ入れない。
- [ ] **6. 報告:** Release URL、移行コマンドの使い方、テストの実行結果と範囲を提示する。実データ移行に必要な元repo/移行先repo/復元キーファイルが未指定なら、それを明示しツール検証と実移行を混同しない。旧リポジトリ削除は実行しない。

## 自己レビュー

鍵互換、全保存経路、移行と復元、既存Gitの全履歴、切替、Releaseまで各Taskへ割り当てた。未完了整理DBとWAL、nonceによる再送誤判定、LFS/悪意あるGit設定、鍵紛失、旧接続の保持を個別試験にした。共通の暗号化実装はDataVault、DB開閉はEncryptedDatabase、起動時移行はDataMigrationへ集約する。移行先URL等の実データは計画に埋め込まない。
