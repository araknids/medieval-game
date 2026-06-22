# Backup do banco (produção) [BACKUP_DB]

Backup **portátil** (host-agnóstico) do Postgres de prod: `pg_dump` → `gpg` AES-256 → bucket S3 privado.
Roda no GitHub Actions (`.github/workflows/db-backup.yml`, cron diário + manual) ou como cron no host.

- `db_backup.sh <timestamp-utc>` — dump + cifra + upload.
- `db_restore.sh <arquivo.dump.gpg> <DATABASE_URL_destino>` — baixa + decifra + restaura.

**Setup (criar bucket + setar secrets) e runbook de restore:** ver **`docs/PLANO_BACKUP_BANCO.md`**.
