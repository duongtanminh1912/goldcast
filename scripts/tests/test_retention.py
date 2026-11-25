import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('retention', Path(__file__).resolve().parents[1] / 'retention.py')
retention = importlib.util.module_from_spec(spec)
spec.loader.exec_module(retention)


class RetentionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()

    def backup(self, number):
        path = self.root / f'goldcast-backup-{number:03}'
        path.mkdir()
        (path / 'verification.json').write_text(json.dumps(dict.fromkeys(
            ('sha256_verified', 'release_archive_verified', 'database_restore_verified'), True)))
        for name in ('database.dump', 'release.tar', 'release.env'):
            (path / name).write_text('verified data')
        (path / 'SHA256SUMS').write_text(''.join(
            hashlib.sha256(file.read_bytes()).hexdigest() + '  ' + file.name + '\n'
            for file in sorted(path.iterdir())))
        return path

    def release(self, number, phase='promoted'):
        path = self.root / 'releases' / str(number)
        path.mkdir(parents=True)
        (path / 'deploy-phase').write_text(phase)
        os.utime(path, (1, 1))
        return path

    def test_only_old_verified_backups_are_candidates(self):
        backups = [self.backup(i) for i in range(7)]
        self.assertEqual(retention.backup_candidates(self.root, 5), [backups[1], backups[0]])
        (backups[6] / 'database.dump').write_text('damaged')
        self.assertEqual(retention.backup_candidates(self.root, 5), [backups[0]])

    def test_incomplete_backup_and_symlink_are_never_deleted(self):
        incomplete = self.root / 'goldcast-backup-incomplete'
        incomplete.mkdir()
        (self.root / 'goldcast-backup-link').symlink_to(incomplete, target_is_directory=True)
        self.assertEqual(retention.backup_candidates(self.root, 5), [])

    def test_checksum_path_traversal_is_rejected(self):
        path = self.backup(0)
        with (path / 'SHA256SUMS').open('a') as stream:
            stream.write('0' * 64 + '  ../outside\n')
        self.assertFalse(retention.verified_backup(path))

    def test_current_previous_running_and_interrupted_targets_survive(self):
        releases = [self.release(i) for i in range(8)]
        (self.root / '.last-good-release').write_text(str(releases[0]))
        (releases[0] / 'previous-good-release').write_text(str(releases[1]))
        interrupted = self.release(9, 'deploying')
        (interrupted / 'previous-good-release').write_text(str(releases[2]))
        # Restore old timestamps after writing metadata.
        for path in releases:
            os.utime(path, (1, 1))
        candidates = retention.release_candidates(self.root, 1, active_paths={releases[3]})
        self.assertFalse(set(releases[:4]) & set(candidates))
        self.assertNotIn(interrupted, candidates)
        self.assertTrue(candidates)

    def test_deployment_lock_refuses_retention(self):
        (self.root / '.release-lock').mkdir()
        with self.assertRaisesRegex(ValueError, 'lock'):
            retention.release_candidates(self.root, 5)

    def test_recent_release_is_kept_even_outside_count_limit(self):
        old = self.release(0)
        recent = self.release(1)
        (self.root / '.last-good-release').write_text(str(old))
        os.utime(recent, None)
        self.assertNotIn(recent, retention.release_candidates(self.root, 1))


BACKEND = 'ghcr.io/duongtanminh1912/goldcast-backend@sha256:'
FRONTEND = 'ghcr.io/duongtanminh1912/goldcast-frontend@sha256:'


class ImageRetentionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()

    def release(self, name, backend, frontend, phase='promoted', age=1):
        path = self.root / 'releases' / name
        path.mkdir(parents=True)
        (path / 'deploy-phase').write_text(phase)
        (path / 'release.env').write_text(f'RELEASE_ID={name}\nBACKEND_IMAGE={BACKEND}{backend * 64}\n'
                                          f'FRONTEND_IMAGE={FRONTEND}{frontend * 64}\n')
        os.utime(path, (age, age))
        return path

    def test_only_unreferenced_goldcast_digests_are_candidates(self):
        kept = [self.release('current', 'a', 'b'), self.release('previous', 'c', 'b')]
        local = {BACKEND + 'a' * 64, BACKEND + 'c' * 64, FRONTEND + 'b' * 64,
                 BACKEND + 'e' * 64, FRONTEND + 'f' * 64,
                 'ghcr.io/duongtanminh1912/goldcast-backend@<none>',
                 'docker.io/library/postgres@sha256:' + 'e' * 64}
        self.assertEqual(retention.image_candidates(local, kept), [BACKEND + 'e' * 64, FRONTEND + 'f' * 64])

    def test_a_release_without_manifest_protects_nothing_and_breaks_nothing(self):
        broken = self.root / 'releases' / 'broken'
        broken.mkdir(parents=True)
        self.assertEqual(retention.image_candidates({BACKEND + 'e' * 64}, [broken]), [BACKEND + 'e' * 64])

    def fake_docker(self):
        state = self.root / 'state'
        state.mkdir()
        docker = state / 'docker'
        container = {'Config': {'Labels': {'com.docker.compose.project.config_files':
                                           str(self.root / 'releases/current/docker-compose.prod.yml')}}}
        (state / 'inspect.json').write_text(json.dumps([container] * 4))
        docker.write_text(f'''#!/bin/sh
printf '%s\\n' "$*" >> {state}/docker.log
case "$1 $2" in
  'inspect goldcast-db') cat {state}/inspect.json;;
  'image ls') case "$*" in
      *goldcast-backend) printf '%s\\n' '{BACKEND}{"a" * 64}' '{BACKEND}{"d" * 64}' '{BACKEND}{"e" * 64}';;
      *goldcast-frontend) printf '%s\\n' '{FRONTEND}{"b" * 64}';;
    esac;;
  'image rm') case "$3" in *eeee*) echo 'conflict: in use' >&2; exit 1;; esac;;
esac
''')
        docker.chmod(0o755)
        return state, dict(os.environ, PATH=f'{state}:{os.environ["PATH"]}')

    def run_retention(self, env, *extra):
        script = Path(__file__).resolve().parents[1] / 'retention.py'
        return subprocess.run([sys.executable, str(script), 'release', str(self.root), '--keep', '5', *extra],
                              env=env, capture_output=True, text=True)

    def test_plan_lists_images_and_apply_removes_them_without_force(self):
        current = self.release('current', 'a', 'b', age=10 ** 9)
        (self.root / '.last-good-release').write_text(str(current))
        # Six old releases: five are kept by count, the oldest (images d) goes.
        for number in range(6):
            self.release(f'old{number}', 'd' if number == 0 else 'a', 'b', age=1 + number)
        state, env = self.fake_docker()

        plan = self.run_retention(env)
        self.assertEqual(plan.returncode, 0, plan.stderr)
        self.assertIn('WOULD DELETE ' + str(self.root / 'releases/old0'), plan.stdout)
        self.assertIn('WOULD DELETE IMAGE ' + BACKEND + 'd' * 64, plan.stdout)
        self.assertIn('WOULD DELETE IMAGE ' + BACKEND + 'e' * 64, plan.stdout)  # mo coi tu truoc
        self.assertNotIn(BACKEND + 'a' * 64, plan.stdout)
        self.assertNotIn('image rm', (state / 'docker.log').read_text())

        applied = self.run_retention(env, '--apply')
        self.assertEqual(applied.returncode, 0, applied.stderr)
        log = (state / 'docker.log').read_text()
        self.assertIn('image rm ' + BACKEND + 'd' * 64, log)
        self.assertNotIn('-f', [word for line in log.splitlines() if 'image rm' in line for word in line.split()])
        self.assertIn('KEEP IMAGE (in use) ' + BACKEND + 'e' * 64, applied.stdout)
        self.assertFalse((self.root / 'releases/old0').exists())
