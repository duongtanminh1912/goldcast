#!/usr/bin/env python3
"""Run central monitoring from a durable snapshot, independent of checkout/branch changes."""
import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import shutil
import subprocess
import uuid


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--secrets-dir', type=Path, default=Path.home() / '.config/goldcast-monitoring')
    parser.add_argument('--services', nargs='+', choices=['alertmanager', 'prometheus', 'grafana',
                                                       'blackbox', 'jenkins-exporter', 'metrics-tunnel'])
    args = parser.parse_args()
    config = args.secrets_dir.expanduser().resolve() / 'runtime.env'
    if not config.is_file():
        raise SystemExit('Run monitoring/configure.py first.')
    root = Path.home() / '.local/share/goldcast-monitoring'
    name = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    snapshot = root / 'releases' / name
    shutil.copytree(Path(__file__).resolve().parent, snapshot,
                    ignore=shutil.ignore_patterns('__pycache__'))
    command = ['docker', 'compose', '--env-file', str(snapshot / 'images.env'),
               '--env-file', str(config), '-f', str(snapshot / 'compose.yml')]
    subprocess.run(command + ['config', '--quiet'], check=True)
    subprocess.run(command + ['up', '-d', '--build'] + (args.services or []), check=True)
    temporary = root / ('current-' + uuid.uuid4().hex)
    temporary.symlink_to(snapshot)
    os.replace(temporary, root / 'current')
    print('Monitoring snapshot: ' + str(snapshot))
