#!/bin/sh
# Run once on VPS from the updated checkout, while existing production is healthy.
set -eu
root=${GOLDCAST_ROOT:-/opt/goldcast}
source_dir=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
release="$root/releases/bootstrap-$(date +%Y%m%d%H%M%S)"
test ! -e "$root/.last-good-release"
# The release compose uses project goldcast; reject adoption of another project's
# containers/DB rather than silently selecting a new, empty production volume.
for service in db backend frontend caddy; do
    project=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "goldcast-$service")
    test "$project" = goldcast || { echo "Unexpected Compose project for $service: $project" >&2; exit 1; }
done
volume=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' goldcast-db)
test "$volume" = goldcast_goldcast-pgdata || { echo "Unexpected PostgreSQL volume: $volume" >&2; exit 1; }
mkdir -p "$release/deploy" "$release/scripts"
cp "$source_dir/docker-compose.prod.yml" "$release/"
docker cp goldcast-caddy:/etc/caddy/Caddyfile "$release/deploy/Caddyfile"
cp "$source_dir/scripts/release.sh" "$source_dir/scripts/check-pages.mjs" "$release/scripts/"
for service in backend frontend; do
    image_id=$(docker inspect --format '{{.Image}}' "goldcast-$service")
    digest=$(docker image inspect --format '{{index .RepoDigests 0}}' "$image_id")
    case "$digest" in *@sha256:*) ;; *) echo 'Current production needs registry digests' >&2; exit 1;; esac
    key=$(printf '%s' "$service" | tr '[:lower:]' '[:upper:]')
    printf '%s_IMAGE=%s\n' "$key" "$digest" >> "$release/release.env"
done
sh "$release/scripts/release.sh" bootstrap "$release"
echo "Verified baseline: $release"
