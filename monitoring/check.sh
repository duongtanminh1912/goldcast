#!/bin/sh
# Run identically on a developer machine, GitHub Actions and Jenkins (Docker only).
set -eu
cd "$(dirname "$0")/.."
. monitoring/images.env
COPYFILE_DISABLE=1 tar -cf - monitoring |
  docker run --rm -i --entrypoint /bin/sh "$PROMETHEUS_IMAGE" -ec '
    mkdir /tmp/check; cd /tmp/check; tar -xf -;
    promtool check config --syntax-only monitoring/prometheus/prometheus.yml;
    promtool check rules monitoring/prometheus/alerts.yml;
    cd monitoring/prometheus; promtool test rules rule-tests.json'
COPYFILE_DISABLE=1 tar -cf - monitoring/alertmanager |
  docker run --rm -i --entrypoint /bin/sh "$ALERTMANAGER_IMAGE" -ec '
    mkdir /tmp/check; cd /tmp/check; tar -xf -;
    sed "s/__CHAT_ID__/1/" monitoring/alertmanager/alertmanager.yml.template > /tmp/alertmanager.yml;
    amtool check-config /tmp/alertmanager.yml'
