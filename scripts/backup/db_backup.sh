#!/usr/bin/env bash
# [BACKUP_DB] Backup PORTÁTIL do Postgres de produção (independe do host: Railway, VPS BR, etc.).
#   pg_dump (formato custom)  ->  gpg AES-256 (simétrico)  ->  aws s3 cp (bucket S3-compatível privado)
# Roda no GitHub Actions (.github/workflows/db-backup.yml) OU como cron no próprio host do banco.
# Uso: db_backup.sh <timestamp-utc>   (ex.: db_backup.sh 20260621T050000Z)
#
# Env vars:
#   DATABASE_URL             postgres://user:senha@host:porta/db?sslmode=require   (OBRIGATÓRIO)
#   S3_BUCKET                nome do bucket                                        (OBRIGATÓRIO)
#   BACKUP_PASSPHRASE        senha de criptografia AES-256                         (OBRIGATÓRIO)
#   S3_ENDPOINT              endpoint S3 (R2/B2/etc.); vazio = AWS S3 padrão       (opcional)
#   AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_DEFAULT_REGION  credenciais do bucket
#   BACKUP_PREFIX            pasta no bucket por servidor (default "prod")         (opcional)
set -euo pipefail

: "${DATABASE_URL:?defina DATABASE_URL}"
: "${S3_BUCKET:?defina S3_BUCKET}"
: "${BACKUP_PASSPHRASE:?defina BACKUP_PASSPHRASE}"
PREFIX="${BACKUP_PREFIX:-prod}"
STAMP="${1:-}"
[ -n "$STAMP" ] || { echo "uso: db_backup.sh <timestamp-utc>"; exit 2; }

FILE="medieval-${PREFIX}-${STAMP}.dump"
ENC="${FILE}.gpg"
trap 'rm -f "$FILE" "$ENC"' EXIT

echo "[backup] pg_dump (custom, sem owners/privilégios) ..."
pg_dump --format=custom --no-owner --no-privileges --dbname="$DATABASE_URL" --file="$FILE"
echo "[backup] dump: $(du -h "$FILE" | cut -f1)"

echo "[backup] cifrando (AES-256) ..."
gpg --batch --yes --symmetric --cipher-algo AES256 \
    --passphrase "$BACKUP_PASSPHRASE" --output "$ENC" "$FILE"

EP=()
[ -n "${S3_ENDPOINT:-}" ] && EP=(--endpoint-url "$S3_ENDPOINT")
echo "[backup] upload -> s3://$S3_BUCKET/$PREFIX/$ENC ..."
aws s3 cp "$ENC" "s3://$S3_BUCKET/$PREFIX/$ENC" "${EP[@]}"

echo "[backup] OK: $PREFIX/$ENC"
