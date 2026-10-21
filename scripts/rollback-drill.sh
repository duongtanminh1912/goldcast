#!/bin/sh
# An isolated, real-Docker rehearsal. No VPS, host ports or production volumes.
set -eu
operation=${1:?prepare|fail-g3|rollback|cleanup}
: "${WORKSPACE:?Run in a Jenkins workspace}"
: "${BUILD_NUMBER:?}"
case "$BUILD_NUMBER" in ''|*[!0-9]*) exit 2;; esac
export GOLDCAST_ROOT="$WORKSPACE/.rollback-drill-$BUILD_NUMBER"
job_key=$(printf '%s' "${JOB_NAME:-local}" | cksum | awk '{print $1}')
export GOLDCAST_COMPOSE_PROJECT="rollback-drill-$job_key-$BUILD_NUMBER"
export GOLDCAST_PROBE_NETWORK="${GOLDCAST_COMPOSE_PROJECT}_default"
export GOLDCAST_PUBLIC_BASE=http://frontend:3000
old="$GOLDCAST_ROOT/releases/baseline"
new="$GOLDCAST_ROOT/releases/candidate"
compose() {
    docker compose --env-file "${manifest_file:-$new/release.env}" -p "$GOLDCAST_COMPOSE_PROJECT" \
        -f "$new/docker-compose.prod.yml" "$@"
}
case "$operation" in
  prepare)
    test ! -e "$GOLDCAST_ROOT"
    mkdir -p "$old/scripts" "$new/scripts"
    touch "$GOLDCAST_ROOT/.env" "$old/runtime.env"
    cp "${GOLDCAST_BASELINE_MANIFEST:-scripts/fixtures/rollback-baseline.env}" "$old/release.env"
    cp release.env "$new/release.env"
    sh scripts/release.sh validate "$new"
    # The purpose is rollback across distinct application images, not merely config.
    test "$(sed -n '/_IMAGE=/p' "$old/release.env")" != "$(sed -n '/_IMAGE=/p' "$new/release.env")"
    for directory in "$old" "$new"; do
        cp docker-compose.ci.yml "$directory/docker-compose.prod.yml"
        cp scripts/check-pages.mjs "$directory/scripts/"
    done
    docker compose --env-file "$old/release.env" -p "$GOLDCAST_COMPOSE_PROJECT" \
        -f "$old/docker-compose.prod.yml" up -d --wait --wait-timeout 300 db backend frontend
    sh scripts/release.sh bootstrap "$old"
    sh scripts/release.sh deploy "$new" "${BUILD_URL:-isolated-drill}"
    # Keep the candidate migrations/data. Only corrupt frontend identity after deploy.
    sed 's/GOLDCAST_RELEASE_ID: .*/GOLDCAST_RELEASE_ID: deliberately-wrong-release/' \
        "$new/docker-compose.prod.yml" > "$new/compose.tmp"
    mv "$new/compose.tmp" "$new/docker-compose.prod.yml"
    compose up -d --no-deps --force-recreate frontend
    ;;
  fail-g3)
    # This MUST fail the Jenkins stage, exercising its real post.unsuccessful.
    sh scripts/release.sh verify "$new"
    echo 'ERROR: deliberate identity mismatch unexpectedly passed G3' >&2
    exit 2
    ;;
  rollback)
    test -f "$GOLDCAST_ROOT/.release-lock/owner" || exit 0
    sh scripts/release.sh rollback "$new"
    test "$(cat "$GOLDCAST_ROOT/.last-good-release")" = "$old"
    test "$(cat "$new/deploy-phase")" = rolled-back
    test ! -e "$GOLDCAST_ROOT/.release-lock"
    # Prove the old app can read the schema/data left by the candidate.
    docker compose --env-file "$old/release.env" -p "$GOLDCAST_COMPOSE_PROJECT" \
        -f "$old/docker-compose.prod.yml" run --rm -T probe http://backend:8080 < scripts/smoke.mjs
    printf '%s\n' 'PASS: rollback to distinct baseline digests, old API/pages on candidate schema, pointer and lock verified' > rollback-evidence.txt
    ;;
  cleanup)
    test -f "$new/docker-compose.prod.yml" || exit 0
    manifest_file="$old/release.env"
    compose logs --no-color --tail 100 > rollback-containers.log || true
    compose down --volumes --remove-orphans
    # Keep per-build state for diagnosis; normal workspace/build retention applies.
    ;;
  *) exit 2;;
esac
