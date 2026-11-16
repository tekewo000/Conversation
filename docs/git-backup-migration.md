# 既存Gitバックアップの暗号化移行

写真で使っていた人物ごとの復元キーを使います。元リポジトリの全ブランチ・タグ・コミットをGit bundleとして集め、暗号化して別の非公開GitHubリポジトリへ保存します。元のリポジトリは変更・削除しません。

## 準備

1. アプリの「バックアップ → データの復元キーを書き出す」でキーを別の安全な場所に保管します。既に書き出した写真用の`.key`も同じキーなので使えます。人物ごとに対応するキーが必要です。
2. GitHubで、移行先の**空のPrivateリポジトリ**を作ります。README等の初期ファイルや既存のブランチ・タグを入れないでください。
3. PCにJava 17以降、Git、Git LFSを用意します。GitHubへの認証はGitのcredential helperを使います。キーやトークンをチャットに貼る必要はありません。移行元の読み取りと、移行先のContents読み書きが必要です。
4. Releaseの`Conversation-backup-tools-<version>.zip`を展開します。この配布物ではAndroid SDKやコンパイラは不要です。ソースから使う場合はConversationの`scripts/`にある同名スクリプトを使えます。

キーを含むフォルダや元リポジトリの中を、移行先の作業フォルダにしないでください。作業フォルダは専用の新しい場所を指定します。失敗後の再開時だけ、同じ作業フォルダを使います。

## 全履歴を移行する

展開先でPowerShellを開き、実際の所有者・リポジトリ・キーファイル・作業先に置き換えて実行します。

```powershell
.\migrate-git-backup.ps1 -Source old-owner/old-person-repo -Destination new-owner/new-person-repo -RecoveryKey "D:\Keys\Conversation-person-images.key" -WorkDirectory "D:\Migration\person-migration"
```

ツールは全履歴を取得し、8MiBごとの暗号文と認証付き索引を新リポジトリに保存します。新リポジトリから取得し直したデータを復号し、全参照・HEAD・Gitオブジェクトの整合性を検証してから、暗号化した移行証明を保存します。Git LFSを使っている履歴では実体も取得・暗号化・照合します。取得できない実体がある場合は完了扱いにしません。

`Verified encrypted migration: https://github.com/...` が出たらPC側の移行が完了です。途中で失敗した場合は同じ引数で再実行します。別のキーや移行先へ切り替えて上書きすることはできません。

入力には既存の完全なbare mirrorのパスも指定できます。ローカルmirrorのoriginには元GitHubリポジトリが必要で、LFS実体は事前に取得してください。shallow/partial/promisor cloneや、別のオブジェクト保存先を参照するmirrorは受け付けません。

## アプリの今後の保存先を切り替える

1. 同じ人物を選び、「バックアップ → 別のGit保存先へ移行」を開きます。
2. PC側で検証済みの移行先と、そのリポジトリへのContents読み書きを許可したトークンを入力します。
3. アプリがキー・人物・元/先リポジトリ・移行証明を検証し、端末の全ジャーナルを新形式で初回保存します。完了後に接続先を切り替えます。写真のキーと人物IDは変わりません。

途中で止まった場合は同じ画面から同じ保存先で再開します。保存済みの移行状態とトークンは端末内で保護されます。移行中は通常の自動Git保存を止め、旧接続と端末の原本を維持します。PCで移した過去履歴は`history/`に残り、アプリの通常保存が上書きすることはありません。

## 元端末なしで過去のGitを復元する

移行先を通常のGitでcloneし、そのフォルダと復元キーを指定します。出力先はまだ存在しない別フォルダにします。

```powershell
.\migrate-git-backup.ps1 -Mode Verify -Source "D:\Backups\encrypted-person" -RecoveryKey "D:\Keys\Conversation-person-images.key"
.\migrate-git-backup.ps1 -Mode Restore -Source "D:\Backups\encrypted-person" -RecoveryKey "D:\Keys\Conversation-person-images.key" -OutputDirectory "D:\Recovered\person-history"
```

`repository.git`に元のbare repositoryとLFS実体が復元されます。そこから通常のGitでcloneして内容を見られます。元のコミットIDとタグを維持するため、署名されたオブジェクトも元のバイト列のままです。Gitホスティング側の認証・権限・Issues・PR・Actions等は移しません。サーバーから取得できる全ブランチ・タグに到達する履歴が対象で、到達不能オブジェクトやPR専用参照の完全取得は保証しません。

Verifyも整合性検査のため一時的な平文の復元領域を使い、終了時に削除します。Restoreの出力は意図した平文です。いずれも入力を上書きしません。

## 新しい会話・記憶・添付を復元する

ZIPを展開したフォルダ、またはアプリの暗号化差分が保存されたGitのcloneを指定します。OutputDirectoryを省略すると検証だけ行います。

```powershell
.\restore-backup.ps1 -Source "D:\Backups\expanded-or-cloned" -RecoveryKey "D:\Keys\Conversation-person-images.key"
.\restore-backup.ps1 -Source "D:\Backups\expanded-or-cloned" -RecoveryKey "D:\Keys\Conversation-person-images.key" -OutputDirectory "D:\Recovered\person-data"
```

人物資料、会話・記憶のJSONL、添付等を元の内容で取り出せます。過去履歴の復元と最新データの取り出しは別のコマンドです。認証トークンはバックアップに含めません。

## 保持されるものと限界

- 元GitHubリポジトリ、既存clone、過去のZIPはそのまま残ります。移行は元の平文を消去する操作ではありません。
- ツールの専用作業先には、一時的に元の平文mirrorと照合用の復元データを置きます。成功後はツールが作った領域だけを削除します。失敗時は復旧用に残る場合があります。フラッシュの過去ブロックの安全消去は保証しません。
- 移行先の外側からはデータ量、チャンク数、更新時刻、固定の形式名が分かります。人物名、元のファイル名、コミット情報、内容は暗号文の中です。
- 復元キーは全個人データを復号できる秘密情報です。失うと元端末なしでは復元できません。GitやZIPと一緒にアップロードしないでください。
- 通常バックアップの認証付き索引は1MiBを上限とし、超えた場合は保存を止めて端末の原本を保持します。

配布物にはJSON-javaのJARを同梱します。各ライセンスは同JAR内の配布資料と、ソースの依存情報を参照してください。
