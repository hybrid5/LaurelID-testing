#!/usr/bin/env bash
set -euo pipefail

TARGET_DIR="app/src/main/assets/trust/iaca"
mkdir -p "${TARGET_DIR}"

TMP_DIR=$(mktemp -d)
trap 'rm -rf "${TMP_DIR}"' EXIT

CERT_SUBJECT=${CERT_SUBJECT:-/CN=CI Dummy IACA Root}
DAYS=${DAYS:-2}
ANCHOR_NAME=${ANCHOR_NAME:-CI_Dummy_IACA_Root.cer}

openssl req -x509 -newkey rsa:2048 -keyout "${TMP_DIR}/dummy.key" \
  -out "${TMP_DIR}/dummy.pem" -days "${DAYS}" -nodes -subj "${CERT_SUBJECT}" >/dev/null 2>&1
openssl x509 -outform DER -in "${TMP_DIR}/dummy.pem" -out "${TARGET_DIR}/${ANCHOR_NAME}"

sha256sum "${TARGET_DIR}/${ANCHOR_NAME}"
