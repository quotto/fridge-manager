# Cloud / AI 実装計画案

## 方針

- 対象: AWS / TypeScript / AI 基盤。Android 端末内の実装は別計画とする。
- 各 Story / Enabler は原則 1〜3 日で完了できる粒度とする。
- 実装は IaC（AWS CDK + TypeScript）を基本とし、開発・本番の環境差分を設定で管理する。
- `manager` が依存関係と進捗を統括し、`cloud-developer`、`ai-developer`、`security-reviewer`、`devops` が担当する。
- AI 出力、画像、Firebase token はすべて信頼できない入力として扱う。画像・プロンプト・解析結果はクラウドに永続化しない。

## Epic C-E1: AWS サーバーレス基盤と安全な解析 API

**目的:** 認証済みの正規 Android アプリだけが、上限を満たす画像を AI 解析へ送信できる最小権限の API を構築する。

**完了条件:** C-01〜C-06 が完了し、有効な token と画像では解析処理へ進み、不正リクエストは Bedrock 呼び出し前に拒否される。

**優先度:** P0  
**見積り:** XL（配下 Issue 合計）  
**担当:** `manager` / `cloud-developer` / `security-reviewer`

### C-01 [Enabler] TypeScript/CDK プロジェクトと環境分離の初期構築

**目的:** API、Lambda、DynamoDB、WAF、監視を再現可能に構築する土台を用意する。

**主要タスク:**

- [ ] TypeScript の Lambda / CDK ワークスペースとテスト構成を作成する
- [ ] `dev` / `prod` の環境設定、東京リージョン固定、命名・タグ規則を定義する
- [ ] lint、unit test、CDK synth、依存脆弱性検査を CI に組み込む
- [ ] IAM role を機能単位に分離し、Bedrock、DynamoDB、CloudWatch の最小権限を定義する
- [ ] 秘密値や Firebase 設定をアプリ成果物・ログへ含めない設定注入方式を定義する

**受け入れ条件:**

- `ap-northeast-1` 向けに `cdk synth` が再現可能に成功する
- Lambda の実行 role に管理者権限や不要なワイルドカード権限がない
- CI で build、lint、test、synth、重大脆弱性チェックが実行される

**依存関係:** Epic C-E1  
**優先度:** P0  
**見積り:** M  
**担当:** `cloud-developer` / `devops`

### C-02 [Story] API Gateway と解析 Lambda の HTTP 契約を実装する

**目的:** Android アプリから 1 画像を受け付け、機械検証可能な成功・失敗応答を返す解析 API の境界を確立する。

**主要タスク:**

- [ ] リクエスト ID、処理種別、画像、必要最小限の現在値を含む API schema を定義する
- [ ] 最大 payload、Content-Type、1リクエスト1画像、最大30候補の制約を実装する
- [ ] Lambda timeout と API timeout を考慮し、通常30秒目標・クライアント60秒打切りに適した失敗契約を定義する
- [ ] 認証失敗、App Check失敗、画像不正、上限超過、AI障害、解析不能を安定した error code に分類する
- [ ] リクエスト ID を生成・伝播し、同一リクエスト再送でクラウド上の副作用が重複しない契約を実装する

**受け入れ条件:**

- API schema の契約テストが成功し、不要な全在庫データを要求しない
- 画像や token がログに残らず、すべての応答に request ID が含まれる
- エラー種別と再利用可能日時を Android 側が判定できる

**依存関係:** Epic C-E1、C-01  
**優先度:** P0  
**見積り:** M  
**担当:** `cloud-developer`

### C-03 [Story] Firebase ID token をサーバー側で検証する

**目的:** 有効な匿名 Firebase ユーザーだけを識別し、利用者別制御に安全な主体 ID を渡す。

**主要タスク:**

- [ ] Google 公開鍵の取得・キャッシュ・ローテーション対応を実装する
- [ ] 署名、有効期限、issuer、audience、subject を検証する
- [ ] Firebase UID をログへ平文出力せず、クォータ用の不可逆キーへ変換する
- [ ] 期限切れ、改ざん、別プロジェクト、鍵取得障害のテストを作成する

**受け入れ条件:**

- 有効な対象プロジェクトの匿名 ID token のみ通過する
- 不正 token は Bedrock とクォータ予約の前に 401 で拒否される
- token 本体および直接識別可能な UID はログに出力されない

**依存関係:** Epic C-E1、C-01、C-02  
**優先度:** P0  
**見積り:** S  
**担当:** `cloud-developer` / `security-reviewer`

### C-04 [Story] Firebase App Check と limited-use token のリプレイ防止を実装する

**目的:** Play Integrity で認められた正規アプリからの高額 API 呼び出しだけを許可し、token 再利用を防ぐ。

**主要タスク:**

- [ ] App Check token の署名、issuer、audience、app ID、有効期限を検証する
- [ ] limited-use token の consume / replay protection を高額解析 endpoint に適用する
- [ ] Firebase 検証サービス障害時は AI 呼び出しをフェイルクローズする
- [ ] 通常 token、limited-use token、再利用 token、不正 app ID の統合テストを作成する

**受け入れ条件:**

- ID token と App Check token の双方が有効な場合のみ解析へ進む
- 消費済み limited-use token の再利用が拒否される
- 検証障害時も手動在庫管理に影響せず、API は安全なエラーを返す

**依存関係:** Epic C-E1、C-02、C-03、Firebase の App Check / Play Integrity 設定  
**優先度:** P0  
**見積り:** M  
**担当:** `cloud-developer` / `security-reviewer`

### C-05 [Story] サーバー側画像検証と安全なメモリ内デコードを実装する

**目的:** 偽装形式、破損画像、デコード爆弾を Bedrock 前段で排除し、許可画像だけをメモリ上で処理する。

**主要タスク:**

- [ ] request body の実サイズを 3 MiB 以下に制限する
- [ ] Content-Type と magic bytes の一致、JPEG形式、RGB、アニメーション非対応を検証する
- [ ] 幅、高さ、長辺2,048px、総画素4MP以下を安全なヘッダー解析・デコードで再検証する
- [ ] 一時ファイル、S3、キャッシュを使用せず、メモリ上 bytes のライフサイクルを限定する
- [ ] truncated image、偽装拡張子、巨大寸法、過大 payload のセキュリティテストを作成する

**受け入れ条件:**

- 許可範囲外・破損・偽装画像は AI 呼び出し前に拒否される
- Lambda のログ、DLQ、トレース、永続ストレージに画像 bytes が残らない
- 正常画像の処理後および例外時に画像への参照が解放される

**依存関係:** Epic C-E1、C-02  
**優先度:** P0  
**見積り:** M  
**担当:** `cloud-developer` / `security-reviewer`

### C-06 [Enabler] API脅威モデルとセキュリティ回帰テストを確立する

**目的:** token、画像、AI出力、コスト悪用を含む信頼境界を明文化し、実装後も防御を維持する。

**主要タスク:**

- [ ] STRIDE ベースで API Gateway、Lambda、Firebase、Bedrock、DynamoDB の信頼境界を整理する
- [ ] prompt injection、JSON/schema攻撃、token replay、画像爆弾、クォータ競合、ログ漏えいのテスト項目を定義する
- [ ] Lambda依存ライブラリのSCA、secret scan、IaC scanをCIへ追加する
- [ ] token、画像、在庫一覧、プロンプトがログにないことを自動検査する

**受け入れ条件:**

- P0/P1 の脅威に緩和策と検証方法が割り当てられている
- 重大・高リスクの未対応脆弱性がない
- セキュリティ回帰テストが CI で再実行可能である

**依存関係:** Epic C-E1、C-02〜C-05  
**優先度:** P0  
**見積り:** M  
**担当:** `security-reviewer` / `cloud-developer`

## Epic C-E2: Bedrock による構造化食材解析

**目的:** 日本の家庭内食品画像から最大30件の編集可能な候補を、保持なし・国内Geo・機械検証可能な形式で生成する。

**完了条件:** C-07〜C-10 が完了し、Nova 2 Lite の結果が安全な schema に正規化され、条件不適合時は画像を送信せず手動入力へフォールバックする。

**優先度:** P0  
**見積り:** XL（配下 Issue 合計）  
**担当:** `ai-developer` / `security-reviewer`

### C-07 [Enabler] AI provider 境界と食材候補 schema を実装する

**目的:** モデル交換可能な TypeScript 境界と、AI出力を信頼しない厳密なドメイン変換を用意する。

**主要タスク:**

- [ ] `AiAnalysisProvider` 相当の interface と Nova adapter を定義する
- [ ] 最大30品目、名称1〜30文字、数量nullable・0〜100・小数2桁、21単位、推定根拠、要確認フラグの JSON Schema を定義する
- [ ] AI文字列の Unicode 正規化と、許可列挙値・長さ・件数・数値の再検証を実装する
- [ ] 500g→0.5kg、500ml→0.5L を含む重量・容量正規化を実装する
- [ ] 不明値を0で補完せず nullable / 要確認として返す

**受け入れ条件:**

- 不正な tool input は API 応答候補へ混入しない
- schema と業務規則の境界値テストが成功する
- AI provider を差し替えても HTTP / ドメイン契約が変わらない

**依存関係:** Epic C-E2、C-01、C-02  
**優先度:** P0  
**見積り:** M  
**担当:** `ai-developer`

### C-08 [Story] Nova 2 Lite Converse/toolChoice 推論を実装する

**目的:** `jp.amazon.nova-2-lite-v1:0` へ画像を直接渡し、強制 tool choice で構造化候補を取得する。

**主要タスク:**

- [ ] Bedrock Converse API と JP Geo inference profile を使用する adapter を実装する
- [ ] 特定 tool を `toolChoice` で強制し、画像・新規/更新処理・必要最小限の現在値をプロンプトへ渡す
- [ ] temperature 0、reasoning無効、max tokens設定を外部設定化する
- [ ] schema不適合時は同モデルで1回だけ再試行し、なお不正なら解析不能へ遷移する
- [ ] モデル名、レイテンシ、token数、エラー種別だけをメトリクス化する

**受け入れ条件:**

- 正常応答は C-07 の schema を満たす最大30候補として返る
- AIは増加・減少・置換を決定せず、数量の絶対値のみ返す
- 画像、プロンプト、tool input、解析結果がログや永続領域へ保存されない
- 再試行は schema不適合時の最大1回に制限される

**依存関係:** Epic C-E2、C-05、C-07、Bedrock model access  
**優先度:** P0  
**見積り:** M  
**担当:** `ai-developer`

### C-09 [Enabler] Bedrock ZDR/JP Geo ガードをフェイルクローズで実装する

**目的:** `data_retention_mode: none` と日本Geoの条件を満たさないモデルへ画像が送られることを構成・起動・デプロイ時に防止する。

**主要タスク:**

- [ ] 許可 model/profile を `jp.amazon.nova-2-lite-v1:0` 等の allowlist で固定し Global profile を拒否する
- [ ] 対象モデルの `allowed_modes` に `none` が含まれることをデプロイ時・起動時に検証する
- [ ] 実呼び出しで `data_retention_mode: none` を明示し、設定欠落時は Bedrock を呼ばない
- [ ] モデル仕様変更・検証API障害・不正profileを模擬したフェイルクローズテストを作成する
- [ ] Sonnet 5 は同じ保持なし条件を確認できるまで無効とする

**受け入れ条件:**

- ZDR確認不能、`none`非対応、Global profile 指定時は画像送信前に拒否される
- 東京起点かつJP Geo外への推論経路が IaC / 設定上存在しない
- 停止理由は画像を含まない運用アラートとクライアント向け手動移行エラーになる

**依存関係:** Epic C-E2、C-01、C-08、AWS側の保持モード確認手段  
**優先度:** P0  
**見積り:** M  
**担当:** `ai-developer` / `security-reviewer` / `devops`

### C-10 [Story] 品質フォールバックと解析結果の安全な失敗処理を実装する

**目的:** 遮蔽、不明値、schema不正、モデル障害を安全に分類し、条件を満たす場合だけ代替モデルを使用する。

**主要タスク:**

- [ ] schema再試行後の失敗、品目/数量不明、遮蔽、モデル障害の判定を実装する
- [ ] Sonnet 5 adapter を feature flag 配下に置き、ZDR/JP Geo等の許可条件を共通guardで強制する
- [ ] 保持なしを保証できない場合は代替モデルを呼ばず、部分候補または手動修正へ移行する
- [ ] フォールバック回数・原価をクォータ予約内で制限し、無限再試行を防止する

**受け入れ条件:**

- 未確認の代替モデルへ画像が送信されない
- 不明数量は0に変換されず、要確認として返る
- すべての失敗経路で画像と解析結果が永続化されず、在庫更新は発生しない

**依存関係:** Epic C-E2、C-07〜C-09  
**優先度:** P1  
**見積り:** M  
**担当:** `ai-developer` / `security-reviewer`

## Epic C-E3: クォータ、コスト防御、可観測性

**目的:** 匿名利用でも原子的に利用回数と費用を制御し、月間99.0%目標と画像非保持を運用で検証可能にする。

**完了条件:** C-11〜C-15 が完了し、ユーザー・全体・IP・予算の各上限が機能し、異常を画像や個人情報なしで検知できる。

**優先度:** P0  
**見積り:** XL（配下 Issue 合計）  
**担当:** `cloud-developer` / `devops` / `security-reviewer`

### C-11 [Story] DynamoDBでJSTユーザークォータを原子的に予約・返却する

**目的:** 1 UIDあたり2回/分、5回/日、30回/月を並行リクエスト下でも厳密に適用する。

**主要タスク:**

- [ ] 不可逆UIDキーとJSTの分・日・月bucketを用いたテーブル設計を実装する
- [ ] DynamoDB transaction / conditional write で3上限を原子的に予約する
- [ ] 入力不正・上限超過は未計上、AI予約後の基盤障害は補償更新で返却する
- [ ] TTLで不要counter/idempotency記録を最小期間後に削除する
- [ ] JST境界、月跨ぎ、同時実行、補償失敗のテストを作成する

**受け入れ条件:**

- 競合時も上限を超える予約が成立しない
- 拒否応答に上限種別と正しいJSTの再利用可能日時が含まれる
- AI基盤障害時の返却が冪等で、失敗時は監視通知される

**依存関係:** Epic C-E3、C-03、C-04  
**優先度:** P0  
**見積り:** M  
**担当:** `cloud-developer`

### C-12 [Story] 全体8,000回/月のハード上限と停止スイッチを実装する

**目的:** Budgetsの遅延に依存せず、全利用者合計の月間AI呼び出しを同期的に遮断する。

**主要タスク:**

- [ ] JST月bucketの全体counterをユーザーcounterと同じ予約トランザクションで更新する
- [ ] 月8,000回到達時にAIのみ停止する
- [ ] 設定可能な運用停止フラグを実装し、解除・変更を監査可能にする
- [ ] ユーザー上限との競合、月初リセット、基盤障害時返却をテストする

**受け入れ条件:**

- 8,001件目は Bedrock 前に拒否される
- AI停止中もAPIは明確な停止理由を返し、端末の手動機能に依存しない
- 上限値はデプロイなしで安全に変更でき、変更履歴が残る

**依存関係:** Epic C-E3、C-11  
**優先度:** P0  
**見積り:** S  
**担当:** `cloud-developer` / `devops`

### C-13 [Enabler] AWS WAF と API Gateway 防御を実装する

**目的:** IPごとの大量送信や異常payloadを入口で抑止し、Lambda・Bedrock費用を保護する。

**主要タスク:**

- [ ] WAFをAPI Gatewayへ関連付け、IPごと10回/分相当のrate-based ruleを構成する
- [ ] body size、許可HTTP method、既知の不正パターンを入口で制限する
- [ ] WAFログを機密情報・bodyなしで最小化し、保持期間を設定する
- [ ] 正常burstと攻撃trafficのテストを行い、誤遮断時の運用手順を作成する

**受け入れ条件:**

- IP上限超過がLambda/Bedrock到達前に遮断される
- WAFログから画像、token、在庫情報を復元できない
- WAFはDynamoDBの厳密なUID別上限の代替ではなく併用される

**依存関係:** Epic C-E3、C-01、C-02  
**優先度:** P0  
**見積り:** S  
**担当:** `cloud-developer` / `security-reviewer`

### C-14 [Enabler] AWS Budgets と100%時のAI自動停止を構築する

**目的:** 月50 USD予算を50/80/100%で通知し、100%到達時は同期上限とは別にAIを停止する。

**主要タスク:**

- [ ] 月次50 USD Budgetと50%通知、80%緊急通知を構成する
- [ ] 100%アクションを停止スイッチへ反映する安全な自動化を実装する
- [ ] 通知先、権限、復旧承認手順を定義する
- [ ] Budget更新遅延を前提に、C-12が主遮断手段であることをrunbookへ記載する

**受け入れ条件:**

- 各閾値のテスト通知が担当者へ届く
- 100%イベントでAI解析が停止し、手動機能には影響しない
- 自動化roleは停止スイッチ更新以外の不要権限を持たない

**依存関係:** Epic C-E3、C-12、通知先の決定  
**優先度:** P1  
**見積り:** S  
**担当:** `devops` / `security-reviewer`

### C-15 [Enabler] CloudWatch監視・SLO・画像非永続化監査を構築する

**目的:** エラー率、レイテンシ、AI利用量、削除/返却失敗、月99.0%目標を個人情報なしで監視する。

**主要タスク:**

- [ ] request ID、model ID、latency、token数、error categoryのみの構造化ログを定義する
- [ ] APIエラー率、p50/p95、呼出数、Bedrock失敗、クォータ補償失敗、WAF遮断、予算をdashboard化する
- [ ] 99.0%可用性、30秒目標、異常コストのalarmとrunbookを作成する
- [ ] S3、モデル呼び出しログ、X-Ray payload capture、DLQ payload等に画像が保存されない設定を検査する
- [ ] ログ保持期間、アクセス制御、削除を定義し、禁止フィールドの監査テストを実装する

**受け入れ条件:**

- request IDで追跡できる一方、画像・token・UID・prompt・在庫一覧・解析結果は記録されない
- 主要異常のテストalarmが通知され、runbookで一次対応できる
- IaC検査により画像の永続保存先とBedrock invocation loggingが無効であることを確認できる

**依存関係:** Epic C-E3、C-02、C-08、C-11〜C-14  
**優先度:** P0  
**見積り:** M  
**担当:** `devops` / `security-reviewer`

## Epic C-E4: AI品質評価と本番採用判定

**目的:** 日本の家庭内食品に対する品質、遅延、原価、保持要件を再現可能に評価し、本番モデル採用を証拠に基づき決定する。

**完了条件:** C-16〜C-18 が完了し、最低300画像の固定評価セットで全必須基準を満たすモデル・設定だけが本番有効化される。

**優先度:** P0  
**見積り:** XL（配下 Issue 合計。画像収集・アノテーション期間は別途調整）  
**担当:** `ai-developer` / `reviewer` / `manager`

### C-16 [Enabler] 再現可能なAI評価ハーネスを実装する

**目的:** 固定画像と正解データに対し、モデル/プロンプト変更を同条件で回帰評価できるようにする。

**主要タスク:**

- [ ] 品目検出F1、名称一致、可視個数完全一致/MAE、重量容量誤差、単位正解、schema適合、要確認再現率を計算する
- [ ] p50/p95レイテンシ、入力/出力token、1画像原価を集計する
- [ ] model/profile、prompt version、推論設定を結果へ紐付ける
- [ ] 評価画像や結果を本番ログへ混在させず、アクセス制限された評価環境でのみ扱う
- [ ] 同一設定で再実行した集計が再現するテストを作成する

**受け入れ条件:**

- 要件NFR-010の全指標を自動集計できる
- 個別失敗をrequest/画像IDで分析できるが、本番画像は入力できない
- model/prompt変更時にCIまたは承認workflowから同じ評価を起動できる

**依存関係:** Epic C-E4、C-07〜C-09  
**優先度:** P0  
**見積り:** M  
**担当:** `ai-developer` / `test-developer`

### C-17 [Story] 日本向け300画像評価セットと正解データを確定する

**目的:** 冷蔵庫全景から日本固有食材・全21単位までを含む、偏りを管理した品質判定用データを作る。

**主要タスク:**

- [ ] 冷蔵庫、棚、買い物品、包装/裸、透明/不透明容器、重なり、暗所、日本固有食材、全21単位の割付表を作る
- [ ] 同意・権利・個人情報を確認した最低300画像を収集し、評価専用のアクセス制御下で管理する
- [ ] 2名が独立アノテーションし、不一致を第三者が調停する
- [ ] 重量・体積で一意推定不能なケースと「不明」が正解となるケースを明示する
- [ ] データセットversionと変更履歴を固定する

**受け入れ条件:**

- 300枚以上、指定シナリオと全21単位が網羅される
- 全画像に独立2名の正解と調停結果がある
- 個人情報・権利・保持期間・アクセス権がレビュー済みである

**依存関係:** Epic C-E4、評価画像の収集・利用方針、人間のアノテーター2名以上  
**優先度:** P0  
**見積り:** L（実作業は収集/アノテーションbatchへ分割可）  
**担当:** `test-developer` / `domain-reviewer` / human reviewer

### C-18 [Story] Nova 2 Lite比較評価と本番採用ゲートを実施する

**目的:** Nova 2 Liteおよび許可された代替候補を比較し、品質・ZDR・JP Geo・原価の全条件を満たす構成を採用する。

**主要タスク:**

- [ ] max tokens 1,500〜2,500、prompt/tool schema候補を固定評価セットで比較する
- [ ] 再試行後schema 100%、単位95%以上、可視個数90%以上、品目F1 0.85以上、重大誤候補1%以下を判定する
- [ ] p95レイテンシ、1画像原価、8,000回/月時の予測費用を比較する
- [ ] ZDR `none`、JP Geo、ログ非保持条件を最終確認する
- [ ] 重量・体積のPoC結果から基準を確定し、未達時に「不明」へ送る性能を評価する
- [ ] 採用model/profile/prompt versionを承認記録とfeature flagへ反映する

**受け入れ条件:**

- 全必須品質基準と保持・地域条件を満たすまで本番AIが有効化されない
- 不採用理由、誤り分析、残余リスク、手動フォールバック方針が記録される
- 採用設定で月50 USD予算と通常30秒目標に対する妥当性が示される

**依存関係:** Epic C-E4、C-10、C-16、C-17、C-15  
**優先度:** P0  
**見積り:** M  
**担当:** `ai-developer` / `reviewer` / `manager`

## 推奨実施順序と並行化

1. C-01を先行する。
2. C-02、C-07、C-16を並行開始する。
3. API系はC-03/C-05を並行し、その後C-04/C-06へ進む。
4. AI系はC-07→C-08→C-09→C-10の順とし、C-09完了まで実画像を本番相当モデルへ送らない。
5. 制御系はC-03/C-04後にC-11、続いてC-12を実装し、C-13はC-02後に並行する。
6. C-14/C-15を整備してから統合負荷・障害試験を行う。
7. C-17は早期に人間作業を開始し、C-16/C-17完了後にC-18で本番採用を判定する。

## GitHub Project への配置案

- すべてのEpic/Story/Enablerを初期状態 `Backlog` で登録する。
- C-01を `Sprint Ready` とし、C-17の画像収集は人間依存を明示して早期着手する。
- ラベル例: `area:cloud`, `area:ai`, `type:epic|story|enabler`, `priority:P0`, `estimate:M`, `agent:cloud-developer`。
- Story/Enabler本文には必ず親Epicへの依存を記載し、前提Issueが完了するまで `Blocked` または `Backlog` とする。
- AI本番有効化はC-18の承認を必須gateとし、Issue完了のみで自動的に有効化しない。
