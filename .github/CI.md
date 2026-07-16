# CI の実行方法

Pull Request と `main` への push では、Android、TypeScript/CDK、依存関係、秘密情報、IaC の検査を実行する。必須チェックの設定は GitHub の branch protection または ruleset で管理する。

## ローカルでの再現

CI と同じ Java 21、Node.js 22 を使用し、リポジトリのルートで次を実行する。

```bash
bash .github/scripts/verify-android.sh
npm ci --ignore-scripts
bash .github/scripts/verify-cloud.sh
```

秘密検査と IaC 検査は、各ツールをインストールした環境で次のように再現できる。

```bash
npm audit --audit-level=high --ignore-scripts
gitleaks git . --redact --no-banner
npm run synth
trivy config --exit-code 1 --severity HIGH,CRITICAL cdk.out
```

`npm audit` は全イベントで npm の既知脆弱性を検査する。依存関係レビューは Pull Request の Gradle・npm・GitHub Actions を含む依存関係差分を対象とし、重大度 `high` 以上の既知脆弱性を拒否する。検証証跡には GitHub Actions のチェック結果とログを使用する。テストデータや機微情報が混入し得るため、ビルド出力、テストレポート、カバレッジ結果は artifact として保存しない。

## 必須チェック

`main` の ruleset または branch protection で、次のジョブを必須ステータスチェックに指定する。GitHub のプランやリポジトリ設定でこれらを利用できない場合、ワークフローを追加しただけでは失敗時の統合を防止できない。

- `Android lint / test / build`
- `TypeScript lint / test / coverage / build / synth`
- `Dependency review`（Pull Request のみ）
- `npm dependency audit`
- `Secret scan`
- `IaC misconfiguration scan`

Pull Request には対象 Issue を `Closes #<番号>` で記載し、Issue から Actions の検証証跡を追跡できるようにする。必須チェックの変更時は、この一覧とリポジトリ設定を同じ作業で更新する。

Dependabot は GitHub Actions、Gradle、npm の更新を毎週確認する。更新PRも通常のCIとセキュリティ検査を通過させる。
