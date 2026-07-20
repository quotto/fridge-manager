# クラウド運用 Runbook

## 適用範囲と目標

解析 API のデプロイ、ロールバック、AI 緊急停止、主要アラームの初動を対象とする。手動停止は5分以内、予算SNS受信後の自動停止は1分以内、復旧は15分以内、CloudFormation rollbackは30分以内を目標とする。手動CRUDはクラウド障害中も継続できるため、障害対応でAndroidのローカル機能を停止しない。

Issue、対象commit SHA、GitHub Actions run URL、開始・終了時刻、判断、検証結果をIssueへ記録する。ログ本文、画像、token、Firebase UID、在庫情報、実account IDは記録しない。

## 初期設定

GitHub Environments `staging`、`production-plan`、`production` を作成し、`production` にrequired reviewerを設定する。各Environmentに次のvariableを登録する。

- `AWS_DEPLOY_ROLE_ARN`, `AWS_PLAN_ROLE_ARN`, `AWS_OPERATIONS_ROLE_ARN`, `PROMOTION_ARTIFACT_BUCKET`
- `FIREBASE_PROJECT_ID`, `FIREBASE_PROJECT_NUMBER`, `FIREBASE_APP_IDS`
- `GOOGLE_WIF_AUDIENCE`, `GOOGLE_SERVICE_ACCOUNT_EMAIL`
- Secret: `OPERATIONS_NOTIFICATION_EMAIL`

`OPERATIONS_NOTIFICATION_EMAIL` にはAWSアカウントで確認済みの既存予算通知先を再利用し、Repository variableやログへ値を出さない。アプリstackは要件固有の50/80/100%予算通知、Cost Anomaly、DLQ、API/Lambda障害をこの通知先へ送り、100%時は自動停止も実行する。SNSの購読確認メールが届いた場合は、各環境の購読を承認する。

長期AWS access keyは登録しない。GitHub OIDC roleのtrustは対象repository、`main`、対応Environmentの`sub`完全一致に限定する。plan roleはCloudFormation read、release prefixの`S3 GetObject`と必要なKMS Decryptだけを許可する。deploy roleは対象環境のCDK bootstrap roleへのassumeとrelease artifact書込だけを許可し、operations roleは対象環境のcontrol Lambda `InvokeFunction`、control tableの`GetItem`、対象stackの`DescribeStacks`と隔離rollback drill stackだけを許可する。stg/prodのroleとaccountは分離する。

Firebase検証LambdaからGoogle APIへは環境別Workload Identity Poolを使用し、サービスアカウント鍵を発行しない。providerのAWS account条件に加えて、`attribute.aws_role`をCloudFormation output `FirebaseAuthorizerRoleArn`に対応する固定role名へ完全一致させる。サービスアカウントには、そのroleのexact principalSetへ`roles/iam.workloadIdentityUser`だけを付与し、project全体・pool全体・AWS account全体を許可しない。`roles/iam.serviceAccountTokenCreator`は署名権限を含むため付与しない。サービスアカウント自身には`roles/firebaseappcheck.tokenVerifier`だけを付与する。

初回構築では、CDK synthで環境別role名を確認し、Google WIF providerとサービスアカウントbindingを先に作成してからAWS stackをdeployする。既存stackでrole名を変更する場合は、新旧roleを一時的にexact列挙し、新roleでtoken exchange成功を確認してから旧bindingを削除する。prefix wildcardによる切替は行わない。

## デプロイと昇格

1. `main` のCI成功を契機に `Deploy` workflowがCI検証済みSHAをcheckoutする。
2. CDK assemblyを一度だけsynthし、決定的tar archiveとSHA-256 digestを作成する。このdigestは転送時の同一性検証であり、暗号署名による発行者証明ではない。
3. checksum検証後の同一assemblyをstgへdeployする。`--rollback`によりCloudFormation失敗時は自動rollbackし、続行しない。
4. 未認証APIが401/403で拒否され、AI controlがenabledであることをsmoke testする。
5. read-onlyな`production-plan` roleで同一assemblyのprod diffをActions logへ出力する。
6. GitHub Environment `production` の承認者がSHA、stg結果、diffを確認する。
7. stgで検証したarchiveを再downloadしてchecksum検証し、同一assemblyをprodへdeployして同じsmoke testを行う。成功後はversioning・削除保護されたrelease bucketへchecksumとともにSHA単位で保存する。

GitHub artifact保持は90日であるため、prod稼働中の現行・既知良好assemblyはrelease bucketで保持する。bucketはversioning、暗号化、MFA DeleteまたはObject Lock、prodのデータ保持期間以上のlifecycleを設定する。sourceからの再synthをrollback artifactとして代用しない。

実accountのEnvironment/OIDC設定がない状態ではdeployを実行しない。認証付き正常系smokeと実停止演習もFirebase limited-use token・実account設定が整うまで留保し、CDK synth、workflow test、未認証negative smokeだけを自動化する。代替としてCloudFormation outputs、API Gateway integration、Lambda/control状態をsynth testとnegative smokeで検証する。

## ロールバック

deploy失敗時はCloudFormation Eventsを確認し、`UPDATE_ROLLBACK_COMPLETE`まで待つ。`UPDATE_ROLLBACK_FAILED`の場合は変更を重ねず、権限・quota・手動変更を特定してCloudFormationのcontinue-update-rollbackを実施する。

deploy成功後の不具合では、`Rollback Production Release` workflowへ既知良好SHAとIncident Issueを入力する。workflowはrelease bucketから保持済みassemblyを取得してchecksum検証し、prod diff提示と`production`承認を経て30分以内を目標に再deployする。`cdk rollback`やsourceからの再synthを復旧手段にしない。prod stackのtermination protectionとRETAINを解除しない。DynamoDB schemaや保存データを手作業で巻き戻さない。互換性のないschema変更はexpand/contract方式の別Issueとする。

このrollback対象はcodeとIaCであり、GitHub Environment variablesは現在値を使う。設定変更が原因の場合は、Issueに記録された既知良好値を二名確認でEnvironmentへ復元してからdiff・承認・再deployする。tokenや秘密値そのものをIssueへ記録しない。

stgの `CloudFormation Rollback Drill` workflowは、隔離した一時stackを意図的に失敗させ、CloudFormation rollbackによる削除を30分以内に確認する。アプリstackやデータには触れない。成功条件は一時stackが残らずworkflowが成功することとし、run URL、所要時間、対象Issueを証跡として記録する。

## 緊急のAI状態変更

`AI Control Change` workflowで対象、`stopped`または`enabled`、Issue番号、機微情報を含まない理由を入力する。prod操作には`production`承認が必要であり、workflowはcontrol Lambda経由の監査付き変更後にDynamoDBをconsistent readして状態を検証する。障害対応ではDrillではなくこのworkflowを用い、原因と予算・quotaが解消するまで復旧しない。

## AI停止・復旧演習

GitHub Actionsの `AI Control Drill` を手動起動する。prodは`production`承認を必須とする。`.github/scripts/drill-ai-control.sh` はcontrol Lambdaを介して `enabled=false` を設定・確認し、必ず `enabled=true` へ復旧・確認する。Lambdaが監査レコードを同一transactionへ保存し、CloudTrailのInvoke principalを実主体の正本とする。

停止確認または復旧に失敗した場合は再度復旧を試し、5分以内に復旧できなければP0としてoperations責任者へ連絡する。DynamoDBを直接更新しない。

## アラーム初動

| Alarm | 初動 | 復旧判断 |
| --- | --- | --- |
| `CoreAvailabilityAlarm` | API/Lambda errorsと直近deployを確認し、影響拡大時はAI停止 | core成功率が回復しsmoke成功 |
| `ProviderFailureAlarm` | Bedrock/JP Geo/data retention条件を確認し、継続時はAI停止 | provider成功と保持条件を確認 |
| `LatencyP95Alarm` | Lambda concurrency、Bedrock latency、timeoutを確認 | p95が閾値未満へ回復 |
| `AnalysisApi5xxAlarm` | API Gateway execution、integration/Lambda errorsを確認 | 未認証smokeと認証済み監視が成功 |
| `AuthorizerLambdaErrorAlarm` | Firebase公開鍵/WIF/App Check設定と外部障害を確認 | 無効token拒否と正常認証が成功 |
| `AnalysisLambdaErrorAlarm` | request IDでallowlistログを追跡。payloadは記録しない | errorが解消しsmoke成功 |
| `AnalysisLambdaThrottleAlarm` | reserved concurrencyと流量を確認。安易に上限を増やさない | throttle解消、cost/quota正常 |
| `BudgetStopDlqAlarm` | DLQ message metadataとcontrol状態を確認し、必要ならAI停止 | control停止と配送経路の復旧確認 |

予算100%または全体quota到達による停止は意図した安全動作である。予算期間、請求情報、全体quotaを確認するまで復旧しない。アラーム確認時もCloudWatchへ画像、候補、prompt、token、UID、在庫を出力しない。
