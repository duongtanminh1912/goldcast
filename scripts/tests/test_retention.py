import hashlib
import importlib.util
import json
import os
from pathlib import Path
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
