#!/usr/bin/env python3
"""Back up the current VPS release privately and restore-test its database."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tarfile
import time
from datetime import datetime, timezone

def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()

def require(condition, message):
    if not condition:
        raise ValueError(message)


def command(args, **kwargs):
    result = subprocess.run(args, capture_output=True, timeout=kwargs.pop('timeout', 180), **kwargs)
    if result.returncode:
        message = result.stderr.decode('utf-8', errors='replace')[-2500:]
        raise RuntimeError(f'Command failed ({args[0]}): {message}')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', required=True, type=Path, help='Private directory outside the checkout, on another host than the VPS')
    parser.add_argument('--key', required=True, help='SSH private key path (never included in the backup)')
    parser.add_argument('--host', default='deploy@103.175.248.236')
    parser.add_argument('--known-hosts', type=Path, default=Path.home() / '.ssh/known_hosts')
    args = parser.parse_args()
    destination = args.destination.expanduser().resolve()
    checkout = Path(__file__).resolve().parents[1]
    if destination == checkout or checkout in destination.parents:
        parser.error('Backup destination must be outside the Git checkout')
    os.umask(0o077)
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    output = destination / ('goldcast-backup-' + stamp)
    destination.mkdir(mode=0o700, parents=True, exist_ok=True)
    if stat.S_IMODE(destination.stat().st_mode) & 0o077:
        parser.error('Backup destination must have mode 700')
    output.mkdir(mode=0o700)
    print(f'BACKUP_DIRECTORY={output}', flush=True)
    ssh = ['ssh', '-i', args.key, '-o', 'IdentitiesOnly=yes',
           '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', '-o', 'StrictHostKeyChecking=yes',
           '-o', 'UserKnownHostsFile=' + str(args.known_hosts),
           '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3', args.host]


    print('Capturing release and database under the deployment lock...', flush=True)
    remote = command(ssh + ['sh -s'], input=Path(__file__).with_name('backup-remote.sh').read_bytes(), timeout=300)
    remote_directory = remote.stdout.decode().strip()
    if not re.fullmatch(r'/opt/goldcast/backups/offsite-[0-9TZ]+\.[A-Za-z0-9]+', remote_directory):
        raise RuntimeError('Unexpected remote backup path')
    print(f'Remote backup complete: {remote_directory}', flush=True)
    transfer = output / 'transfer.tar.partial'
    with transfer.open('xb') as stream:
        copied = subprocess.run(ssh + ['tar -cf - -C ' + shlex.quote(remote_directory) + ' .'],
                                stdout=stream, stderr=subprocess.PIPE, timeout=300)
    if copied.returncode:
        raise RuntimeError('Backup transfer failed; private partial file retained')
    with tarfile.open(transfer) as archive:
        names = set()
        for member in archive:
            name = member.name.removeprefix('./')
            if member.isdir() and name in ('', '.'):
                continue
            if not member.isfile() or not re.fullmatch(r'[A-Za-z0-9_.-]+', name) or name in names:
                raise RuntimeError('Unexpected entry in backup transfer')
            names.add(name)
            with archive.extractfile(member) as source, (output / name).open('xb') as target:
                shutil.copyfileobj(source, target)
    required = {'release.tar', 'release.env', 'database.dump', 'metadata.txt', 'public-release.json', 'registry-check.log', 'SHA256SUMS'}
    if not required.issubset(names):
        raise RuntimeError('Incomplete backup transfer')
    checksum_names = set()
    for line in (output / 'SHA256SUMS').read_text().splitlines():
        expected, filename = line.split(None, 1)
        filename = filename.strip()
        if (not re.fullmatch(r'[A-Za-z0-9_.-]+', filename) or filename in checksum_names
                or not re.fullmatch(r'[a-f0-9]{64}', expected) or digest(output / filename) != expected):
            raise RuntimeError('Backup checksum mismatch')
        checksum_names.add(filename)
    require(checksum_names == names - {'SHA256SUMS'}, 'Incomplete checksum coverage')
    transfer.unlink()
    manifest = dict(line.split('=', 1) for line in (output / 'release.env').read_text().splitlines())
    metadata = dict(line.split('=', 1) for line in (output / 'metadata.txt').read_text().splitlines())
    public = json.loads((output / 'public-release.json').read_text())
    require(public['releaseId'] == public['backendReleaseId'] == manifest['RELEASE_ID'], 'Public release mismatch')
    with tarfile.open(output / 'release.tar') as archive:
        allowed = {'release.env', 'docker-compose.prod.yml', 'deploy/Caddyfile', 'scripts/release.sh',
                   'scripts/check-pages.mjs', 'scripts/remote-release.sh', 'scripts/retention.py', 'runtime.env', 'legacy-local-origin'}
        members = archive.getmembers()
        archive_names = {member.name for member in members}
        require(all(member.isfile() and member.name in allowed for member in members), 'Unsafe release archive')
        require(len(archive_names) == len(members), 'Duplicate archive entry')
        require((allowed - {'legacy-local-origin', 'scripts/remote-release.sh', 'scripts/retention.py'}).issubset(archive_names), 'Missing release configuration')
        require(archive.extractfile('release.env').read() == (output / 'release.env').read_bytes(), 'Manifest mismatch')
        validator = archive.extractfile('scripts/release.sh').read()
        require(hashlib.sha256(validator).hexdigest() == metadata['validator_sha256'], 'Validator mismatch')
        require(archive.getmember('runtime.env').size > 0, 'Missing frozen runtime environment')
    with (output / 'database.dump').open('rb') as stream:
        require(stream.read(5) == b'PGDMP', 'Invalid PostgreSQL custom dump')
    print('Archive, configuration, release identity and SHA-256 checks passed.', flush=True)

    container = 'goldcast-backup-restore-' + stamp.lower()
    restore_log = output / 'restore-check.log'
    created = False
    try:
        print('Restoring into a temporary PostgreSQL 16 container; no network or published ports...', flush=True)
        command(['docker', 'run', '-d', '--rm', '--pull=missing', '--network', 'none', '--memory', '256m', '--cpus', '1',
                 '--name', container, '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '-e', 'POSTGRES_USER=restorecheck',
                 '-e', 'POSTGRES_DB=restorecheck', 'postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea'])
        created = True
        for attempt in range(60):
            ready = subprocess.run(['docker', 'exec', container, 'pg_isready', '-U', 'restorecheck', '-d', 'restorecheck'],
                                   capture_output=True, timeout=10)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('Temporary restore database did not become ready')
        with (output / 'database.dump').open('rb') as source, restore_log.open('wb') as log:
            restored = subprocess.run(['docker', 'exec', '-i', container, 'pg_restore', '--exit-on-error', '--no-owner',
                                       '--no-privileges', '-U', 'restorecheck', '-d', 'restorecheck'],
                                      stdin=source, stdout=log, stderr=subprocess.STDOUT, timeout=300)
        if restored.returncode:
            raise RuntimeError(f'Database restore failed; see private log {restore_log}')
        sql = """SELECT json_build_object(
            'server_version', current_setting('server_version'),
            'instrument', (SELECT count(*) FROM instrument),
            'price_point', (SELECT count(*) FROM price_point),
            'forecast_run', (SELECT count(*) FROM forecast_run),
            'forecast_point', (SELECT count(*) FROM forecast_point),
            'flyway_successes', (SELECT count(*) FROM flyway_schema_history WHERE success),
            'flyway_failures', (SELECT count(*) FROM flyway_schema_history WHERE NOT success)
        );"""
        result = command(['docker', 'exec', container, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'restorecheck',
                          '-d', 'restorecheck', '-Atc', sql])
        counts = json.loads(result.stdout)
        require(counts['instrument'] > 0 and counts['price_point'] > 0, 'Expected production data missing')
        require(counts['flyway_failures'] == 0 and counts['flyway_successes'] >= 2, 'Unexpected migration state')
        print('Restore check passed: ' + json.dumps(counts), flush=True)
    finally:
        if created:
            failing = sys.exc_info()[0] is not None
            cleanup = subprocess.run(['docker', 'stop', '--time', '5', container], capture_output=True, timeout=30)
            if cleanup.returncode and not failing:
                raise RuntimeError('Restore passed, but temporary container cleanup failed: ' + container)

    registry = (output / 'registry-check.log').read_text().splitlines()
    registry_ok = len(registry) == 2 and all(line.startswith('PASS ') for line in registry)
    verification = {
        'verified_utc': datetime.now(timezone.utc).isoformat(),
        'release_id': manifest['RELEASE_ID'], 'remote_backup_directory': remote_directory,
        'sha256_verified': True, 'release_archive_verified': True, 'database_restore_verified': True,
        'restored_counts': counts, 'ghcr_manifests_accessible': registry_ok,
        'backup_directory_mode': oct(stat.S_IMODE(output.stat().st_mode)),
    }
    (output / 'verification.json').write_text(json.dumps(verification, ensure_ascii=False, indent=2) + '\n')
    (output / 'README.md').write_text(
        '# GoldCast backup\n\n'
        f"Release: `{manifest['RELEASE_ID']}`.\n\n"
        '- `release.tar`: manifest, Compose, Caddyfile, release/probe scripts and frozen runtime.env.\n'
        '- `database.dump`: PostgreSQL custom-format dump, successfully restored into an isolated PostgreSQL 16 container.\n'
        '- `production.env`: current deployment settings, when present on the source VPS.\n'
        '- `verification.json`: restore counts and validation results.\n'
        '- `SHA256SUMS`: checksums of every backup file.\n\n'
        'The folder contains private configuration. Directory mode: 700; file modes: 600.\n'
        'Recovery procedure: docs/dung-lai-khi-het-vps.md in the GoldCast repository.\n'
    )
    for path in output.iterdir():
        if path.is_file():
            path.chmod(0o600)
    checksums = ''.join(f'{digest(path)}  {path.name}\n' for path in sorted(output.iterdir()) if path.is_file() and path.name != 'SHA256SUMS')
    (output / 'SHA256SUMS').write_text(checksums)
    print('GHCR manifest checks: ' + ('both accessible' if registry_ok else 'needs review; see registry-check.log'), flush=True)
    print('VERIFIED_BACKUP_DIRECTORY=' + str(output), flush=True)
    if not registry_ok:
        raise SystemExit('Backup and restore verified, but GHCR availability still needs investigation.')
    command(ssh + ['sh -s -- ' + shlex.quote(remote_directory)],
            input=Path(__file__).with_name('backup-remote-retention.sh').read_bytes())

if __name__ == '__main__':
    main()
