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
[[ -n "$truststore_bucket" && "$truststore_bucket" != None ]] || {
  echo 'AOP truststore bucket is missing; deploy the foundation stack first' >&2
  exit 2
}

work_dir="$(mktemp -d)"
cleanup() {
  find "$work_dir" -type f -exec shred -u {} + 2>/dev/null || rm -f "$work_dir"/*
  rmdir "$work_dir" 2>/dev/null || true
}
trap cleanup EXIT

cat >"$work_dir/leaf-ext.cnf" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=critical,clientAuth
subjectAltName=DNS:${hostname}
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid:always
EOF

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$work_dir/ca-key.pem" >/dev/null 2>&1
ca_common_name="fridge-manager-${target}-cloudflare-aop-ca-$(openssl rand -hex 12)"
openssl req -x509 -new -sha256 -days 90 -key "$work_dir/ca-key.pem" \
  -subj "/CN=${ca_common_name}" -out "$work_dir/ca.pem" \
  -addext 'basicConstraints=critical,CA:TRUE,pathlen:0' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' >/dev/null 2>&1
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$work_dir/leaf-key.pem" >/dev/null 2>&1
openssl req -new -sha256 -key "$work_dir/leaf-key.pem" \
  -subj "/CN=${hostname}" -out "$work_dir/leaf.csr" >/dev/null 2>&1
openssl x509 -req -sha256 -days 89 -in "$work_dir/leaf.csr" -CA "$work_dir/ca.pem" \
  -CAkey "$work_dir/ca-key.pem" -CAcreateserial -out "$work_dir/leaf.pem" \
  -extfile "$work_dir/leaf-ext.cnf" >/dev/null 2>&1
openssl verify -CAfile "$work_dir/ca.pem" "$work_dir/leaf.pem" >/dev/null

cat >"$work_dir/curl.conf" <<EOF
header = "Authorization: Bearer ${CLOUDFLARE_AOP_TOKEN}"
header = "Content-Type: application/json"
EOF
jq -n --rawfile certificate "$work_dir/leaf.pem" --rawfile private_key "$work_dir/leaf-key.pem" \
  '{certificate: $certificate, private_key: $private_key}' >"$work_dir/upload.json"
report_upload_error() {
  echo 'Cloudflare AOP certificate upload failed:' >&2
  jq -c '{success, errors: (.errors | map({code, message}))}' "$work_dir/upload-response.json" >&2 || \
    echo '{"success":false,"errors":[{"message":"Cloudflare returned a non-JSON response"}]}' >&2
}
if ! curl --fail-with-body --silent --show-error --config "$work_dir/curl.conf" \
  --data @"$work_dir/upload.json" \
  "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}/origin_tls_client_auth/hostnames/certificates" \
  >"$work_dir/upload-response.json"; then
  report_upload_error
  exit 1
fi
if ! certificate_id="$(jq -er 'select(.success == true) | .result.id' "$work_dir/upload-response.json")"; then
  report_upload_error
  exit 1
fi

active_manifest_key="aop/${target}/active-manifest.json"
if aws s3 cp "s3://${truststore_bucket}/${active_manifest_key}" "$work_dir/active-manifest.json" --no-progress >/dev/null 2>&1; then
  aws s3 cp "s3://${truststore_bucket}/aop/${target}/active-ca.pem" "$work_dir/active-ca.pem" --no-progress >/dev/null
  cat "$work_dir/active-ca.pem" "$work_dir/ca.pem" >"$work_dir/truststore.pem"
else
  cp "$work_dir/ca.pem" "$work_dir/truststore.pem"
fi
truststore_key="aop/${target}/truststore.pem"
aws s3 cp "$work_dir/ca.pem" "s3://${truststore_bucket}/aop/${target}/pending-ca.pem" --no-progress >/dev/null
aws s3 cp "$work_dir/truststore.pem" "s3://${truststore_bucket}/${truststore_key}" --no-progress >/dev/null
truststore_version="$(aws s3api head-object --bucket "$truststore_bucket" --key "$truststore_key" --query VersionId --output text)"
[[ -n "$truststore_version" && "$truststore_version" != None ]] || { echo 'truststore object version is missing' >&2; exit 1; }
jq -n --arg hostname "$hostname" --arg certificate_id "$certificate_id" --arg truststore_version "$truststore_version" \
  '{hostname: $hostname, certificate_id: $certificate_id, truststore_version: $truststore_version}' >"$work_dir/manifest.json"
aws s3 cp "$work_dir/manifest.json" "s3://${truststore_bucket}/aop/${target}/pending-manifest.json" --no-progress >/dev/null
printf 'truststore_version=%s\n' "$truststore_version" >>"${GITHUB_OUTPUT:-/dev/null}"
