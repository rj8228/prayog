#!/bin/sh
# Runs once, when the PostgreSQL data volume is empty. Keycloak gets its own database and user, so it can never touch
# Prayog's tables (and Prayog's services never touch Keycloak's).
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set kc_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
CREATE USER keycloak WITH PASSWORD :'kc_password';
CREATE DATABASE keycloak OWNER keycloak;
SQL
