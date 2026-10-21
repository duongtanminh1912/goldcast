#!/usr/bin/env python3
"""Plan backup/release retention. Deletion requires --apply; never prune Docker globally."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def verified_backup(path):
    try:
        result = json.loads((path / 'verification.json').read_text())
        if not all(result.get(key) is True for key in
                   ('sha256_verified', 'release_archive_verified', 'database_restore_verified')):
            return False
        checked = set()
        for line in (path / 'SHA256SUMS').read_text().splitlines():
            expected, name = line.split(None, 1)
            if Path(name).name != name or name in checked:
                return False
            file = path / name
            if file.is_symlink() or not file.is_file():
                return False
            digest = hashlib.sha256()
            with file.open('rb') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(block)
            if digest.hexdigest() != expected:
                return False
            checked.add(name)
        return {'verification.json', 'database.dump', 'release.tar', 'release.env'}.issubset(checked)
    except (OSError, ValueError, KeyError):
        return False


def backup_candidates(root, keep):
    # Incomplete or corrupt snapshots are left for investigation, never silently deleted.
    snapshots = sorted((p for p in root.glob('goldcast-backup-*')
                        if p.is_dir() and not p.is_symlink() and verified_backup(p)),
                       key=lambda p: p.name, reverse=True)
    return snapshots[keep:]


def release_candidates(root, keep, minimum_age_days=7, active_paths=()):
    releases = (root / 'releases').resolve()
    if (root / '.release-lock').exists():
        raise ValueError('Deployment/backup lock exists; retention refused')
    good = Path((root / '.last-good-release').read_text().strip())
    if good.parent != releases or not good.is_dir() or good.is_symlink():
        raise ValueError('Invalid last-good pointer')
    protected = {good, *active_paths}
    previous_file = good / 'previous-good-release'
    if previous_file.exists():
        previous = Path(previous_file.read_text().strip())
        if previous.parent != releases or not previous.is_dir() or previous.is_symlink():
            raise ValueError('Invalid saved rollback pointer')
        protected.add(previous)
    terminal = []
    for path in releases.iterdir():
        if path.is_symlink() or not path.is_dir():
            continue
        phase_file = path / 'deploy-phase'
        phase = phase_file.read_text().strip() if phase_file.exists() else ''
        if phase in ('promoted', 'rolled-back'):
            terminal.append(path)
        else:
            # Preserve interrupted, failed, baseline and unfamiliar releases and their targets.
            protected.add(path)
            if (path / 'previous-good-release').exists():
                protected.add(Path((path / 'previous-good-release').read_text().strip()))
    terminal.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    protected.update(terminal[:keep])
    now = datetime.now(timezone.utc).timestamp()
    return [p for p in terminal if p not in protected
            and now - p.stat().st_mtime >= minimum_age_days * 86400]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kind', choices=('backup', 'release'))
    parser.add_argument('root', type=Path)
    parser.add_argument('--keep', type=int, default=14)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if args.keep < 5:
        parser.error('Keep at least five verified backups/releases')
    root = args.root.resolve(strict=True)
    lock = None
    try:
        if args.kind == 'release' and args.apply:
            # Same kernel mutex as remote-release.sh. The mkdir owner lock still
            # protects the gap between deploy and G3 (and interrupted pipelines).
            import fcntl
            lock = (root / '.release-operation.lock').open('a')
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.kind == 'backup':
            candidates = backup_candidates(root, args.keep)
        else:
            # Fail closed when Docker is unavailable. Preserve every directory
            # referenced by a live service even if an operator moved the pointer.
            result = subprocess.run(['docker', 'inspect', 'goldcast-db', 'goldcast-backend',
                                     'goldcast-frontend', 'goldcast-caddy'],
                                    capture_output=True, check=True, timeout=15, text=True)
            active_paths = set()
            for container in json.loads(result.stdout):
                label = container['Config']['Labels']['com.docker.compose.project.config_files']
                active_paths.update(Path(name).parent for name in label.split(','))
            candidates = release_candidates(root, args.keep, active_paths=active_paths)
        for path in candidates:
            print(('DELETE ' if args.apply else 'WOULD DELETE ') + str(path))
            if args.apply:
                shutil.rmtree(path)
    finally:
        if lock:
            lock.close()


if __name__ == '__main__':
    main()
