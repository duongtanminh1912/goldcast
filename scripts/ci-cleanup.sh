#!/bin/sh
# Jenkins, after every build: each build tags two new goldcast images on this machine and
# grows the BuildKit cache; nothing ever removed them (74 images, ~25 GB with the cache).
# Keep the newest few for diagnosis and drop the rest by name, never with -f: an image
# a container still uses (another job's G2) is refused and stays. Never fails the build.
set -u
registry=${1:?registry}
keep=${2:-3}
cache_limit=${3:-10gb}
case "$keep" in ''|*[!0-9]*) echo 'keep must be a number' >&2; exit 2;; esac

for service in backend frontend; do
    repository="$registry/goldcast-$service"
    docker image ls "$repository" --format '{{.CreatedAt}}|{{.ID}}' |
        sort -r | cut -d'|' -f2 | awk '!seen[$0]++' | tail -n +"$((keep + 1))" |
        while IFS= read -r id; do
            references=$(docker image inspect --format '{{range .RepoTags}}{{.}} {{end}}{{range .RepoDigests}}{{.}} {{end}}' "$id") || continue
            # Only names in this repository: a shared base layer of another project stays.
            mine=
            for reference in $references; do
                case "$reference" in "$repository":*|"$repository"@*) mine="$mine $reference";; esac
            done
            test -n "$mine" || continue
            # One name at a time: once the last name goes the image is deleted, and the
            # remaining names then fail with "no such image" although nothing is wrong.
            # Judge by whether the image still exists, not by the exit codes.
            for reference in $mine; do
                docker image rm "$reference" >/dev/null 2>&1 || true
            done
            if docker image inspect "$id" >/dev/null 2>&1; then
                echo "kept $repository $id (in use or named by another project)"
            else
                echo "removed $repository $id"
            fi
        done
done

# BuildKit cache is only cache: cap it instead of letting it grow without bound.
docker buildx prune -f --max-used-space "$cache_limit" >/dev/null 2>&1 ||
    docker buildx prune -f --keep-storage "$cache_limit" >/dev/null 2>&1 ||
    echo 'buildx cache prune skipped'
exit 0
