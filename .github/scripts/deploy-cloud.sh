#!/usr/bin/env bash
set -euo pipefail

environment="${1:-}"
assembly="${2:-}"
case "$environment" in stg|prod) ;; *) echo "usage: $0 <stg|prod>" >&2; exit 2 ;; esac
[[ -f "$assembly/manifest.json" ]] || { echo 'verified CDK assembly is missing' >&2; exit 2; }
bash .github/scripts/verify-aws-account.sh

required=(FIREBASE_PROJECT_ID FIREBASE_PROJECT_NUMBER FIREBASE_APP_IDS GOOGLE_WIF_AUDIENCE GOOGLE_SERVICE_ACCOUNT_EMAIL OPERATIONS_NOTIFICATION_EMAIL ACM_CERTIFICATE_ARN)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then echo "required deployment variable is missing: $name" >&2; exit 2; fi
done

case "$environment" in
  stg) foundation=FridgeManagerStgFoundation ;;
  prod) foundation=FridgeManagerProdFoundation ;;
esac
api_stack="${foundation}AnalysisApi"

npx cdk deploy "$foundation" --app "$assembly" \
  --exclusively \
  --rollback \
  --require-approval never

truststore_bucket="$(aws cloudformation describe-stacks --stack-name "$foundation" \
  --query "Stacks[0].Outputs[?OutputKey=='AopTruststoreBucketName'].OutputValue | [0]" --output text)"
[[ -n "$truststore_bucket" && "$truststore_bucket" != None ]] || {
  echo 'AOP truststore bucket is missing after foundation deployment' >&2
  exit 1
}
truststore_phase="${AOP_TRUSTSTORE_PHASE:-active}"
case "$truststore_phase" in active|pending) ;; *) echo 'AOP_TRUSTSTORE_PHASE must be active or pending' >&2; exit 2 ;; esac
manifest_key="aop/${environment}/${truststore_phase}-manifest.json"
aop_truststore_version="$(aws s3 cp "s3://${truststore_bucket}/${manifest_key}" - --no-progress 2>/dev/null | jq -er '.truststore_version' || true)"
[[ -n "$aop_truststore_version" && "$aop_truststore_version" != None ]] || {
  echo "AOP ${truststore_phase} truststore is not prepared; run the Provision Cloudflare AOP workflow for ${environment}" >&2
  exit 2
}

npx cdk deploy "$api_stack" --app "$assembly" \
# CloudFormationはROLLBACK_COMPLETEのstackを更新できない。quota待ちで失敗した
# stg API stackだけを再作成可能にし、foundationとprodは削除対象にしない。
if [[ "$environment" == stg ]]; then
  stack_status="$(aws cloudformation describe-stacks --stack-name "$api_stack" --query 'Stacks[0].StackStatus' --output text 2>/dev/null || true)"
  if [[ "$stack_status" == ROLLBACK_COMPLETE ]]; then
    aws cloudformation delete-stack --stack-name "$api_stack"
    aws cloudformation wait stack-delete-complete --stack-name "$api_stack"
  fi
fi
  --exclusively \
  --rollback \
  --require-approval never \
  --outputs-file "/tmp/fridge-manager-${environment}-outputs.json" \
  --parameters "${api_stack}:FirebaseProjectId=${FIREBASE_PROJECT_ID}" \
  --parameters "${api_stack}:FirebaseProjectNumber=${FIREBASE_PROJECT_NUMBER}" \
  --parameters "${api_stack}:FirebaseAppIds=${FIREBASE_APP_IDS}" \
  --parameters "${api_stack}:GoogleWifAudience=${GOOGLE_WIF_AUDIENCE}" \
  --parameters "${api_stack}:GoogleServiceAccountEmail=${GOOGLE_SERVICE_ACCOUNT_EMAIL}" \
  --parameters "${api_stack}:OperationsNotificationEmail=${OPERATIONS_NOTIFICATION_EMAIL}"
  --parameters "${api_stack}:OperationsNotificationEmail=${OPERATIONS_NOTIFICATION_EMAIL}" \
  --parameters "${api_stack}:AcmCertificateArn=${ACM_CERTIFICATE_ARN}" \
  --parameters "${api_stack}:AopTruststoreVersion=${aop_truststore_version}"
