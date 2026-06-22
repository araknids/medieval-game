# Plano — Backup do Banco de Dados (produção) [BACKUP_DB]

> Status: **implementado (automação no repo)** — falta só você criar o bucket + setar os secrets (§6).
> Decisão do dono: backup **PORTÁTIL / host-agnóstico** (não amarrado ao Railway), porque o hosting
> pode mudar (Railway parece caro; quer lançar também num **servidor no Brasil**).

## 1. Objetivo

Garantir que **os dados de produção nunca sejam perdidos** — contas, personagens, inventário, guildas,
ranking, leilões, etc. Hoje **não havia nenhum backup**: se o Postgres sumir (erro humano, falha da
plataforma, conta deletada, cobrança cortada), **perde-se tudo**.

Regra **3-2-1**: 3 cópias, em 2 lugares, **1 fora do host do banco**. E a regra de ouro: **backup que
você nunca restaurou não é backup** → tem que **testar o restore**.

## 2. Por que portátil (e não a solução nativa do Railway)

O Railway tem backup nativo (snapshots; PITR de 7 dias no Pro), mas isso **amarra você ao Railway**.
Como o plano é poder **trocar de host** (inclusive pra um provedor BR), o backup tem que ser **agnóstico
de host**: usa só ferramenta-padrão do Postgres (`pg_dump`/`pg_restore`) e manda o resultado pra um
**bucket S3-compatível** (que existe em qualquer nuvem). Trocou de host? Só muda a connection string.

> 💡 **Enquanto estiver no Railway**, ligar o backup nativo dele também (1 clique, grátis) não custa nada
> e é uma camada extra. Mas o que **te segue pra qualquer host** é a automação portátil abaixo.

## 3. O que precisa ser protegido

| Ambiente | Banco | Backup? |
|----------|-------|---------|
| Dev (local) | H2 in-memory | ❌ descartável |
| Testes (CI) | Postgres efêmero (Testcontainers) | ❌ descartável |
| **Produção** | **PostgreSQL** (Railway hoje; talvez VPS BR amanhã) | ✅ **é isto que blindamos** |

Config: `application-prod.properties` usa `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD`, `ddl-auto=update`.
**[SERVIDORES]** 1 banco por deploy → **cada servidor de prod tem seu próprio backup** (use `BACKUP_PREFIX`).

## 4. Como funciona (o que está no repo)

Um **GitHub Actions** roda todo dia (e pode ser disparado na mão) e executa um script portátil:

```
pg_dump (formato custom)  →  gpg (AES-256, simétrico)  →  aws s3 cp  →  bucket S3 privado
```

- **`scripts/backup/db_backup.sh`** — faz o dump, **cifra** com `gpg` AES-256 e sobe pro bucket. Só
  depende de `DATABASE_URL` + credenciais do bucket (env vars) → roda contra **qualquer** Postgres.
- **`scripts/backup/db_restore.sh`** — baixa do bucket, **decifra** e restaura num banco de destino.
- **`.github/workflows/db-backup.yml`** — cron diário (05:00 UTC) + botão manual (`workflow_dispatch`);
  instala o `postgresql-client` certo e chama o script com os **secrets** do repo. Se os secrets ainda
  não existem, ele **pula sem erro** (não fica spammando falha).

Por que GitHub Actions: roda de graça (repo público, Actions ilimitado [project_repo_publico]), **mora
no repo** (versionado), e funciona **onde quer que** o banco esteja — só precisa que o `DATABASE_URL`
seja alcançável (com `sslmode=require`). Migrou pra um host BR? Atualiza o secret `PROD_DATABASE_URL` e
pronto. O mesmo script também serve pra rodar como cron no próprio host depois, se preferir.

> 🔒 **Repo é PÚBLICO** → o backup **nunca** vira artefato do Actions (seria público). Ele só vai pro
> **bucket privado** e ainda **cifrado** com uma senha que só você tem. Mesmo se o bucket vazar, o dump
> é inútil sem a `BACKUP_PASSPHRASE`.

## 5. Segurança & retenção

- **Criptografia:** AES-256 (`gpg --symmetric`). A `BACKUP_PASSPHRASE` fica nos secrets do GitHub **e**
  numa cópia **fora** dali (gerenciador de senhas) — perdeu a senha = não decifra o backup.
- **Bucket privado** (sem acesso público). Credenciais com escopo só desse bucket.
- **Retenção:** configure uma **regra de ciclo de vida (lifecycle)** no bucket (ex.: apagar objetos com
  > 30 dias). Todo provedor S3-compatível tem isso. (Mantém custo baixo sem lógica no script.)
- **DB exposto:** o Actions precisa alcançar o banco pela internet (string pública + **TLS obrigatório**).
  No Railway = TCP Proxy; num VPS = porta 5432 com `sslmode=require` e senha forte (idealmente firewall).
  *(Alternativa sem expor: rodar o `db_backup.sh` como cron no próprio host do banco — o script é o mesmo.)*

## 6. ✅ O que VOCÊ precisa fazer (setup único)

1. **Criar um bucket S3-compatível privado** (qualquer um serve — o script é agnóstico):
   - **Cloudflare R2** (10 GB grátis, sem egress) ou **Backblaze B2** (10 GB grátis), ou um **object
     storage BR** (ex.: Magalu Cloud) se quiser tudo no Brasil. Os dumps são pequenos (MB).
   - Gerar **Access Key ID + Secret** com acesso só a esse bucket; anotar o **endpoint** S3.
   - Criar uma **lifecycle rule**: expirar objetos com mais de ~30 dias.
2. **Setar os secrets** no GitHub (repo → Settings → Secrets and variables → Actions):
   | Secret | Valor |
   |--------|-------|
   | `PROD_DATABASE_URL` | `postgres://user:senha@host:porta/db?sslmode=require` |
   | `BACKUP_S3_ENDPOINT` | endpoint do bucket (ex.: `https://<acct>.r2.cloudflarestorage.com`) |
   | `BACKUP_S3_BUCKET` | nome do bucket |
   | `BACKUP_S3_ACCESS_KEY_ID` | access key |
   | `BACKUP_S3_SECRET_ACCESS_KEY` | secret key |
   | `BACKUP_S3_REGION` | região (`auto` no R2) |
   | `BACKUP_PASSPHRASE` | senha forte de criptografia (guarde uma cópia FORA do GitHub!) |
3. **Testar agora:** Actions → workflow **db-backup** → "Run workflow" (manual). Conferir que apareceu
   um `.dump.gpg` no bucket.
4. **Testar o RESTORE** (o passo que mais gente esquece): baixar o último backup e restaurar num banco
   vazio (§7). Sem isso, você não sabe se o backup presta.

## 7. Runbook de RESTORE

Precisa de `psql`/`pg_restore` (mesma major version do Postgres de prod) + `gpg` + `aws` cli.

```bash
# Variáveis do bucket no ambiente (mesmos valores dos secrets)
export S3_BUCKET=... S3_ENDPOINT=... AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... \
       AWS_DEFAULT_REGION=auto BACKUP_PASSPHRASE=... BACKUP_PREFIX=prod

# 1. Listar backups disponíveis
aws s3 ls "s3://$S3_BUCKET/$BACKUP_PREFIX/" --endpoint-url "$S3_ENDPOINT"

# 2. Restaurar num banco NOVO e vazio (NUNCA por cima do prod vivo às cegas)
bash scripts/backup/db_restore.sh medieval-prod-20260621T050000Z.dump.gpg \
     "postgres://user:senha@host:porta/medieval_restore?sslmode=require"

# 3. Conferir
psql "<DATABASE_URL do restore>" -c "SELECT count(*) FROM players;"

# 4. Só então apontar o app pro banco restaurado (trocar PG*/DATABASE_URL)
```

## 8. Cuidados específicos deste projeto

- 🧨 **Soft-wipe / instant-complete** (ligados em prod p/ teste solo) **zeram dados de propósito**. Tirar
  backup **ANTES** de qualquer wipe; no restore, não restaurar por cima de um wipe intencional. No prod
  "de verdade", **desligar** esses flags.
- 🗃️ **`ddl-auto=update`:** schema gerenciado pelo Hibernate. O `pg_dump` salva schema+dados juntos →
  o restore reconstrói tudo. (Flyway/migrations versionadas seriam um plus futuro, não pré-requisito.)
- 🌐 **[SERVIDORES]** multi-deploy: um backup por banco. Use `BACKUP_PREFIX` (ex.: `prod1`, `prod2`) e/ou
  um secret `PROD_DATABASE_URL` por servidor (duplicar o job no workflow).
- 💸 **Custo:** dumps de MB no tier grátis do R2/B2 ≈ **R$0**. O Actions é grátis no repo público.

## 9. Decisões (resolvidas)

- **Abordagem:** backup **portátil/host-agnóstico** (não amarra ao Railway) — o dono pode trocar p/ host BR.
- **Escopo:** **montar tudo agora** — automação no repo já; falta o setup manual do §6 (bucket + secrets).
- **Provedor do bucket:** em aberto — o script aceita **qualquer** S3-compatível; recomendação R2 (grátis,
  sem egress) ou um object storage BR se quiser tudo no Brasil.

## 10. Checklist

- [ ] Bucket S3 privado criado (+ lifecycle de retenção ~30d)
- [ ] 7 secrets setados no GitHub (§6)
- [ ] Workflow `db-backup` rodado manualmente → `.dump.gpg` apareceu no bucket
- [ ] **Restore testado** num banco vazio (teste de fogo)
- [ ] `BACKUP_PASSPHRASE` guardada FORA do GitHub
- [ ] (no Railway) backup nativo ligado como camada extra
- [ ] (futuro) repetido por servidor [SERVIDORES] + lembrete de teste trimestral
