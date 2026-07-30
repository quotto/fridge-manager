# Google Play Data safety 申告根拠

## アカウント

- Firebase匿名認証はユーザー向けの登録、ログイン、本人確認、端末間利用を提供しないため、Google Playの app account には該当しないと判断する。
- この判断は公開時のPlay Console質問票とポリシー変更を再確認し、証跡をIssueへ残す。
- 該当性にかかわらず、アプリ内削除導線と公開Webページを提供する。

## Play Console回答案

サービス事業者として処理する委託先への転送は「共有」ではなく「収集」として申告する。販売、広告目的、第三者独自目的の利用はなく、いずれも共有しない。写真の短時間処理（ephemeral processing）も収集として回答する。

| Google Playデータ型 | 収集 | 共有 | 必須/任意 | ephemeral | 目的 | 処理先・保持 |
| --- | --- | --- | --- | --- | --- | --- |
| Photos and videos / Photos | はい | 共有しない | 画像解析を選ぶ場合のみ任意 | はい | App functionality | AWS・Amazon Bedrock。クラウド永続保存なし |
| Personal info / User IDs | はい | 共有しない | AI解析時は必須 | いいえ | App functionality、Fraud prevention, security | Firebase匿名IDとAWS上の不可逆hash。認証情報のbackup削除は最大180日、hashはクォータ・冪等性TTLまで |
| Device or other IDs | はい | 共有しない | アプリ利用時に必須 | いいえ | Fraud prevention, security、App functionality | Firebase Installation ID、Crashlytics Installation UUID、App Check。最大90日 |
| App activity / Other actions | はい | 共有しない | AI解析時は必須 | いいえ | Analytics、App functionality、Fraud prevention, security | request ID、モデルID、token数。AWSで90日 |
| App info and performance / Crash logs | はい | 共有しない | アプリ利用時に必須 | いいえ | Analytics | Firebase Crashlytics。90日 |
| App info and performance / Diagnostics | はい | 共有しない | アプリ利用時に必須 | いいえ | Analytics | Firebase Crashlyticsの端末・OS・診断情報、およびAWSのlatency・error種別。90日 |

IPアドレス、User-Agent、attestation情報はFirebase、Google Play Integrity、AWSの通信・認証基盤で不正利用防止のため処理される。Playフォーム上では該当する上記データ型へ含め、Consoleの最新設問に従って確認する。

## 収集しないデータ

- 食材一覧は端末外へ保存しない。
- 広告ID、連絡先、正確な位置情報、決済情報、健康情報は利用しない。
- Analytics SDKは使用しない。上表のAnalytics目的は障害・性能分析に限定する。
- 画像、在庫、token、匿名ユーザーIDそのものをAWSアプリケーションログへ記録しない。

## セキュリティ・削除

- 通信中はTLSで暗号化する。
- 端末内食材データは設定画面、アプリデータ消去、アンインストールで削除できる。
- 設定画面は端末内データ、一時画像、Firebase匿名ユーザーを削除する。
- Firebaseのbackup、App Check、CrashlyticsおよびAWSのセキュリティ・クォータ情報は、ポリシー記載の保持期限後に削除される。

## 公開前確認

- [ ] 公開主体名、施行日、公開問い合わせ先をHTMLへ反映
- [ ] GitHub Pagesを有効化し、`https://quotto.github.io/fridge-manager/privacy-policy.html` を公開
- [ ] 公開URLとアプリ内リンクを実機確認
- [ ] Firebase Authenticationの匿名ユーザー自動クリーンアップ設定
- [ ] 最新のPlay Console Data safetyフォームへ本表を転記し、回答証跡をIssueへ記録
- [ ] 匿名認証のapp account非該当判断を最新のPlay Console質問票で再確認
