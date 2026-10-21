#!/bin/sh
set -eu
: "${CI_PROJECT:?Set a unique CI_PROJECT}"
tested_release=$(sed -n 's/^RELEASE_ID=//p' release.env)
test -n "$tested_release" || { echo 'release.env needs RELEASE_ID' >&2; exit 1; }
printf '%s\n' "$tested_release" | grep -Eq '^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$' || { echo 'Invalid RELEASE_ID' >&2; exit 1; }
export MIGRATION_TARGET=latest CI_INGEST_ENABLED=true
compose() { docker compose --env-file release.env -p "$CI_PROJECT" -f docker-compose.ci.yml "$@"; }
cleanup() {
    result=$?
    trap - EXIT
    if [ "$result" -ne 0 ]; then compose logs --no-color --tail 150 || true; fi
    compose down --volumes --remove-orphans || exit 1
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
compose up -d --wait --wait-timeout 300 db backend frontend caddy
# Startup applies Flyway to an empty isolated DB. Assert its history explicitly.
compose exec -T db psql -U goldcast -d goldcast -v ON_ERROR_STOP=1 -c \
  "DO \$\$ BEGIN IF (SELECT count(*) FROM flyway_schema_history WHERE success) < 2 OR EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success) THEN RAISE EXCEPTION 'Flyway migration failed'; END IF; END \$\$;"
compose run --rm -T probe http://backend:8080 < scripts/smoke.mjs
compose run --rm -T probe http://caddy:8080 "$tested_release" < scripts/check-pages.mjs

# Separate upgrade scenario: V1 schema with existing rows in every domain table.
# Keep this baseline and its fixture tied to V1 when adding future migrations.
compose down --volumes --remove-orphans
(
    export MIGRATION_TARGET=1 CI_INGEST_ENABLED=false
    compose up -d --wait --wait-timeout 300 db backend
)
compose exec -T db psql -U goldcast -d goldcast -v ON_ERROR_STOP=1 < scripts/fixtures/upgrade-v1.sql
compose stop backend
# Same candidate image, now applying all migrations to the populated V1 database.
compose up -d --no-deps --force-recreate --wait --wait-timeout 300 backend frontend
compose up -d --wait --wait-timeout 60 caddy
compose exec -T db psql -U goldcast -d goldcast -v ON_ERROR_STOP=1 < scripts/fixtures/assert-upgrade.sql
compose run --rm -T probe http://backend:8080 < scripts/smoke.mjs
compose run --rm -T probe http://caddy:8080 "$tested_release" < scripts/check-pages.mjs
