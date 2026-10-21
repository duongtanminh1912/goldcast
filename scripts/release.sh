#!/bin/sh
# Runs on VPS. Each release contains its own compose, Caddyfile and digest manifest.
set -eu
umask 077
operation=${1:?validate|deploy|verify|rollback|bootstrap|status|recover}
release=${2:?release directory}
root=${GOLDCAST_ROOT:-/opt/goldcast}
# Alternate project/network/base are only for the isolated rollback rehearsal.
# The production root always uses the production project, host network and HTTPS.
project=goldcast
probe_network=host
public_base=https://goldcast.click
if [ "$root" != /opt/goldcast ]; then
    project=${GOLDCAST_COMPOSE_PROJECT:-goldcast}
    probe_network=${GOLDCAST_PROBE_NETWORK:-host}
    public_base=${GOLDCAST_PUBLIC_BASE:-https://goldcast.click}
fi
case "$release" in "$root"/releases/*) ;; *) echo 'Invalid release directory' >&2; exit 1;; esac
validate_manifest() {
    manifest_release=$1
    require_identity=1
    if [ "${2:-}" = --allow-legacy ] && [ -f "$manifest_release/legacy-local-origin" ]; then
        require_identity=0
    fi
    # Treat the manifest as data, never source it as shell code. Only the two
    # application repositories built by Jenkins may reach production.
    awk -v require_identity="$require_identity" '
        function fail(message) {
            print "Invalid release manifest: " message > "/dev/stderr"
            failed = 1
            exit 1
        }
        BEGIN {
            repository["BACKEND_IMAGE"] = "ghcr.io/duongtanminh1912/goldcast-backend"
            repository["FRONTEND_IMAGE"] = "ghcr.io/duongtanminh1912/goldcast-frontend"
        }
        /^[[:space:]]*(#.*)?$/ { next }
        {
            equals = index($0, "=")
            if (!equals) fail("malformed entry at line " NR)
            key = substr($0, 1, equals - 1)
            value = substr($0, equals + 1)
            if (key != "RELEASE_ID" && !(key in repository))
                fail("unknown key at line " NR)
            if (seen[key]++) fail("duplicate " key)
            if (key == "RELEASE_ID") {
                if (length(value) > 128 || value !~ /^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/)
                    fail("invalid RELEASE_ID")
            } else {
                prefix = repository[key] "@sha256:"
                digest = substr(value, length(prefix) + 1)
                if (index(value, prefix) != 1 || length(digest) != 64 || digest ~ /[^a-f0-9]/)
                    fail("invalid repository or SHA-256 digest for " key)
            }
        }
        END {
            if (failed) exit 1
            if (!seen["BACKEND_IMAGE"] || !seen["FRONTEND_IMAGE"])
                fail("both application image digests are required")
            if (require_identity && !seen["RELEASE_ID"])
                fail("RELEASE_ID is required")
        }
    ' "$manifest_release/release.env"
}
compose() {
    validate_manifest "$release" --allow-legacy
    runtime_env="$release/runtime.env"
    test -f "$runtime_env" || runtime_env="$root/.env"
    docker compose --env-file "$runtime_env" --env-file "$release/release.env" -p "$project" -f "$release/docker-compose.prod.yml" "$@"
}
freeze_env() {
    if [ ! -f "$release/runtime.env" ]; then
        cp "$root/.env" "$release/runtime.env"
        chmod 600 "$release/runtime.env"
    fi
}
verify() {
    validate_manifest "$release" --allow-legacy
    # Match the actual running image IDs to the exact registry digests passed by G2.
    for service in backend frontend; do
        case "$service" in backend) key=BACKEND_IMAGE;; frontend) key=FRONTEND_IMAGE;; esac
        expected=$(sed -n "s/^$key=//p" "$release/release.env")
        case "$expected" in *@sha256:*) ;; *) echo 'Digest required' >&2; return 1;; esac
        cid=$(compose ps -q "$service")
        test -n "$cid"
        test "$(docker inspect --format '{{.Image}}' "$cid")" = "$(docker image inspect --format '{{.Id}}' "$expected")"
    done
    compose exec -T backend wget -qO- http://localhost:8080/actuator/health/readiness | grep -q '"status":"UP"'
    # HTTPS certificate validation is enabled; requests exercise Caddy -> Next -> API.
    probe_image=$(sed -n 's/^FRONTEND_IMAGE=//p' "$release/release.env")
    public_release=$(sed -n 's/^RELEASE_ID=//p' "$release/release.env")
    if [ -z "$public_release" ]; then
        test -f "$release/legacy-local-origin" || { echo 'Missing public release identity' >&2; return 1; }
        public_release=--legacy-local-origin
    elif ! printf '%s\n' "$public_release" | grep -Eq '^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$'; then
        echo 'Invalid public release identity' >&2
        return 1
    fi
    docker run --rm -i --network "$probe_network" --entrypoint node "$probe_image" \
      --input-type=module - "$public_base" "$public_release" < "$release/scripts/check-pages.mjs"
}
set_phase() {
    printf '%s\n' "$1" > "$release/deploy-phase.tmp"
    mv "$release/deploy-phase.tmp" "$release/deploy-phase"
}
cleanup_lock() {
    rm "$root/.release-lock/owner" && rmdir "$root/.release-lock"
}
case "$operation" in
  validate)
    validate_manifest "$release"
    ;;
  deploy)
    validate_manifest "$release"
    test -s "$root/.last-good-release" || { echo 'Run bootstrap against the current healthy production first' >&2; exit 1; }
    previous=$(cat "$root/.last-good-release")
    case "$previous" in "$root"/releases/*) ;; *) echo 'Invalid previous release' >&2; exit 1;; esac
    if [ ! -f "$previous/runtime.env" ] || { ! grep -q '^RELEASE_ID=.' "$previous/release.env" && [ ! -f "$previous/legacy-local-origin" ]; }; then
        echo 'Baseline predates public identity/config snapshots; run bootstrap-release.sh --refresh against healthy production first' >&2
        exit 1
    fi
    validate_manifest "$previous" --allow-legacy
    mkdir "$root/.release-lock"
    printf '%s\n' "$release" > "$root/.release-lock/owner"
    date -u '+%Y-%m-%dT%H:%M:%SZ' > "$release/deploy-started-at"
    printf '%s\n' "${3:-unknown}" > "$release/deploy-build-url"
    set_phase preparing
    # Freeze the rollback target before touching production. Promotion may change
    # .last-good-release, but must never change this deployment's saved target.
    test "$(cat "$root/.last-good-release")" = "$previous"
    test "$previous" != "$release"
    printf '%s\n' "$previous" > "$release/previous-good-release.tmp"
    mv "$release/previous-good-release.tmp" "$release/previous-good-release"
    freeze_env
    compose pull
    set_phase deploying
    compose up -d --wait --wait-timeout 300
    set_phase deployed
    ;;
  verify)
    test "$(cat "$root/.release-lock/owner")" = "$release"
    set_phase verifying
    verify
    printf '%s\n' "$release" > "$root/.last-good-release.tmp"
    mv "$root/.last-good-release.tmp" "$root/.last-good-release"
    if ! set_phase promoted; then echo 'WARNING: promoted but phase metadata update failed' >&2; fi
    # Promotion above is the commit point. Cleanup failure is operational;
    # it must not turn a healthy, committed release into an application failure.
    if ! cleanup_lock; then
        echo "WARNING: release promoted; lock cleanup failed at $root/.release-lock; inspect and clean it before the next deploy" >&2
    fi
    ;;
  rollback)
    # A failure before acquiring the lock must never revert another deployment.
    test -f "$root/.release-lock/owner" || exit 0
    test "$(cat "$root/.release-lock/owner")" = "$release" || exit 0
    # Also handle a lost SSH response after successful promotion: Jenkins may
    # invoke rollback even though G3 has already committed the release.
    if [ "$(cat "$root/.last-good-release")" = "$release" ]; then
        echo 'Release already promoted after G3; skipping rollback'
        exit 0
    fi
    failed_release=$release
    set_phase rolling-back
    release=$(cat "$release/previous-good-release")
    case "$release" in "$root"/releases/*) ;; *) echo 'Invalid rollback target' >&2; exit 1;; esac
    compose up -d --wait --wait-timeout 300
    verify
    printf '%s\n' "$release" > "$root/.last-good-release.tmp"
    mv "$root/.last-good-release.tmp" "$root/.last-good-release"
    if ! cleanup_lock; then
        echo "WARNING: rollback verified; lock cleanup failed at $root/.release-lock" >&2
    fi
    printf '%s\n' rolled-back > "$failed_release/deploy-phase"
    echo "Rollback verified: $release"
    ;;
  status)
    printf 'release: %s\n' "$release"
    for field in deploy-started-at deploy-build-url deploy-phase previous-good-release; do
        printf '%s: ' "$field"
        if [ -f "$release/$field" ]; then cat "$release/$field"; else echo unknown; fi
    done
    printf 'last-good: '; cat "$root/.last-good-release"
    printf 'lock-owner: '
    if [ -f "$root/.release-lock/owner" ]; then cat "$root/.release-lock/owner"
    elif [ -d "$root/.release-lock" ]; then echo 'missing (partial lock; inspect before recovery)'
    else echo none; fi
    compose ps
    ;;
  recover)
    # An operator must first establish that Jenkins/SSH is no longer deploying.
    # Lock age alone never authorizes recovery or deletion.
    test "${3:-}" = --confirm-stopped || { echo 'Confirm the build and remote deploy have stopped; pass --confirm-stopped' >&2; exit 1; }
    test -d "$root/.release-lock"
    if [ -f "$root/.release-lock/owner" ]; then
        test "$(cat "$root/.release-lock/owner")" = "$release"
    else
        # Owner deletion succeeded but rmdir failed after promotion.
        test "$(cat "$root/.last-good-release")" = "$release"
    fi
    if [ "$(cat "$root/.last-good-release")" = "$release" ]; then
        verify
        if [ -f "$root/.release-lock/owner" ]; then rm "$root/.release-lock/owner"; fi
        rmdir "$root/.release-lock"
        echo 'Verified promoted release; cleared residual lock'
    else
        sh "$0" rollback "$release"
    fi
    ;;
  bootstrap)
    validate_manifest "$release" --allow-legacy
    # Explicit one-time adoption of existing production; never auto-bless on deploy.
    case "${3:-}" in
      '') test ! -e "$root/.last-good-release"; baseline_before=;;
      --refresh) baseline_before=$(cat "$root/.last-good-release"); test -n "$baseline_before";;
      *) echo 'Invalid bootstrap mode' >&2; exit 2;;
    esac
    if [ "${4:-}" = --lock-held ]; then
        test "$(cat "$root/.release-lock/owner")" = "$release"
    else
        mkdir "$root/.release-lock"
        printf '%s\n' "$release" > "$root/.release-lock/owner"
    fi
    # Bootstrap has not changed production; release its lock even if G3 fails.
    trap 'if ! cleanup_lock; then echo "WARNING: inspect residual bootstrap lock" >&2; fi' EXIT
    if [ -n "$baseline_before" ]; then
        test "$(cat "$root/.last-good-release")" = "$baseline_before"
    else
        test ! -e "$root/.last-good-release"
    fi
    freeze_env
    verify
    printf '%s\n' "$release" > "$root/.last-good-release.tmp"
    mv "$root/.last-good-release.tmp" "$root/.last-good-release"
    ;;
  *) exit 2;;
esac
