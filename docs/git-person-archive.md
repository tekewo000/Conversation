# 人物ごとの非公開 Git アーカイブ

人物 1 人を GitHub の非公開リポジトリ 1 個に固定します。アプリはリポジトリを作成しません。利用者が GitHub で空の **Private** リポジトリを作成し、アプリに URL と fine-grained personal access token を設定します。README・ライセンスなどの初期ファイルは付けないでください。既存リポジトリも、既定ブランチのファイルをすべて削除してあれば初回保存に使えます。過去のコミットや別ブランチ・タグは削除しないため、以前のデータは履歴に残ります。

トークンでは、対象の resource owner と保存先リポジトリを選び、リポジトリ権限を **Contents: Read and write**、**Metadata: Read-only** にします。他の権限は使いません。複数の人物を同じトークンで保存する場合は、それぞれの専用リポジトリを選択してください。組織の承認が必要なトークンは、承認後に利用します。GitHub 公式の [トークン権限](https://docs.github.com/en/rest/authentication/permissions-required-for-fine-grained-personal-access-tokens)、[Contents API](https://docs.github.com/en/rest/repos/contents)、[GraphQL 認証](https://docs.github.com/en/graphql/guides/forming-calls-with-graphql) を参照してください。

トークンは人物 UUID ごとに別の Android Keystore の AES-GCM 鍵で暗号化し、`noBackupFilesDir/github-token.<UUID>.v1` に AtomicFile で保存します。人物ごとに対象リポジトリだけを許可したトークンを使えます。ChatGPT 接続情報とも鍵・認証データ・ファイルを分けています。PC の GitHub 認証は流用しません。トークンは Git ファイル、人物資料、SQLite、ログ、例外メッセージには含めません。

## 保存するファイル

| パス | 内容 |
| --- | --- |
| `person.md` | 読み込んだ元の人物資料。UTF-8 の改行を含め、元の内容を保持。上限 2 MiB。 |
| `persona.json` | 人物 UUID、名前、会話用プロフィール、元資料の SHA-256。プロフィール上限 128 KiB。 |
| `events/<bucket>/<first>-<last>.jsonl` | SQLite の変更ジャーナル。1 行が完結したトランザクション。カーソル範囲で決まる固定名。 |
| `archive.json` | 形式、人物 UUID、GitHub の数値リポジトリ ID、完了カーソル、ファイル指紋、直近バッチ・親コミットの情報。 |

端末の認証情報、SQLite 本体・検索索引、同期状態、人物一覧、作業ファイルはアップロードしません。アーカイブの内容は通常の UTF-8 テキストとして保存します。非公開リポジトリへのアクセス権を持つ人は内容を読めます。

通常の同期では、前回カーソル以降を目標 1 MiB の差分バッチで書き出します。大きな人物資料の取り込みはトランザクションを分割せず、最大 20 MiB の差分を許可します。既存のイベントファイルや会話全体を読み直したり、新しい完全スナップショットを作ったりしません。元資料・正規化設定は指紋が変わった場合にだけ再送します。空の差分で内容も変わっていなければコミットを作りません。自動保存のタイミングは UI 側でまとめ、手動保存も利用できます。

AndroidのJSON出力ではスラッシュ等のエスケープで容量が増えるため、Git用のJSONLは最大40MiB、base64を含むHTTPリクエストは64MiBまで許可します。通常の保存目標は1MiBのままです。

## 競合と失敗時の動作

保存前に API から `private`、正式な `owner/repo`、数値 ID、既定ブランチを取得します。公開リポジトリは書き込み前に拒否します。人物一覧では `owner/repo` と数値 ID の重複を拒否し、リモートの `archive.json` に別の人物 UUID があれば使用しません。リネーム・移転によるリダイレクトにも追従しません。

コミットのない保存先では、全 Git 参照が空であることを確認してから Contents API で小さな `complete:false` の所有者マーカーを作り、その HEAD を指定した 1 回のコミットで完全なアーカイブにします。マーカーしかない状態はバックアップ完了とは扱いません。既存 HEAD に `archive.json` がない場合は、そのコミットのルートツリーを取得し、省略のない空のツリーだけを初回保存に受け入れます。この場合は既存 HEAD を親にして、完全なアーカイブを 1 回のコミットで追加します。前回の同期状態がある端末では、アーカイブの削除を再初期化とは扱わず競合で停止します。完全な保存は GitHub の [`createCommitOnBranch`](https://docs.github.com/en/graphql/reference/commits) に `expectedHeadOid` を指定し、同じコミットで差分とマニフェストを更新します。これにより、確認後に HEAD が変わっても既存履歴を上書きしません。[ファイル変更は base64 で送信](https://docs.github.com/en/graphql/reference/git)し、HTTP の送信先は `https://api.github.com` に固定します。別ホスト、HTTP リダイレクト、システムプロキシには認証を送信しません。

前回保存した HEAD とリモートが違えば同期を停止し、端末の会話は利用できます。自動マージ、強制 push、履歴削除は行いません。返答が途切れた場合は、リモートの人物 UUID・カーソル・バッチの指紋・実際の親コミットを照合します。同じ保存が完了していればローカル状態だけを進め、二重コミットを作りません。端末側で新しい会話が増えていれば、検証できた先頭分まで進め、残りを次の差分で保存します。競合が残る場合は手動で調査する必要があります。

## 復元用の情報

`archive.json` が `complete:true` のコミットを固定して取得します。`person.md` の SHA-256 と `persona.json` の SHA-256 を確認し、`events/` 以下の `.jsonl` を先頭カーソル順に並べます。各行の `cursor` が 1 からマニフェストの `cursor` まで連続し、範囲の重複や欠落がなく、各ファイル名の範囲が行と一致することを検証します。行の `events` は順序を保ち、1 行全体を 1 トランザクションとして空の DB に適用します。`persona.json` から `id`、`name`、`profile`、`source_sha256` と形式バージョン 1 の人物メタデータを得られます。コアのアーカイブ復元 API はトランザクション単位で差分を検証・適用します。

この Git クライアントはアップロードと競合検出を担当します。GitHub からの取得・復元を行う画面や、複数端末の履歴を統合する機能は含みません。端末の同期状態はアーカイブと別に保存し、成功または検証済み回復時のみ置き換えます。

復元処理ではGitのJSONLをデコードし、SQLiteと互換のUTF-8 JSON表現へ正規化してから、コアの20MiBバッチ・16MiBトランザクション上限内で再生する必要があります。転送用にエスケープされた最大40MiBの行を、そのままコアへ渡せる保証はありません。この変換を含む復元画面は未実装です。

## 検証

`scripts/test-connection.ps1` で `GitArchiveFilesTest` と `GitHubBackupTest` を実行します。模擬 HTTP では、公開先の拒否、別人物・数値 ID の拒否、空リポジトリの初期化、失われた応答の回復、HEAD 競合、差分ファイルの UTF-8、バッチ上限、認証情報を含まないエラーを確認します。`GitHubBackupTest` の引数 `--live-public-read` は、認証なしで `octocat/Hello-World` の公開メタデータだけを取得し、公開先の拒否を確認します。GitHub への書き込みは行いません。Android Keystore の実機動作と、利用者のトークンによる実際の非公開リポジトリへの保存は、別途端末上で確認します。
