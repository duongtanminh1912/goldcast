#!/bin/sh
# Serialize remote operations even when SSH disconnects before the child exits.
# The kernel releases flock on process exit; the deployment owner lock remains
# until verification/rollback succeeds and is never removed merely because old.
set -eu
operation=${1:?deploy|verify|rollback}
release=${2:?release directory}
# Each budget covers its own steps with room to spare, so a slow but healthy VPS fails
# by the step's own timeout, not by being killed halfway. A rollback killed after
# `up` but before `verify` records nothing and leaves the lock for manual recovery.
#   deploy:   compose pull (two images) + up --wait-timeout 300
#   verify:   image/identity checks + check-pages (retries 502-504 while Caddy settles)
#   rollback: up --wait-timeout 300 (images already local) + the same verify
# scripts/tests/test_release_budgets.py keeps these consistent with release.sh and Jenkins.
case "$operation" in
  deploy) budget=600;;
  verify) budget=240;;
  rollback) budget=480;;
  *) exit 2;;
esac
case "$release" in /opt/goldcast/releases/*) ;; *) exit 2;; esac
lock_wait=390
kill_after=15
exec timeout $((lock_wait + budget + kill_after + 15)) flock -w "$lock_wait" /opt/goldcast/.release-operation.lock \
    timeout --kill-after="$kill_after" "$budget" sh "$release/scripts/release.sh" "$@"
