#!/bin/sh
set -eu
: "${MONITORING_SSH_HOST:?}"
exec ssh -NT -i /run/secrets/ssh_key -o IdentitiesOnly=yes \
  -o BatchMode=yes -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile=/run/secrets/known_hosts \
  -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -o ExitOnForwardFailure=yes \
  -L 0.0.0.0:19091:127.0.0.1:9091 \
  -L 0.0.0.0:19100:127.0.0.1:9100 "$MONITORING_SSH_HOST"
