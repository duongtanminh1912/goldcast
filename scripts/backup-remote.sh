#!/bin/sh
set -eu
umask 077
root=/opt/goldcast
# Run by backup.py; stdout is reserved for the private backup directory.
mkdir -p "$root/backups"
backup=$(mktemp -d "$root/backups/offsite-$(date -u +%Y%m%dT%H%M%SZ).XXXXXX")
locked=0
unlock() {
    if [ "$locked" = 1 ]; then
        if [ -f "$root/.release-lock/owner" ] && [ "$(cat "$root/.release-lock/owner")" = "$backup" ]; then
            rm "$root/.release-lock/owner"
            rmdir "$root/.release-lock"
        elif [ ! -e "$root/.release-lock/owner" ]; then
            rmdir "$root/.release-lock"
        else
            echo 'Backup lock owner changed; inspect before proceeding' >&2
            return 1
        fi
        locked=0
    fi
}
trap unlock EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir "$root/.release-lock"
locked=1
printf '%s\n' "$backup" > "$root/.release-lock/owner"
release=$(cat "$root/.last-good-release")
case "$release" in "$root"/releases/*) ;; *) echo 'Invalid good release path' >&2; exit 1;; esac
test "$(readlink -f "$release")" = "$release"
test "$(cat "$release/deploy-phase")" = promoted
test -s "$release/runtime.env"
sh "$release/scripts/release.sh" status "$release" > "$backup/status.log"
for service in backend frontend; do
    case "$service" in backend) key=BACKEND_IMAGE;; frontend) key=FRONTEND_IMAGE;; esac
    image=$(sed -n "s/^$key=//p" "$release/release.env")
    actual=$(docker inspect --format '{{.Image}}' "goldcast-$service")
    test "$actual" = "$(docker image inspect --format '{{.Id}}' "$image")"
done
cp "$release/release.env" "$backup/release.env"
set -- release.env docker-compose.prod.yml deploy/Caddyfile scripts/release.sh scripts/check-pages.mjs runtime.env
if [ -f "$release/scripts/remote-release.sh" ]; then set -- "$@" scripts/remote-release.sh; fi
if [ -f "$release/scripts/retention.py" ]; then set -- "$@" scripts/retention.py; fi
if [ -f "$release/legacy-local-origin" ]; then set -- "$@" legacy-local-origin; fi
tar -cf "$backup/release.tar" -C "$release" "$@"
if [ -f "$root/.env" ]; then cp "$root/.env" "$backup/production.env"; fi
docker exec goldcast-db sh -ec 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$backup/database.dump"
test -s "$backup/database.dump"
curl --fail --silent --show-error --max-time 20 https://goldcast.click/api/release > "$backup/public-release.json"
{
    printf 'backup_utc=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    printf 'release_directory=%s\n' "$release"
    printf 'release_id=%s\n' "$(sed -n 's/^RELEASE_ID=//p' "$backup/release.env")"
    printf 'validator_sha256=%s\n' "$(sha256sum "$release/scripts/release.sh" | awk '{print $1}')"
    printf 'dump_version=%s\n' "$(docker exec goldcast-db pg_dump --version)"
    printf 'database_volume=%s\n' "$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' goldcast-db)"
    printf 'database_image_id=%s\n' "$(docker inspect --format '{{.Image}}' goldcast-db)"
} > "$backup/metadata.txt"
test "$(cat "$root/.last-good-release")" = "$release"
unlock
# Registry checks do not need to hold up deployments after the snapshot.
for service in backend frontend; do
    case "$service" in backend) key=BACKEND_IMAGE;; frontend) key=FRONTEND_IMAGE;; esac
    image=$(sed -n "s/^$key=//p" "$backup/release.env")
    if timeout 45 docker manifest inspect "$image" > /dev/null 2> "$backup/registry-$service.error"; then
        printf 'PASS %s %s\n' "$service" "$image" >> "$backup/registry-check.log"
        rm "$backup/registry-$service.error"
    else
        printf 'FAIL %s %s\n' "$service" "$image" >> "$backup/registry-check.log"
    fi
done
(
    cd "$backup"
    set -- release.tar release.env database.dump metadata.txt public-release.json status.log registry-check.log
    if [ -f production.env ]; then set -- "$@" production.env; fi
    for file in registry-backend.error registry-frontend.error; do
        if [ -f "$file" ]; then set -- "$@" "$file"; fi
    done
    sha256sum "$@" > SHA256SUMS
)
printf '%s\n' "$backup"
