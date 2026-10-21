#!/usr/bin/env python3
"""Install a per-user AC-only sleep assertion, without changing global power settings."""
import os
from pathlib import Path
import plistlib
import subprocess
import sys


if __name__ == '__main__':
    if sys.platform != 'darwin':
        raise SystemExit('This helper is for the Mac monitoring host only.')
    script = Path.home() / '.local/share/goldcast-monitoring/current/macos-keep-awake.sh'
    if not script.is_file():
        raise SystemExit('Run monitoring/start.py first to create a durable snapshot.')
    label = 'vn.goldcast.monitoring-awake'
    target = Path.home() / 'Library/LaunchAgents' / (label + '.plist')
    target.parent.mkdir(parents=True, exist_ok=True)
    config = {'Label': label, 'ProgramArguments': ['/bin/sh', str(script)],
              'RunAtLoad': True, 'KeepAlive': True, 'ProcessType': 'Background', 'ThrottleInterval': 10}
    target.write_bytes(plistlib.dumps(config))
    target.chmod(0o644)
    service = 'gui/' + str(os.getuid()) + '/' + label
    loaded = subprocess.run(['launchctl', 'print', service], capture_output=True).returncode == 0
    if loaded:
        subprocess.run(['launchctl', 'kickstart', '-k', service], check=True)
    else:
        subprocess.run(['launchctl', 'bootstrap', 'gui/' + str(os.getuid()), str(target)], check=True)
    print('AC-only keep-awake enabled for this login session and future logins; display sleep unchanged.')
