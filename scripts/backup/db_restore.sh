#!/usr/bin/env bash
# [BACKUP_DB] Restore de um backup feito pelo db_backup.sh.
#   aws s3 cp (baixa)  ->  gpg --decrypt  ->  pg_restore num banco de DESTINO.
# Uso: db_restore.sh <arquivo.dump.gpg> <DATABASE_URL_destino>
#   ex.: db_restore.sh medieval-prod-20260621T050000Z.dump.gpg \
#          "postgres://user:senha@host:porta/medieval_restore?sslmode=require"
#
# ⚠️ Restaure num banco NOVO/VAZIO — não por cima do prod vivo às cegas.
# Env vars: S3_BUCKET, BACKUP_PASSPHRASE (obrig.); S3_ENDPOINT, AWS_*, BACKUP_PREFIX (opc.).
set -euo pipefail

KEY="${1:-}"; DEST="${2:-}"
[ -n "$KEY" ] && [ -n "$DEST" ] || { echo "uso: db_restore.sh <arquivo.dump.gpg> <DATABASE_URL_destino>"; exit 2; }
: "${S3_BUCKET:?defina S3_BUCKET}"
: "${BACKUP_PASSPHRASE:?defina BACKUP_PASSPHRASE}"
PREFIX="${BACKUP_PREFIX:-prod}"
DUMP="restore-$$.dump"
trap 'rm -f "$KEY" "$DUMP"' EXIT

EP=()
[ -n "${S3_ENDPOINT:-}" ] && EP=(--endpoint-url "$S3_ENDPOINT")
echo "[restore] baixando s3://$S3_BUCKET/$PREFIX/$KEY ..."
aws s3 cp "s3://$S3_BUCKET/$PREFIX/$KEY" "./$KEY" "${EP[@]}"

echo "[restore] decifrando ..."
gpg --batch --yes --decrypt --passphrase "$BACKUP_PASSPHRASE" --output "$DUMP" "$KEY"

echo "[restore] pg_restore -> destino ..."
pg_restore --clean --if-exists --no-owner --no-privileges --dbname="$DEST" "$DUMP"

echo "[restore] OK. Confira: psql \"\$DEST\" -c 'SELECT count(*) FROM players;'"
