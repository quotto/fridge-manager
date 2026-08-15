#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

target="${TARGET:-}"
case "$target" in
  stg) hostname='fridge-manager-stg.wackwack.net'; foundation='FridgeManagerStgFoundation' ;;
  prod) hostname='fridge-manager.wackwack.net'; foundation='FridgeManagerProdFoundation' ;;
  *) echo 'TARGET must be stg or prod' >&2; exit 2 ;;
esac

for required in CLOUDFLARE_AOP_TOKEN CLOUDFLARE_ZONE_ID AWS_REGION; do
  [[ -n "${!required:-}" ]] || { echo "required setting is missing: $required" >&2; exit 2; }
done
bash .github/scripts/verify-aws-account.sh

truststore_bucket="$(aws cloudformation describe-stacks --stack-name "$foundation" \
  --query "Stacks[0].Outputs[?OutputKey=='AopTruststoreBucketName'].OutputValue | [0]" --output text)"
[[ -n "$truststore_bucket" && "$truststore_bucket" != None ]] || { echo 'AOP truststore bucket is missing' >&2; exit 2; }

work_dir="$(mktemp -d)"
cleanup() {
  rm -f "$work_dir"/*
  rmdir "$work_dir" 2>/dev/null || true
}
trap cleanup EXIT

aws s3 cp "s3://${truststore_bucket}/aop/${target}/pending-manifest.json" "$work_dir/manifest.json" --no-progress >/dev/null
certificate_id="$(jq -er '.certificate_id' "$work_dir/manifest.json")"
expected_version="$(jq -er '.truststore_version' "$work_dir/manifest.json")"
[[ "$(jq -er '.hostname' "$work_dir/manifest.json")" == "$hostname" ]] || { echo 'AOP manifest hostname mismatch' >&2; exit 1; }

deployed_version="$(aws apigateway get-domain-name --domain-name "$hostname" \
  --query 'mutualTlsAuthentication.truststoreVersion' --output text)"
[[ "$deployed_version" == "$expected_version" ]] || {
  echo 'API Gateway mTLS truststore is not deployed with the prepared AOP CA version' >&2
  exit 2
}

cat >"$work_dir/curl.conf" <<EOF
header = "Authorization: Bearer ${CLOUDFLARE_AOP_TOKEN}"
header = "Content-Type: application/json"
EOF
jq -n --arg hostname "$hostname" --arg cert_id "$certificate_id" \
  '{config: [{hostname: $hostname, cert_id: $cert_id, enabled: true}]}' >"$work_dir/association.json"
curl --fail-with-body --silent --show-error --config "$work_dir/curl.conf" \
  --request PUT --data @"$work_dir/association.json" \
  "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/origin_tls_client_auth/hostnames" \
  >"$work_dir/association-response.json"
jq -e 'select(.success == true)' "$work_dir/association-response.json" >/dev/null
certificate_active=false
for _attempt in {1..24}; do
  curl --fail-with-body --silent --show-error --config "$work_dir/curl.conf" \
    "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/origin_tls_client_auth/hostnames/${hostname}" \
    >"$work_dir/hostname-status.json"
  if jq -e --arg certificate_id "$certificate_id" \
    'select(.success == true) | .result | select(.enabled == true and .cert_id == $certificate_id and .cert_status == "active")' \
    "$work_dir/hostname-status.json" >/dev/null; then
    certificate_active=true
    break
  fi
  sleep 5
done
[[ "$certificate_active" == true ]] || { echo 'Cloudflare AOP certificate did not become active before timeout' >&2; exit 1; }
aws s3 cp "$work_dir/manifest.json" "s3://${truststore_bucket}/aop/${target}/active-manifest.json" --no-progress >/dev/null
aws s3 cp "s3://${truststore_bucket}/aop/${target}/pending-ca.pem" "s3://${truststore_bucket}/aop/${target}/active-ca.pem" --no-progress >/dev/null
