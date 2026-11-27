#!/usr/bin/env python3
"""Cong SSH duy nhat cua khoa Jenkins tren VPS (forced command trong authorized_keys).

Khoa `vps-deploy` nam tren may Jenkins. Truoc day no mo duoc shell cua user `deploy`
(thuoc nhom docker, tuong duong root), nen ai chiem may Jenkins la chiem production.
Qua cong nay khoa chi lam duoc vai viec co ten, va moi script/cau hinh duoc chay phai
giong het file cung ten tai mot commit da nam tren nhanh `main` cua GitHub. Jenkins bi
chiem van khong chay duoc lenh tuy y, khong dua len duoc script hay compose tu che.

Lenh (SSH_ORIGINAL_COMMAND, tach theo khoang trang, khong bao gio qua shell):
  receive <release-id>                  tar qua stdin -> releases/<id>, sau khi doi chieu GitHub
  deploy <release-id> [build-url]       chay scripts/remote-release.sh cua release da doi chieu
  verify <release-id>
  rollback <release-id>                 release chua tung nhan thi bo qua (nhu truoc day)
  backup snapshot                       scripts/backup-remote.sh cua last-good release
  backup fetch <offsite-dir>            tar thu muc backup ra stdout
  backup confirm <offsite-dir>          scripts/backup-remote-retention.sh cua last-good release

Viec quan tri (status, recover, bootstrap, retention) dung khoa ca nhan, khong qua day.
Rui ro con lai: release.env la du lieu, khong co tren GitHub; image trong do chi bi
gioi han trong hai repo GHCR cua du an (release.sh kiem), ma token GHCR cung nam tren
may Jenkins. Xem docs/huong-dan.md.
"""
import hashlib
import io
import json
import os
import re
import shutil
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path('/opt/goldcast')
REPOSITORY = 'duongtanminh1912/goldcast'
BRANCH = 'main'
MARKER = '.gate-verified'
MAX_UPLOAD = 4 * 1024 * 1024
RELEASE_ID = re.compile(r'(?P<commit>[0-9a-f]{40})-[0-9a-f]{12}-[0-9]{1,9}')
BUILD_URL = re.compile(r'https?://[A-Za-z0-9._:/%~+-]{1,300}')
OFFSITE = re.compile(r'/opt/goldcast/backups/offsite-[0-9TZ]+\.[A-Za-z0-9]+')
# release.env la du lieu (digest image) do release.sh kiem; moi file khac phai khop GitHub.
DATA_FILES = {'release.env'}
CODE_FILES = {
    'docker-compose.prod.yml', 'deploy/Caddyfile', 'scripts/release.sh',
    'scripts/check-pages.mjs', 'scripts/remote-release.sh', 'scripts/retention.py',
    'scripts/backup-remote.sh', 'scripts/backup-remote-retention.sh',
}
REQUIRED = DATA_FILES | {'docker-compose.prod.yml', 'deploy/Caddyfile', 'scripts/release.sh',
                         'scripts/check-pages.mjs', 'scripts/remote-release.sh'}


class Refused(Exception):
    pass


def fetch(url, timeout=20):
    request = urllib.request.Request(url, headers={'User-Agent': 'goldcast-gate'})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def commit_on_branch(commit, get=fetch):
    """main chua commit: so sanh commit...main la 'ahead' hoac 'identical'."""
    url = f'https://api.github.com/repos/{REPOSITORY}/compare/{commit}...{BRANCH}'
    try:
        status = json.loads(get(url)).get('status')
    except Exception as error:  # mang loi, 404, JSON hong: deu tu choi
        raise Refused(f'cannot confirm {commit} is on {BRANCH}: {error}') from None
    if status not in ('ahead', 'identical'):
        raise Refused(f'commit {commit} is not on {BRANCH} ({status})')


def matches_github(commit, name, content, get=fetch):
    url = f'https://raw.githubusercontent.com/{REPOSITORY}/{commit}/{name}'
    try:
        expected = get(url)
    except Exception as error:
        raise Refused(f'cannot fetch {name} at {commit}: {error}') from None
    if hashlib.sha256(expected).digest() != hashlib.sha256(content).digest():
        raise Refused(f'{name} differs from {REPOSITORY}@{commit}')


def release_dir(release_id, root):
    match = RELEASE_ID.fullmatch(release_id)
    if not match:
        raise Refused('invalid release id')
    return root / 'releases' / release_id, match['commit']


def read_upload(stream):
    data = stream.read(MAX_UPLOAD + 1)
    if len(data) > MAX_UPLOAD:
        raise Refused('upload too large')
    files = {}
    try:
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            for member in archive:
                name = member.name.removeprefix('./')
                if member.isdir() and name in ('', '.', 'deploy', 'scripts'):
                    continue
                if not member.isfile() or name not in DATA_FILES | CODE_FILES or name in files:
                    raise Refused(f'unexpected entry {member.name!r}')
                files[name] = archive.extractfile(member).read()
    except tarfile.TarError as error:
        raise Refused(f'bad archive: {error}') from None
    missing = REQUIRED - set(files)
    if missing:
        raise Refused('missing ' + ', '.join(sorted(missing)))
    return files


def receive(release_id, stream, root=ROOT, get=fetch):
    target, commit = release_dir(release_id, root)
    if target.exists():
        raise Refused('release already received')
    files = read_upload(stream)
    commit_on_branch(commit, get)
    for name in sorted(files):
        if name in CODE_FILES:
            matches_github(commit, name, files[name], get)
    staging = Path(tempfile.mkdtemp(prefix='.receiving-', dir=root / 'releases'))
    try:
        for name, content in files.items():
            path = staging / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        (staging / MARKER).write_text(''.join(
            f'{hashlib.sha256(files[name]).hexdigest()}  {name}\n' for name in sorted(files)))
        staging.rename(target)
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    print(f'received {release_id}: {len(files)} files match {REPOSITORY}@{commit}')


def verified(path, names):
    """Release da qua cong va cac file can chay van dung noi dung da doi chieu."""
    marker = path / MARKER
    if path.is_symlink() or not marker.is_file():
        raise Refused(f'{path} was not received through the gate')
    recorded = dict(reversed(line.split('  ', 1)) for line in marker.read_text().splitlines())
    for name in names:
        file = path / name
        if name not in recorded or file.is_symlink() or not file.is_file() \
                or hashlib.sha256(file.read_bytes()).hexdigest() != recorded[name]:
            raise Refused(f'{name} changed since it was verified')


def last_good(root):
    try:
        path = Path((root / '.last-good-release').read_text().strip())
    except OSError:
        raise Refused('no last-good release yet') from None
    if path.parent != root / 'releases':
        raise Refused('invalid last-good pointer')
    return path


def plan(words, root=ROOT):
    """argv cua lenh se exec cho mot yeu cau hop le (None: khong can lam gi); khong chay gi."""
    if not words:
        raise Refused('no command')
    action, args = words[0], words[1:]
    if action in ('deploy', 'verify', 'rollback'):
        if not 1 <= len(args) <= (2 if action == 'deploy' else 1):
            raise Refused('usage')
        path, _ = release_dir(args[0], root)
        if action == 'rollback' and not path.exists():
            print('release never received; nothing to roll back')
            return None
        if len(args) == 2 and not BUILD_URL.fullmatch(args[1]):
            raise Refused('invalid build url')
        verified(path, ['scripts/remote-release.sh', 'scripts/release.sh'])
        return ['sh', str(path / 'scripts/remote-release.sh'), action, str(path), *args[1:]]
    if action == 'backup' and args[:1] == ['snapshot'] and len(args) == 1:
        good = last_good(root)
        verified(good, ['scripts/backup-remote.sh'])
        return ['sh', str(good / 'scripts/backup-remote.sh')]
    if action == 'backup' and len(args) == 2 and args[0] in ('fetch', 'confirm'):
        if not OFFSITE.fullmatch(args[1]):
            raise Refused('invalid backup directory')
        directory = Path(args[1])
        if directory.is_symlink() or not directory.is_dir():
            raise Refused('no such backup')
        if args[0] == 'fetch':
            return ['tar', '-cf', '-', '-C', str(directory), '.']
        good = last_good(root)
        verified(good, ['scripts/backup-remote-retention.sh'])
        return ['sh', str(good / 'scripts/backup-remote-retention.sh'), str(directory)]
    raise Refused(f'command not allowed: {action}')


def main():
    words = os.environ.get('SSH_ORIGINAL_COMMAND', '').split()
    # Jenkins goi bang duong dan day du de lenh cung chay duoc khi khoa chua bi gioi han.
    if words[:1] in (['goldcast-gate'], ['/usr/local/bin/goldcast-gate']):
        words = words[1:]
    try:
        if words[:1] == ['receive']:
            if len(words) != 2:
                raise Refused('usage: receive <release-id>')
            receive(words[1], sys.stdin.buffer)
            return 0
        argv = plan(words)
        if argv is None:
            return 0
        os.execvp(argv[0], argv)
    except Refused as error:
        print(f'goldcast-gate: refused: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
