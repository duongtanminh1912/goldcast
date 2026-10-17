#!/bin/sh
# Runs on VPS. Each release contains its own compose, Caddyfile and digest manifest.
set -eu
operation=${1:?deploy|verify|rollback|bootstrap|status|recover}
release=${2:?release directory}
root=${GOLDCAST_ROOT:-/opt/goldcast}
case "$release" in "$root"/releases/*) ;; *) echo 'Invalid release directory' >&2; exit 1;; esac
compose() { docker compose --env-file "$root/.env" --env-file "$release/release.env" -p goldcast -f "$release/docker-compose.prod.yml" "$@"; }
verify() {
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
    docker run --rm -i --network host --entrypoint node "$probe_image" \
      --input-type=module - https://goldcast.click < "$release/scripts/check-pages.mjs"
}
set_phase() {
    printf '%s\n' "$1" > "$release/deploy-phase.tmp"
    mv "$release/deploy-phase.tmp" "$release/deploy-phase"
}
cleanup_lock() {
    rm "$root/.release-lock/owner" && rmdir "$root/.release-lock"
}
case "$operation" in
  deploy)
    test -s "$root/.last-good-release" || { echo 'Run bootstrap against the current healthy production first' >&2; exit 1; }
    mkdir "$root/.release-lock"
    printf '%s\n' "$release" > "$root/.release-lock/owner"
    date -u '+%Y-%m-%dT%H:%M:%SZ' > "$release/deploy-started-at"
    printf '%s\n' "${3:-unknown}" > "$release/deploy-build-url"
    set_phase preparing
    # Freeze the rollback target before touching production. Promotion may change
    # .last-good-release, but must never change this deployment's saved target.
    previous=$(cat "$root/.last-good-release")
    case "$previous" in "$root"/releases/*) ;; *) echo 'Invalid previous release' >&2; exit 1;; esac
    test "$previous" != "$release"
    printf '%s\n' "$previous" > "$release/previous-good-release.tmp"
    mv "$release/previous-good-release.tmp" "$release/previous-good-release"
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
    # Explicit one-time adoption of existing production; never auto-bless on deploy.
    test ! -e "$root/.last-good-release"
    mkdir "$root/.release-lock"
    printf '%s\n' "$release" > "$root/.release-lock/owner"
    # Bootstrap has not changed production; release its lock even if G3 fails.
    trap 'cleanup_lock || true' EXIT
    test ! -e "$root/.last-good-release"
    verify
    printf '%s\n' "$release" > "$root/.last-good-release.tmp"
    mv "$root/.last-good-release.tmp" "$root/.last-good-release"
    ;;
  *) exit 2;;
esac
