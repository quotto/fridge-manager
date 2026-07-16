# 食材在庫管理 Android アプリ MVP 実装計画

## 1. 目的

[`docs/requirements.md`](../../docs/requirements.md) を、GitHub Project 上で追跡可能な Epic、Story、Enabler に分解し、Android、クラウド AI、品質・リリースの複数エージェントで並行実装できる状態にする。

## 2. 管理方針

- GitHub Project: [食材在庫管理 Android アプリ MVP](https://github.com/users/quotto/projects/6)
- GitHub Milestone: [MVP](https://github.com/quotto/fridge-manager/milestone/1)
- GitHub Issues: [MVP の全 Issue](https://github.com/quotto/fridge-manager/issues?q=is%3Aissue%20milestone%3AMVP)
- GitHub Project の `Workflow` は `Backlog / Sprint Ready / In Progress / Review / Done / Blocked` を使用する。
- 優先度は `P0 / P1 / P2 / P3`、見積りは `XS / S / M / L / XL` とする。
- Epic は進捗集約、Story/Enabler は原則 1〜3 日で完了する実行単位とする。
- 各 Issue は目的、作業チェックリスト、受け入れ条件、依存関係、AI エージェント担当、優先度、見積りを持つ。
- 実装はテスト駆動で進め、実装担当とレビュー担当を分離する。

## 3. Epic と実行 Issue

GitHub 上では E1〜E8 をそれぞれ [#1](https://github.com/quotto/fridge-manager/issues/1)〜[#8](https://github.com/quotto/fridge-manager/issues/8) とし、実行 Issue [#9](https://github.com/quotto/fridge-manager/issues/9)〜[#42](https://github.com/quotto/fridge-manager/issues/42) をネイティブの子 Issue として関連付けている。

| ID | 種別 | タイトル | 優先度 | 見積り | AI 担当 |
| --- | --- | --- | --- | --- | --- |
| E1 | Epic | 開発基盤と継続的デリバリー | P0 | XL | manager / devops |
| E1-1 | Enabler | Android プロジェクトとビルド基盤を構築する | P0 | M | android-developer |
| E1-2 | Enabler | アプリ共通アーキテクチャ・画面遷移・デザイン基盤を構築する | P0 | M | android-developer |
| E1-3 | Enabler | TypeScript/CDK と dev・stg・prod 環境分離を構築する | P0 | M | cloud-developer / devops |
| E1-4 | Enabler | PR CI・静的解析・テスト・セキュリティ品質ゲートを構築する | P0 | M | devops / qa-reviewer |
| E2 | Epic | ローカル食材在庫管理 | P0 | XL | manager / android-developer |
| E2-1 | Enabler | 食材ドメインモデルと業務ルールを実装する | P0 | M | android-developer |
| E2-2 | Story | Room 永続化と一括トランザクションを実装する | P0 | M | android-developer |
| E2-3 | Story | 在庫一覧・空状態・在庫切れ表示を実装する | P0 | M | android-developer |
| E2-4 | Story | 手動登録と食材名サジェストを実装する | P0 | M | android-developer |
| E2-5 | Story | 編集・増加・減少・置換・削除を実装する | P0 | M | android-developer |
| E3 | Epic | 安全な画像入力と前処理 | P0 | L | manager / android-developer |
| E3-1 | Story | Photo Picker とカメラ入力を実装する | P0 | M | android-developer |
| E3-2 | Enabler | 画像検証・向き補正・縮小・圧縮・EXIF 除去を実装する | P0 | L | android-developer / security-reviewer |
| E3-3 | Story | 送信画像プレビュー・警告・一時ファイル破棄を実装する | P0 | M | android-developer |
| E4 | Epic | 認証済み AI 解析基盤 | P0 | XL | manager / cloud-developer |
| E4-1 | Story | Firebase 匿名認証と App Check クライアントを実装する | P0 | M | android-developer |
| E4-2 | Enabler | AI 解析 API 契約・Lambda・エラー体系を実装する | P0 | M | cloud-developer |
| E4-3 | Enabler | Firebase ID token・App Check・リプレイ保護を実装する | P0 | L | cloud-developer / security-reviewer |
| E4-4 | Enabler | サーバー画像検証と AI 出力 schema/provider 境界を実装する | P0 | L | cloud-developer / ai-developer |
| E4-5 | Story | Nova 2 Lite JP Geo 推論と保持なしガードを実装する | P0 | L | ai-developer / security-reviewer |
| E5 | Epic | AI 候補の確認と在庫反映 | P0 | XL | manager / android-developer |
| E5-1 | Story | Android API クライアント・状態・再試行・上限表示を実装する | P0 | M | android-developer |
| E5-2 | Story | 新規登録候補の追加・修正・除外 UI を実装する | P0 | L | android-developer |
| E5-3 | Story | 更新候補の増加・減少・置換選択 UI を実装する | P0 | L | android-developer |
| E5-4 | Story | 重複解決と最大30件の原子的な一括確定を実装する | P0 | L | android-developer |
| E6 | Epic | クォータ・監視・運用 | P0 | XL | manager / devops |
| E6-1 | Enabler | DynamoDB によるユーザー別日次・月次クォータを実装する | P0 | L | cloud-developer |
| E6-2 | Enabler | 全体上限・WAF・Budgets・緊急停止を実装する | P0 | L | cloud-developer / devops |
| E6-3 | Enabler | CloudWatch メトリクス・SLO・画像非保持ログを実装する | P0 | M | devops / security-reviewer |
| E6-4 | Enabler | デプロイ・ロールバック・障害対応 Runbook を整備する | P1 | M | devops |
| E7 | Epic | AI 品質評価とモデル採用 | P0 | XL | manager / ai-evaluator |
| E7-1 | Enabler | 日本の家庭内食品300枚以上の評価セットを設計・収集する | P0 | XL | ai-evaluator |
| E7-2 | Story | 二重アノテーションと調停済み正解データを作成する | P0 | L | ai-evaluator / qa-reviewer |
| E7-3 | Enabler | 品質・遅延・原価の評価ハーネスを実装する | P0 | L | ai-evaluator |
| E7-4 | Story | Nova ベースライン・フォールバック比較・回帰ゲートを確定する | P0 | L | ai-evaluator / manager |
| E8 | Epic | セキュリティ・プライバシー・国内リリース | P0 | XL | manager / qa-reviewer |
| E8-1 | Enabler | 脅威モデル・データフロー・独立セキュリティレビューを実施する | P0 | L | security-reviewer |
| E8-2 | Story | 端末データと Firebase 匿名ユーザーの削除を実装する | P0 | M | android-developer / cloud-developer |
| E8-3 | Enabler | プライバシーポリシー・削除案内・Data safety を整備する | P0 | M | security-reviewer / manager |
| E8-4 | Story | Android 対象7世代・アクセシビリティ・主要 E2E を検証する | P0 | XL | qa-reviewer / android-developer |
| E8-5 | Story | 署名 AAB と日本限定の段階公開・ロールバックを実施する | P0 | L | devops / manager |

## 4. 依存関係と実施順序

1. E1 を開始し、E1-1 と E1-3 を並行する。
2. E1-1 完了後に E2 と E3、E1-3 完了後に E4 と E6 を並行する。
3. E3 と E4 の縦切りが完成した時点で E5 を開始する。
4. E4 の評価可能な縦切り完成後に E7 を本格化する。
5. E8-1 は設計初期から継続し、E8-4・E8-5 をリリースゲートとする。

## 5. MVP 完了条件

- 要件定義の FR-001〜FR-013、NFR、AC がテスト証跡へ紐づいている。
- P0 の Story/Enabler がすべて Done で、未解決の重大・高リスク脆弱性がない。
- AI モデルが品質・遅延・原価・日本 Geo・保持なし要件の採用判定を通過している。
- 対象 Android 7 世代の互換性、アクセシビリティ、主要 E2E が合格している。
- 日本限定の段階公開、監視、緊急停止、ロールバックの手順が検証済みである。
