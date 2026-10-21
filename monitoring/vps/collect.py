#!/usr/bin/env python3
"""Export only operational state. Never read runtime.env, credentials or price rows."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.request

SERVICES = ('db', 'backend', 'frontend', 'caddy')
FORMAT = r'{"name":{{json .Name}},"image":{{json .Image}},"running":{{.State.Running}},"health":{{if .State.Health}}{{json .State.Health.Status}}{{else}}"none"{{end}},"restarts":{{.RestartCount}},"oom":{{.State.OOMKilled}}}'


def metric(name, value, **labels):
    def escape(text):
        return str(text).replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
    suffix = '{' + ','.join(f'{key}="{escape(value)}"' for key, value in labels.items()) + '}' if labels else ''
    return f'{name}{suffix} {float(value):.17g}\n'


def read_manifest(directory):
    values = {}
    for line in (directory / 'release.env').read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, value = line.split('=', 1)
        if key in values or key not in ('RELEASE_ID', 'BACKEND_IMAGE', 'FRONTEND_IMAGE'):
            raise ValueError('Invalid manifest')
        values[key] = value
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', values['RELEASE_ID']):
        raise ValueError('Invalid release identity')
    for service in ('BACKEND', 'FRONTEND'):
        if not re.fullmatch(r'ghcr.io/duongtanminh1912/goldcast-' + service.lower() + r'@sha256:[a-f0-9]{64}', values[service + '_IMAGE']):
            raise ValueError('Invalid image reference')
    return values


def docker(*args):
    return subprocess.check_output(['docker', *args], timeout=10, stderr=subprocess.DEVNULL).decode()


def public_identity(url):
    if not url.startswith('https://'):
        raise ValueError('HTTPS required')
    with urllib.request.urlopen(url.rstrip('/') + '/api/release', timeout=10) as response:
        return json.load(response)


def collect(root, public_url, command=docker, fetch=public_identity, now=None):
    now = time.time() if now is None else now
    output = metric('goldcast_vps_collector_last_run_timestamp_seconds', now)
    success = True
    manifest = None
    try:
        release = Path((root / '.last-good-release').read_text().strip())
        if release.parent != root / 'releases' or release.is_symlink() or release.resolve() != release:
            raise ValueError('Invalid release path')
        manifest = read_manifest(release)
        output += metric('goldcast_deployed_release_info', 1, release_id=manifest['RELEASE_ID'])
        output += metric('goldcast_release_state_read_success', 1)
    except (OSError, ValueError, KeyError):
        success = False
        output += metric('goldcast_release_state_read_success', 0)
    lock = root / '.release-lock'
    try:
        lock_time = lock.stat().st_mtime
    except FileNotFoundError:
        lock_time = None
    output += metric('goldcast_release_lock_exists', lock_time is not None)
    output += metric('goldcast_release_lock_age_seconds', max(0, now - lock_time) if lock_time is not None else 0)
    try:
        containers = [json.loads(line) for line in command('inspect', '--format', FORMAT,
                      *('goldcast-' + service for service in SERVICES)).splitlines()]
        by_name = {item['name']: item for item in containers}
        for service in SERVICES:
            item = by_name['/goldcast-' + service]
            output += metric('goldcast_container_running', item['running'], service=service)
            ready = item['running'] and (item['health'] == 'healthy' if service != 'caddy' else True)
            output += metric('goldcast_container_ready', ready, service=service)
            output += metric('goldcast_container_restarts_total', item['restarts'], service=service)
            output += metric('goldcast_container_oom_killed', item['oom'], service=service)
            if manifest and service in ('backend', 'frontend'):
                expected = command('image', 'inspect', '--format', '{{.Id}}', manifest[service.upper() + '_IMAGE']).strip()
                output += metric('goldcast_container_image_matches_manifest', expected == item['image'], service=service)
        output += metric('goldcast_container_inspect_success', 1)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        success = False
        output += metric('goldcast_container_inspect_success', 0)
    try:
        identity = fetch(public_url)
        matches = bool(manifest and identity.get('releaseId') == manifest['RELEASE_ID']
                       and identity.get('backendReleaseId') == manifest['RELEASE_ID'])
        output += metric('goldcast_public_identity_probe_success', 1)
        output += metric('goldcast_public_release_matches_manifest', matches)
    except Exception:
        # Exception messages may include request details; emit only bounded scalar metrics.
        output += metric('goldcast_public_identity_probe_success', 0)
        output += metric('goldcast_public_release_matches_manifest', 0)
    output += metric('goldcast_vps_collector_success', success)
    return output


def write_atomic(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, prefix='goldcast-', suffix='.tmp', delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.chmod(0o644)
        temporary.replace(path)
    finally:
        if temporary and temporary.exists():
            temporary.unlink()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('/opt/goldcast'))
    parser.add_argument('--output', type=Path, default=Path('/opt/goldcast/monitoring/textfile/goldcast.prom'))
    parser.add_argument('--public-url', default='https://goldcast.click')
    args = parser.parse_args()
    write_atomic(args.output, collect(args.root.resolve(), args.public_url))
