#!/usr/bin/env bash
#
# nabu-backup.sh - daily PostgreSQL dump + config backup for a nabu-watch install.
#
# Called by the systemd timer (nabu-backup.timer). Can also be run by hand:
#   /opt/nabu-watch/deploy/backup/nabu-backup.sh
#
# What it stores:
#   - a pg_dump of the nabu database (gzip, integrity-checked)
#   - the config files listed in BACKUP_PATHS (env files, compose files, ...)
#   - retention: RETENTION_DAYS days (default 14)
#
# It does NOT back up the MinIO bucket (screenshots and generated files). To
# include it, mirror the bucket separately, e.g. with `mc mirror`, or snapshot
# the minio_data Docker volume.
#
# Configuration (environment variables, all optional):
#   BACKUP_ROOT      destination directory           (default /opt/backups/daily)
#   RETENTION_DAYS   how long to keep backups        (default 14)
#   PG_CONTAINER     Postgres container name         (default nabu-postgres)
#   PG_USER          Postgres user                   (default $DB_USER from .env, else nabu)
#   DB_NAME          database to dump                (default nabu_watch)
#   MIN_DUMP_BYTES   dumps smaller than this fail    (default 10000)
#   BACKUP_PATHS     space-separated files/dirs to archive
#                    (default: the install's .env, compose file and deploy dir)
#
set -euo pipefail

BACKUP_ROOT="${BACKUP_ROOT:-/opt/backups/daily}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
PG_CONTAINER="${PG_CONTAINER:-nabu-postgres}"
PG_USER="${PG_USER:-${DB_USER:-nabu}}"
DB_NAME="${DB_NAME:-nabu_watch}"
MIN_DUMP_BYTES="${MIN_DUMP_BYTES:-10000}"
INSTALL_DIR="${INSTALL_DIR:-/opt/nabu-watch}"
BACKUP_PATHS="${BACKUP_PATHS:-${INSTALL_DIR}/.env ${INSTALL_DIR}/docker-compose.prod.yml ${INSTALL_DIR}/docker-compose.override.yml ${INSTALL_DIR}/deploy /opt/nabu-worker/.env /etc/systemd/system/nabu-worker.service}"

STAMP="$(date -u +%Y%m%d-%H%M%S)"
DEST="${BACKUP_ROOT}/${STAMP}"

log()  { printf '[%s] %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"; }
die()  { printf '[%s] FAIL: %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*" >&2; exit 1; }

log "backup starting -> ${DEST}"
mkdir -p "${DEST}"

# -- 1. Postgres ---------------------------------------------------------------
docker exec "${PG_CONTAINER}" pg_isready -U "${PG_USER}" -d "${DB_NAME}" -q \
  || die "${PG_CONTAINER} is not responding, no backup taken"

# `set -o pipefail` makes a pg_dump failure fail the pipeline instead of being
# masked by gzip succeeding.
docker exec "${PG_CONTAINER}" pg_dump -U "${PG_USER}" --no-owner "${DB_NAME}" | gzip -9 > "${DEST}/${DB_NAME}.sql.gz"

SIZE=$(stat -c %s "${DEST}/${DB_NAME}.sql.gz")
[[ "${SIZE}" -ge "${MIN_DUMP_BYTES}" ]] \
  || die "dump is suspiciously small (${SIZE} bytes < ${MIN_DUMP_BYTES}), treating it as corrupt"

# Gzip integrity + the dump must contain at least one table: catches empty or truncated dumps.
gunzip -t "${DEST}/${DB_NAME}.sql.gz" || die "gzip integrity check failed"
TABLES=$(gunzip -c "${DEST}/${DB_NAME}.sql.gz" | grep -c '^CREATE TABLE' || true)
[[ "${TABLES}" -ge 1 ]] || die "dump contains no tables"
log "postgres OK - $(du -h "${DEST}/${DB_NAME}.sql.gz" | cut -f1), ${TABLES} tables"

# -- 2. Config files -----------------------------------------------------------
# Missing paths are skipped (--ignore-failed-read) so a partial install still backs up.
# shellcheck disable=SC2086
tar czf "${DEST}/configs.tar.gz" --ignore-failed-read ${BACKUP_PATHS} 2>/dev/null || true
tar tzf "${DEST}/configs.tar.gz" >/dev/null || die "configs.tar.gz is corrupt"
log "configs OK - $(du -h "${DEST}/configs.tar.gz" | cut -f1)"

# .env files contain secrets: readable by root only.
chmod 700 "${DEST}"; chmod 600 "${DEST}"/*

# -- 3. Prune old backups ------------------------------------------------------
# Only removes whole backup directories, and only after confirming that a fresh,
# healthy dump exists, so a broken night cannot wipe the whole history.
FRESH=$(find "${BACKUP_ROOT}" -maxdepth 2 -name "${DB_NAME}.sql.gz" -mtime -1 | wc -l)
if [[ "${FRESH}" -ge 1 ]]; then
  DELETED=$(find "${BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d -mtime "+${RETENTION_DAYS}" -print -exec rm -rf {} + | wc -l)
  log "cleanup: removed ${DELETED} old backup(s) (older than ${RETENTION_DAYS} days)"
else
  log "WARNING: no fresh backup found, cleanup SKIPPED"
fi

log "done - ${DEST} ($(du -sh "${DEST}" | cut -f1)), $(find "${BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d | wc -l) backups kept"
