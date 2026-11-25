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

# BuildKit cache is only cache: cap it instead of letting it grow without bound. Every
# builder keeps its own cache (a docker-container builder such as `multiarch` holds it in
# its own volume), so cap each one, not just the current. `buildx ls` also prints node
# names; those are not builders and are refused, which is harmless.
pruned=0
for builder in $(docker buildx ls --format '{{.Name}}' 2>/dev/null | awk '!seen[$0]++'); do
    if docker buildx prune -f --builder "$builder" --max-used-space "$cache_limit" >/dev/null 2>&1 ||
       docker buildx prune -f --builder "$builder" --keep-storage "$cache_limit" >/dev/null 2>&1; then
        echo "capped build cache of $builder at $cache_limit"
        pruned=1
    fi
done
test "$pruned" = 1 || echo 'buildx cache prune skipped'
exit 0
