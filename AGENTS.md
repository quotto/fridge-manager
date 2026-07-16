# 開発エージェントガイドライン

## 目的

この文書は、食材在庫管理 Android アプリを複数の AI エージェントで安全かつ一貫して開発するための共通ルールを定める。

## 正本

- 要件の正本は `docs/requirements.md` とする。
- 作業の正本は GitHub Issues と GitHub Project とする。
- ローカルの計画資料は `issues/001/`、調査・レビュー記録は `work/` に置く。
- 要件と実装が矛盾する場合は、実装を進めず manager に報告する。

## チーム

| ロール | 主な責務 |
| --- | --- |
| manager | 要件確認、Issue 分解、依存関係・優先度管理、成果統合、受け入れ判定 |
| android-developer | Kotlin/Compose、ローカル DB、画像前処理、Firebase クライアント、Android テスト |
| cloud-developer | TypeScript、AWS IaC/API、Bedrock、クォータ、監視、クラウドテスト |
| qa-evaluator | AI 評価セット・指標、統合/E2E/互換性テスト、品質報告 |
| security-reviewer | 認証、入力検証、データ保持、ログ、依存関係、プライバシーのレビュー |
| devops | CI/CD、環境分離、デプロイ、リリース、ワークフロー監視 |

## 共通ルール

- 会話、Issue、ドキュメント、コードコメントは日本語を基本とする。
- Issue に記載された範囲を超える実装を行わない。追加作業は別 Issue として manager に提案する。
- 秘密情報、認証トークン、画像本体、在庫一覧をコード、ログ、Issue、テスト成果物へ含めない。
- 食材データと解析画像をクラウドへ永続化しない。
- AI 出力は信頼できない入力として検証し、ユーザー確定前に在庫へ反映しない。
- 既存のユーザー変更を上書きしない。破壊的な Git 操作は行わない。
- コミットは Conventional Commits に従い、1 Issue の論理的な変更単位で作成する。
- remote への push、PR 作成、デプロイはユーザーから許可された範囲で行う。

## Issue 着手フロー

1. 対象 Issue、親 Epic、依存 Issue、受け入れ条件を確認する。
2. GitHub Project の Status を `In Progress` に更新する。
3. 依存 Issue が未完了、または外部決定が不足している場合は `Blocked` にして manager へ報告する。
4. 実装より先に失敗するテストを追加する。
5. 最小限の実装でテストを通し、lint・build・関連テストを実行する。
6. セキュリティまたはプライバシーに関わる変更は security-reviewer のレビューを受ける。
7. PR 作成後は Status を `Review` にする。
8. 受け入れ条件を満たしレビューが完了したら `Done` にする。

## 実装ルール

### Android

- Kotlin と Jetpack Compose を使用し、Android 11（API 30）から Android 17（API 37）を対象とする。
- 業務ロジック、永続化、UI、外部通信の責務を分離する。
- 画像は端末側で要件どおりに検証・縮小・メタデータ除去し、一時ファイルを必ず削除する。
- 手動 CRUD は Firebase/AWS 障害時やオフライン時も利用可能にする。

### Cloud

- TypeScript と AWS のマネージドサービスを使用し、Infrastructure as Code で再現可能にする。
- Firebase ID token と App Check token の双方を検証する。
- Bedrock は JP Geo と `data_retention_mode: none` を強制し、条件不一致時は呼び出さない。
- クォータ判定は原子的に処理し、予算・全体上限到達時も手動管理への影響を避ける。

## テストと検証

- 単体テストは正常系・境界値・異常系を含める。
- API、永続化、認証、クォータは統合テストを作成する。
- 主要ユーザーシナリオは UI/E2E テストを作成する。
- Android 11〜17の互換性を確認する。
- AI モデルやプロンプト変更時は、固定評価セットで回帰評価する。
- 作業完了時は実行したコマンド、結果、未検証事項を PR と Issue に記録する。

## GitHub Project 運用

- Status は `Backlog`、`Sprint Ready`、`In Progress`、`Review`、`Done`、`Blocked` を使用する。
- Priority は `P0`〜`P3`、Estimate は `XS`、`S`、`M`、`L`、`XL` を使用する。
- Story/Enabler は必ず親 Epic と依存関係を記載する。
- Epic は全ての子 Issue が受け入れ済みになるまで閉じない。
- 進捗、依存関係、見積りが変わった場合は Issue と Project を同じ作業内で更新する。
