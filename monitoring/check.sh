#!/bin/sh
# Run identically on a developer machine, GitHub Actions and Jenkins (Docker only).
set -eu
cd "$(dirname "$0")/.."
. monitoring/images.env
COPYFILE_DISABLE=1 tar -cf - monitoring |
  docker run --rm -i --entrypoint /bin/sh "$PROMETHEUS_IMAGE" -ec '
    mkdir /tmp/check; cd /tmp/check; tar -xf -;
    promtool check config --syntax-only monitoring/prometheus/prometheus.yml;
    promtool check rules monitoring/prometheus/alerts.yml monitoring/prometheus/slo.yml;
    cd monitoring/prometheus; promtool test rules rule-tests.json'
COPYFILE_DISABLE=1 tar -cf - monitoring/alertmanager |
  docker run --rm -i --entrypoint /bin/sh "$ALERTMANAGER_IMAGE" -ec '
    mkdir /tmp/check; cd /tmp/check; tar -xf -;
    sed "s/__CHAT_ID__/1/" monitoring/alertmanager/alertmanager.yml.template > /tmp/alertmanager.yml;
    amtool check-config /tmp/alertmanager.yml;
    route() { amtool config routes test --config.file=/tmp/alertmanager.yml "$@"; }
    expect() { want=$1; shift; got=$(route "$@" | tr -d " "); [ "$got" = "$want" ] || { echo "Route for $* is $got, want $want" >&2; exit 1; }; }
    expect deadman alertname=GoldcastWatchdog severity=heartbeat site=goldcast;
    expect telegram alertname=GoldcastWebsiteUnavailable severity=critical site=goldcast;
    expect telegram alertname=GoldcastSLOAPIFastBurn severity=critical site=goldcast slo=api;
    expect telegram alertname=GoldcastSLOAvailabilitySlowBurn severity=warning site=goldcast slo=availability;
    expect dashboard-only alertname=GoldcastMarketDataSynthetic severity=info site=goldcast;
    echo "Alert routing OK";
    # Inhibition is only evaluated by a running Alertmanager: start one with the real rules
    # and unreachable receivers, push alerts, and read back which ones would notify.
    mkdir /tmp/secrets; echo test > /tmp/secrets/telegram_bot_token; echo http://127.0.0.1:9/ > /tmp/secrets/deadman_ping_url;
    sed "s#/run/secrets/#/tmp/secrets/#" /tmp/alertmanager.yml > /tmp/am-run.yml;
    alertmanager --config.file=/tmp/am-run.yml --storage.path=/tmp/am --cluster.listen-address= \
      --web.listen-address=127.0.0.1:9093 > /tmp/am.log 2>&1 &
    am="--alertmanager.url=http://127.0.0.1:9093";
    i=0; until amtool $am config show > /dev/null 2>&1; do i=$((i+1)); [ $i -lt 50 ] || { cat /tmp/am.log >&2; exit 1; }; sleep 0.2; done;
    notifying() { wget -qO- "http://127.0.0.1:9093/api/v2/alerts?active=true&silenced=false&inhibited=false&unprocessed=false" |
      grep -o "\"alertname\":\"[A-Za-z]*\"" | cut -d\" -f4 | sort | tr "\n" " "; }
    for name in GoldcastBackendNotReady GoldcastBackendMetricsUnavailable GoldcastMarketMetricsStale GoldcastMacroDataStale GoldcastWebsiteUnavailable \
      GoldcastSLOAvailabilityFastBurn GoldcastSLOAvailabilitySlowBurn GoldcastSLOAPIFastBurn GoldcastSLOAPISlowBurn; do
      amtool $am alert add alertname=$name site=goldcast severity=warning series=DFII10; done;
    want="GoldcastBackendNotReady GoldcastWebsiteUnavailable ";
    i=0; while got=$(notifying); [ "$got" != "$want" ]; do
      i=$((i+1)); [ $i -lt 50 ] || { echo "Backend down notifies: $got, want: $want" >&2; exit 1; }; sleep 0.2; done;
    echo "Alert inhibition OK"'
