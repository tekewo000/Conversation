# 返答と記憶整理の並行処理・内部ラベルの表示防止

ユーザーは、返答後に次の発言を送れない状態を報告し、記憶整理をバックグラウンドで並列に進めるよう依頼した。続けて内部ID・日時と、無関係な中国語の先頭行の混入を報告した。

## 採用する変更

- `ChatSession.reply` と記憶整理を分離する。`MainActivity` が返答後に送信を再開し、別セッションと `MemoryWorker` で記憶を整理する。整理要求は実行中と次回の2件までにまとめる。DBの未処理範囲は失わない。
- `ChatGptConnection` の返答HTTP全体のロックを外し、トークン取得・更新だけを直列化する。`HttpRequestPool` で並行通信とキャンセル世代を追跡する。返答停止の範囲と全通信停止の範囲を分ける。
- ID・記録時刻・省略フラグを本文から参照データのhistoryへ移す。`ReplyText` で報告済みの内部ラベル・制御記号・無関係な先頭行を取り除き、`SseReader` では明示された非最終チャネルをユーザー本文として使わない。
- 旧DBの原文は保全し、履歴の表示時に内部ラベルを除く。中国語の発生元は未特定のため、原因を修正済みとは扱わない。

## 対象と検証

`android/src/jp/tekewo/conversation/` のChatSession・ChatGptConnection・MainActivity・SseReaderを変更し、MemoryWorker・HttpRequestPool・ReplyTextを追加する。同じパッケージのホスト試験とConnectionTest・ChatSessionTest、`scripts/test-connection.ps1` を更新する。画像や私的な会話本文はリポジトリへ保存しない。

ホスト試験では、記憶整理をラッチで停止したまま次の返答が保存できること、整理要求の集約、停止範囲、古いキャンセル世代の要求拒否、ストリーム途中と保存時のラベル除去、普通の多言語文を保つことを確認する。Android Release・ComposeコンパイルでUIと接続処理を確認する。実機の待ち時間と実際のモデルの同時受付は別途の確認とする。

v0.5.1/code 11としてビルドし、README・自動記憶・検証記録・リリースノートを更新し、ソースと署名済みAPKをGitHubへ公開する。保存DBはschema 4のまま変更しない。
