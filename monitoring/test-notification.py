#!/usr/bin/env python3
"""Send one controlled firing/resolved pair through the real Alertmanager receiver."""
import argparse
from datetime import datetime, timedelta, timezone
import json
import urllib.request


def send(base, started, ended):
    payload = [{'labels': {'alertname': 'GoldcastMonitoringTest', 'severity': 'warning', 'site': 'goldcast'},
                'annotations': {'summary': 'Kiểm thử đường cảnh báo GoldCast',
                                'description': 'Diễn tập monitoring; không thay đổi production.',
                                'runbook': 'https://github.com/duongtanminh1912/goldcast/blob/main/docs/monitoring.md#runbook'},
                'startsAt': started.isoformat(), 'endsAt': ended.isoformat()}]
    request = urllib.request.Request(base + '/api/v2/alerts', data=json.dumps(payload).encode(),
                                    headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(request, timeout=10) as response:
        if response.status != 200:
            raise RuntimeError('Alertmanager rejected test alert')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['fire', 'resolve'])
    parser.add_argument('--url', default='http://127.0.0.1:9093')
    args = parser.parse_args()
    now = datetime.now(timezone.utc)
    # Stable start time allows the resolve command to address the same alert.
    started = now.replace(hour=0, minute=0, second=0, microsecond=0)
    send(args.url, started, now + timedelta(minutes=5) if args.action == 'fire' else now - timedelta(seconds=1))
    print('Test alert ' + args.action + ' accepted by Alertmanager; check delivery metrics and Telegram.')
