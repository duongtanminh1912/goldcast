#!/bin/sh
# Serialize remote operations even when SSH disconnects before the child exits.
# The kernel releases flock on process exit; the deployment owner lock remains
# until verification/rollback succeeds and is never removed merely because old.
set -eu
operation=${1:?deploy|verify|rollback}
release=${2:?release directory}
case "$operation" in deploy|verify|rollback) ;; *) exit 2;; esac
case "$release" in /opt/goldcast/releases/*) ;; *) exit 2;; esac
exec timeout 780 flock -w 390 /opt/goldcast/.release-operation.lock \
    timeout --kill-after=15 360 sh "$release/scripts/release.sh" "$@"
