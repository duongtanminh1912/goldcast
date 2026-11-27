#!/bin/sh
set -eu
: "${CI_PROJECT:?Set a unique CI_PROJECT}"
tested_release=$(sed -n 's/^RELEASE_ID=//p' release.env)
test -n "$tested_release" || { echo 'release.env needs RELEASE_ID' >&2; exit 1; }
printf '%s\n' "$tested_release" | grep -Eq '^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$' || { echo 'Invalid RELEASE_ID' >&2; exit 1; }
export MIGRATION_TARGET=latest CI_INGEST_ENABLED=true CI_LIVE_GOLD_ENABLED=true CI_LIVE_GOLD_POLL_SECONDS=1
compose() { docker compose --env-file release.env -p "$CI_PROJECT" -f docker-compose.ci.yml "$@"; }
check_live_gold() {
    # Minute collection must never revise the daily close series used by models.
    daily_before=$(compose exec -T db psql -U goldcast -d goldcast -tAc "SELECT md5(string_agg(ROW(id,instrument_id,observed_on,close_price,buy_price,sell_price,source)::text, ',' ORDER BY id)) FROM price_point")
    compose run --rm -T probe http://backend:8080 < scripts/check-live-gold.mjs
    compose exec -T db psql -U goldcast -d goldcast -v ON_ERROR_STOP=1 < scripts/fixtures/live-gold-retention.sql
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        remaining=$(compose exec -T db psql -U goldcast -d goldcast -tAc "SELECT count(*) FROM live_gold_quote WHERE source='retention-fixture'")
        test "$remaining" != 0 || break
        sleep 1
    done
    test "$remaining" = 0 || { echo 'Live quote retention did not run' >&2; return 1; }
    daily_after=$(compose exec -T db psql -U goldcast -d goldcast -tAc "SELECT md5(string_agg(ROW(id,instrument_id,observed_on,close_price,buy_price,sell_price,source)::text, ',' ORDER BY id)) FROM price_point")
    test "$daily_before" = "$daily_after" || { echo 'Minute collection changed daily forecasting data' >&2; return 1; }
    echo 'PASS live quote retention and daily history isolation'
}
assert_schema() {
    compose exec -T db psql -U goldcast -d goldcast -v ON_ERROR_STOP=1 -c \
      "DO \$\$ BEGIN IF (SELECT count(*) FROM flyway_schema_history WHERE success) < 3 OR EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success) OR to_regclass('live_gold_quote') IS NULL OR to_regclass('macro_observation') IS NULL OR to_regclass('idx_price_point_instrument_date') IS NOT NULL OR to_regclass('idx_forecast_point_run') IS NOT NULL OR to_regclass('price_point_unique') IS NULL THEN RAISE EXCEPTION 'Flyway migration failed'; END IF; END \$\$;"
}
previous_release() {
    # The rollback target is whatever production runs now (.last-good-release).
    if [ -n "${G2_PREVIOUS_RELEASE:-}" ]; then printf '%s\n' "$G2_PREVIOUS_RELEASE"; return; fi
    printf '%s\n' "const r = await fetch(process.argv[2] + '/api/release', { signal: AbortSignal.timeout(15000) }); if (!r.ok) process.exit(1); console.log((await r.json()).releaseId);" |
        compose run --rm -T probe "${G2_PRODUCTION_URL:-https://goldcast.click}" 2>/dev/null
}
check_rollback_compatibility() {
    # Rollback restores images, never the database. The release production runs now
    # must therefore still work on the schema this candidate has just migrated, with
    # that release's own smoke test and page check (a newer test could ask for
    # features it never had).
    previous=$(previous_release) || previous=
    if ! printf '%s\n' "$previous" | grep -Eq '^[0-9a-f]{40}-[0-9a-f]{12}-[0-9]+$'; then
        echo 'WARNING: production release unknown (site unreachable?); rollback compatibility NOT checked' >&2
        return 0
    fi
    if [ "$previous" = "$tested_release" ]; then echo 'Candidate already in production; rollback compatibility trivial'; return 0; fi
    commit=${previous%%-*}
    if ! git cat-file -e "$commit:scripts/smoke.mjs" 2>/dev/null; then
        echo "Rollback target $previous: commit $commit not in this checkout" >&2
        return 1
    fi
    echo "Rollback compatibility: $previous on the migrated schema"
    compose stop backend frontend
    (
        export RELEASE_ID="$previous"
        export BACKEND_IMAGE="ghcr.io/duongtanminh1912/goldcast-backend:$previous"
        export FRONTEND_IMAGE="ghcr.io/duongtanminh1912/goldcast-frontend:$previous"
        compose up -d --no-deps --force-recreate --wait --wait-timeout 300 backend frontend
        git show "$commit:scripts/smoke.mjs" | compose run --rm -T probe http://backend:8080
        if git cat-file -e "$commit:scripts/check-pages.mjs" 2>/dev/null; then
            git show "$commit:scripts/check-pages.mjs" | compose run --rm -T probe http://caddy:8080 "$previous"
        fi
    )
    echo "PASS rollback target $previous runs on the candidate schema"
}
latest_backup() {
    # Newest backup that backup.py verified (checksums and a test restore), read-only.
    compose run --rm --no-deps -T -v "${G2_BACKUP_VOLUME:-goldcast-private-backups}:/backups:ro" --entrypoint sh db -ec '
        for dir in $(ls -d /backups/goldcast/goldcast-backup-* 2>/dev/null | sort -r); do
            grep -q "\"database_restore_verified\": true" "$dir/verification.json" 2>/dev/null || continue
            (cd "$dir" && grep " database.dump\$" SHA256SUMS | sha256sum -c - >/dev/null 2>&1) || continue
            echo "$dir"; exit 0
        done
        exit 1' 2>/dev/null
}
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
assert_schema
compose run --rm -T probe http://backend:8080 < scripts/smoke.mjs
compose run --rm -T probe http://backend:9091 "$tested_release" < monitoring/check-metrics.mjs
check_live_gold
compose run --rm -T probe http://caddy:8080 "$tested_release" < scripts/check-pages.mjs
# A real browser for what only appears once JavaScript runs (live VND price, charts,
# client navigation, and the 500 page error.tsx renders after the __next_error__ shell).
compose run --rm -T browser http://caddy:8080 up < scripts/check-browser.mjs
compose stop backend
compose run --rm -T browser http://caddy:8080 down < scripts/check-browser.mjs
check_rollback_compatibility

# Separate upgrade scenario: V1 schema with existing rows in every domain table.
# Keep this baseline and its fixture tied to V1 when adding future migrations.
compose down --volumes --remove-orphans
(
    export MIGRATION_TARGET=1 CI_INGEST_ENABLED=false CI_LIVE_GOLD_ENABLED=false
    compose up -d --wait --wait-timeout 300 db backend
)
compose exec -T db psql -U goldcast -d goldcast -v ON_ERROR_STOP=1 < scripts/fixtures/upgrade-v1.sql
compose stop backend
# Same candidate image, now applying all migrations to the populated V1 database.
compose up -d --no-deps --force-recreate --wait --wait-timeout 300 backend frontend
compose up -d --wait --wait-timeout 60 caddy
compose exec -T db psql -U goldcast -d goldcast -v ON_ERROR_STOP=1 < scripts/fixtures/assert-upgrade.sql
compose run --rm -T probe http://backend:8080 < scripts/smoke.mjs
compose run --rm -T probe http://backend:9091 "$tested_release" < monitoring/check-metrics.mjs
check_live_gold
compose run --rm -T probe http://caddy:8080 "$tested_release" < scripts/check-pages.mjs

# Third scenario: the newest verified production backup, upgraded by the candidate.
# Real data has shapes no fixture anticipated (gaps, revisions, old forecast runs).
compose down --volumes --remove-orphans
if backup=$(latest_backup); then
    echo "Upgrade from production backup $backup"
    (
        # Ingest stays on with every external provider off (as in all of G2): it only
        # seeds series that are still empty, e.g. macro data a pre-V4 backup never had,
        # exactly what FRED would fill on the first production run after the upgrade.
        export CI_LIVE_GOLD_ENABLED=false
        compose up -d --wait --wait-timeout 120 db
        compose run --rm --no-deps -T -v "${G2_BACKUP_VOLUME:-goldcast-private-backups}:/backups:ro" --entrypoint cat db "$backup/database.dump" |
            compose exec -T db pg_restore --no-owner --no-acl --exit-on-error -U goldcast -d goldcast
        compose up -d --wait --wait-timeout 300 backend frontend caddy
    )
    assert_schema
    compose run --rm -T probe http://backend:8080 < scripts/smoke.mjs
    compose run --rm -T probe http://caddy:8080 "$tested_release" < scripts/check-pages.mjs
    echo 'PASS upgrade from the production backup'
else
    echo 'WARNING: no verified production backup on this machine; production-data upgrade NOT checked' >&2
fi
