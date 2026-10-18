#!/bin/sh
# Run once on VPS from the updated checkout, while existing production is healthy.
set -eu
umask 077
root=${GOLDCAST_ROOT:-/opt/goldcast}
source_dir=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
release="$root/releases/bootstrap-$(date +%Y%m%d%H%M%S)"
mode=${1:-}
case "$mode" in
  '') test ! -e "$root/.last-good-release";;
  --refresh) test -s "$root/.last-good-release";;
  *) echo 'Usage: bootstrap-release.sh [--refresh]' >&2; exit 2;;
esac
# Hold the same lock as Deploy for the entire capture, not only promotion;
# otherwise a concurrent release could produce a mixed snapshot.
mkdir "$root/.release-lock"
printf '%s\n' "$release" > "$root/.release-lock/owner"
cleanup_bootstrap_lock() {
    if [ -f "$root/.release-lock/owner" ] && [ "$(cat "$root/.release-lock/owner")" = "$release" ]; then
        rm "$root/.release-lock/owner" && rmdir "$root/.release-lock"
    fi
}
trap 'if ! cleanup_bootstrap_lock; then echo "WARNING: inspect residual bootstrap lock" >&2; fi' EXIT
# The release compose uses project goldcast; reject adoption of another project's
# containers/DB rather than silently selecting a new, empty production volume.
for service in db backend frontend caddy; do
    project=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "goldcast-$service")
    test "$project" = goldcast || { echo "Unexpected Compose project for $service: $project" >&2; exit 1; }
done
volume=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' goldcast-db)
test "$volume" = goldcast_goldcast-pgdata || { echo "Unexpected PostgreSQL volume: $volume" >&2; exit 1; }
mkdir -p "$release/deploy" "$release/scripts"
docker cp goldcast-caddy:/etc/caddy/Caddyfile "$release/deploy/Caddyfile"
cp "$source_dir/scripts/release.sh" "$source_dir/scripts/check-pages.mjs" "$release/scripts/"
docker inspect goldcast-db goldcast-backend goldcast-frontend goldcast-caddy > "$release/containers.json"
set --
for service in db backend frontend caddy; do
    image_id=$(docker inspect --format '{{.Image}}' "goldcast-$service")
    set -- "$@" "$image_id"
done
docker image inspect "$@" > "$release/images.json"
# Node is already present in the running frontend image; no Python installation,
# registry download, Docker socket or network is needed by the snapshot helper.
frontend_image=$(docker inspect --format '{{.Image}}' goldcast-frontend)
docker run --rm -i --network none --read-only --user "$(id -u):$(id -g)" \
  --mount "type=bind,src=$release,dst=/snapshot" --entrypoint node "$frontend_image" \
  --input-type=module - /snapshot < "$source_dir/scripts/snapshot-release.mjs"
rm "$release/containers.json" "$release/images.json"
sh "$release/scripts/release.sh" bootstrap "$release" "$mode" --lock-held
echo "Verified baseline: $release"
