#!/bin/sh
# Use the running backend's token and internal port, including custom host ports.
set -eu
exec docker compose exec -T backend sh -ec '
    if [ -z "${APP_ADMIN_TOKEN:-}" ]; then
        echo "Set APP_ADMIN_TOKEN in .env, then recreate backend with docker compose up -d backend" >&2
        exit 1
    fi
    exec wget -qO- --header "X-Admin-Token: $APP_ADMIN_TOKEN" \
        --post-data="" http://127.0.0.1:8080/api/v1/admin/ingest
'
