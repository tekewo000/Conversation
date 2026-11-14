# Repository instructions

<!-- CODEGRAPH_START -->
## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.
<!-- CODEGRAPH_END -->

## Release builds

- `Release` 設定でビルドした成果物は、必ず `tekewo000/Conversation` の GitHub Release へアップロードする。ローカルのAPK生成だけで作業を終了しない。
- ビルド・必要な検証・対応するソースのcommit/push・バージョンタグ・APK公開・公開後の確認までを一連の作業として完了する。公開のたびに追加の確認をユーザーへ求める必要はない。
- マニフェストのバージョンに対応する `v<version>` タグとReleaseを使い、タグは成果物に対応するソースを指すようにする。APKはGitの履歴へ直接追加せず、Releaseの添付ファイルとして保存する。
- 同じバージョンが公開済みならサイズとSHA256を照合する。同じ成果物なら再アップロード不要。異なる成果物で既存タグや公開済みAPKを黙って置き換えず、適切にバージョンを更新して公開する。
- 公開後はReleaseの状態、タグの参照先、APKのアップロード状態・サイズ・SHA256を確認し、最終回答にReleaseのURLと未検証の範囲を記載する。
- ONNX Runtimeを使うReleaseは `test-release-bindings.ps1` によるJNI名の検証と、署名済みRelease APKを対象にした `test-release-startup.ps1 -Serial <device>` によるモデル推論・起動試験を公開前に行う。Debug版の試験だけで代替しない。
