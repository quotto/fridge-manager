#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

target="${TARGET:-}"
case "$target" in
  stg) hostname='fridge-manager-stg.wackwack.net' ;;
  prod) hostname='fridge-manager.wackwack.net' ;;
  *) echo 'TARGET must be stg or prod' >&2; exit 2 ;;
esac

for required in CLOUDFLARE_API_TOKEN CLOUDFLARE_AOP_TOKEN CLOUDFLARE_ZONE_ID AWS_REGION; do
  [[ -n "${!required:-}" ]] || { echo "required setting is missing: $required" >&2; exit 2; }
done
bash .github/scripts/verify-aws-account.sh

origin="$(aws apigateway get-domain-name --domain-name "$hostname" --query 'regionalDomainName' --output text)"
[[ -n "$origin" && "$origin" != None ]] || { echo 'API Gateway regional custom domain is missing' >&2; exit 2; }

work_dir="$(mktemp -d)"
cleanup() {
  rm -f "$work_dir"/*
  rmdir "$work_dir" 2>/dev/null || true
}
trap cleanup EXIT

cat >"$work_dir/curl.conf" <<EOF
header = "Authorization: Bearer ${CLOUDFLARE_API_TOKEN}"
header = "Content-Type: application/json"
EOF

curl --fail-with-body --silent --show-error --config "$work_dir/curl.conf" \
  "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/settings/ssl" >"$work_dir/ssl-setting.json"
jq -e 'select(.success == true and .result.value == "strict")' "$work_dir/ssl-setting.json" >/dev/null || {
  echo 'Cloudflare SSL/TLS encryption mode must be Full (strict) before proxying the API DNS record' >&2
  exit 2
}

curl --fail-with-body --silent --show-error --config "$work_dir/curl.conf" --get \
  --data-urlencode "name=${hostname}" \
  "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/dns_records" >"$work_dir/dns-records.json"

aws cloudformation describe-stacks --stack-name "FridgeManager${target^}Foundation" \
  --query "Stacks[0].Outputs[?OutputKey=='AopTruststoreBucketName'].OutputValue | [0]" --output text >"$work_dir/truststore-bucket.txt"
truststore_bucket="$(<"$work_dir/truststore-bucket.txt")"
[[ -n "$truststore_bucket" && "$truststore_bucket" != None ]] || { echo 'AOP truststore bucket is missing' >&2; exit 2; }
aws s3 cp "s3://${truststore_bucket}/aop/${target}/active-manifest.json" "$work_dir/active-manifest.json" --no-progress >/dev/null
active_certificate_id="$(jq -er '.certificate_id' "$work_dir/active-manifest.json")"
cat >"$work_dir/aop-curl.conf" <<EOF
header = "Authorization: Bearer ${CLOUDFLARE_AOP_TOKEN}"
header = "Content-Type: application/json"
EOF
curl --fail-with-body --silent --show-error --config "$work_dir/aop-curl.conf" \
  "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/origin_tls_client_auth/hostnames/${hostname}" >"$work_dir/aop-hostname.json"
jq -e --arg hostname "$hostname" --arg certificate_id "$active_certificate_id" \
  'select(.success == true) | .result | select(.hostname == $hostname and .cert_id == $certificate_id and .enabled == true)' \
  "$work_dir/aop-hostname.json" >/dev/null || { echo 'hostname AOP is not active with the active certificate; refusing to proxy DNS' >&2; exit 2; }

record_id="$(jq -er 'select(.success == true) | .result[0].id // empty' "$work_dir/dns-records.json" || true)"
if [[ -n "$record_id" ]]; then
  record_type="$(jq -er '.result[0].type' "$work_dir/dns-records.json")"
  [[ "$record_type" == CNAME ]] || { echo 'existing DNS record is not CNAME; refusing to replace it' >&2; exit 1; }
  jq -n --arg hostname "$hostname" --arg origin "$origin" \
    '{type:"CNAME", name:$hostname, content:$origin, proxied:true, ttl:1}' >"$work_dir/dns-record.json"
  curl --fail-with-body --silent --show-error --config "$work_dir/curl.conf" --request PUT \
    --data @"$work_dir/dns-record.json" \
    "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/dns_records/${record_id}" >"$work_dir/dns-write.json"
else
  jq -n --arg hostname "$hostname" --arg origin "$origin" \
    '{type:"CNAME", name:$hostname, content:$origin, proxied:true, ttl:1}' >"$work_dir/dns-record.json"
  curl --fail-with-body --silent --show-error --config "$work_dir/curl.conf" --request POST \
    --data @"$work_dir/dns-record.json" \
    "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/dns_records" >"$work_dir/dns-write.json"
fi
jq -e 'select(.success == true and .result.proxied == true)' "$work_dir/dns-write.json" >/dev/null

rate_rule_description='fridge-manager-analysis-api-ip-rate-limit-v1'
rate_expression='http.request.method eq "POST" and http.request.uri.path eq "/v1/analysis" and (http.host eq "fridge-manager-stg.wackwack.net" or http.host eq "fridge-manager.wackwack.net")'
jq -n --arg description "$rate_rule_description" --arg expression "$rate_expression" '
  {description: $description, expression: $expression,
   action: "block", action_parameters: {response: {content: "Too Many Requests", content_type: "text/plain", status_code: 429}},
   ratelimit: {characteristics: ["ip.src", "http.host"], period: 60, requests_per_period: 10, mitigation_timeout: 60}}' >"$work_dir/rate-rule.json"

entrypoint_url="https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/rulesets/phases/http_ratelimit/entrypoint"
entrypoint_status="$(curl --silent --show-error --config "$work_dir/curl.conf" --output "$work_dir/rate-entrypoint.json" --write-out '%{http_code}' "$entrypoint_url")"
case "$entrypoint_status" in
  200)
    jq -e 'select(.success == true)' "$work_dir/rate-entrypoint.json" >/dev/null
    ruleset_id="$(jq -er '.result.id' "$work_dir/rate-entrypoint.json")"
    existing_rule_id="$(jq -er --arg description "$rate_rule_description" '.result.rules[]? | select(.description == $description) | .id' "$work_dir/rate-entrypoint.json" | head -n 1 || true)"
    if [[ -n "$existing_rule_id" ]]; then
      curl --fail-with-body --silent --show-error --config "$work_dir/curl.conf" --request PUT \
        --data @"$work_dir/rate-rule.json" \
        "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/rulesets/${ruleset_id}/rules/${existing_rule_id}" >"$work_dir/rate-write.json"
    else
      curl --fail-with-body --silent --show-error --config "$work_dir/curl.conf" --request POST \
        --data @"$work_dir/rate-rule.json" \
        "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/rulesets/${ruleset_id}/rules" >"$work_dir/rate-write.json"
    fi
    ;;
  404)
    jq -n --slurpfile rule "$work_dir/rate-rule.json" \
      '{name: "fridge-manager-api-rate-limit", description: "Managed by fridge-manager repository", kind: "zone", phase: "http_ratelimit", rules: $rule}' \
      >"$work_dir/rate-ruleset.json"
    curl --fail-with-body --silent --show-error --config "$work_dir/curl.conf" --request POST \
      --data @"$work_dir/rate-ruleset.json" \
      "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/rulesets" >"$work_dir/rate-write.json"
    ;;
  *)
    echo "unable to read Cloudflare rate limiting entrypoint (HTTP ${entrypoint_status})" >&2
    exit 1
    ;;
esac
jq -e 'select(.success == true)' "$work_dir/rate-write.json" >/dev/null
