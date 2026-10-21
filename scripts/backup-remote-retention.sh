#!/bin/sh
# Called only AFTER offsite transfer, checksum and restore verification succeed.
set -eu
umask 077
root=/opt/goldcast/backups
verified=${1:?verified remote snapshot}
case "$verified" in "$root"/offsite-*) ;; *) exit 2;; esac
test ! -L "$verified"
test "$(dirname "$(readlink -f "$verified")")" = "$root"
date -u '+%Y-%m-%dT%H:%M:%SZ' > "$verified/offsite-restore-verified"
# Failed/untransferred snapshots are never removed automatically. Keep at least
# fourteen completed transfers and seven days before deleting remote copies.
find "$root" -mindepth 1 -maxdepth 1 -type d -name 'offsite-*' \
    -exec test -f '{}/offsite-restore-verified' \; -print |
    sort -r | awk 'NR > 14' |
    while IFS= read -r expired; do
        case "$expired" in "$root"/offsite-*) ;; *) exit 2;; esac
        test "$(dirname "$(readlink -f "$expired")")" = "$root"
        if [ -n "$(find "$expired" -maxdepth 0 -mtime +7 -print)" ]; then
            rm -rf -- "$expired"
        fi
    done
