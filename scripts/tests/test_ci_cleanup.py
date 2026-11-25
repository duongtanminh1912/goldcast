"""Don image goldcast cu tren may Jenkins bang docker gia lap."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'ci-cleanup.sh'
REG = 'ghcr.io/duongtanminh1912'


class CiCleanupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        # Bon build backend (b1 cu nhat), mot frontend; b2 con duoc container dung.
        (self.root / 'images').write_text('\n'.join([
            f'{REG}/goldcast-backend|2026-11-21 10:00:00 +0700|b1|{REG}/goldcast-backend:r1 {REG}/goldcast-backend@sha256:1 node:22-alpine',
            f'{REG}/goldcast-backend|2026-11-22 10:00:00 +0700|b2|{REG}/goldcast-backend:r2',
            f'{REG}/goldcast-backend|2026-11-23 10:00:00 +0700|b3|{REG}/goldcast-backend:r3',
            f'{REG}/goldcast-backend|2026-11-24 10:00:00 +0700|b4|{REG}/goldcast-backend:r4',
            f'{REG}/goldcast-backend|2026-11-25 10:00:00 +0700|b5|{REG}/goldcast-backend:r5',
            f'{REG}/goldcast-frontend|2026-11-25 10:00:00 +0700|f1|{REG}/goldcast-frontend:r5',
        ]) + '\n')
        docker = self.root / 'docker'
        docker.write_text('''#!/bin/sh
printf '%s\\n' "$*" >> "$MOCK_ROOT/docker.log"
case "$1 $2" in
  'image ls') awk -F'|' -v repo="$3" '$1 == repo { print $2 "|" $3 }' "$MOCK_ROOT/images";;
  'image inspect') awk -F'|' -v id="$5" '$3 == id { print $4 }' "$MOCK_ROOT/images";;
  'image rm') case "$*" in *goldcast-backend:r2*) echo 'conflict: image is being used' >&2; exit 1;; esac;;
  'buildx prune') exit "${MOCK_PRUNE_FAILURE:-0}";;
esac
''')
        docker.chmod(0o755)
        self.env = dict(os.environ, MOCK_ROOT=str(self.root), PATH=f'{self.root}:{os.environ["PATH"]}')

    def run_cleanup(self, *args, **env):
        result = subprocess.run(['sh', str(SCRIPT), REG, *args], env=dict(self.env, **env),
                                capture_output=True, text=True)
        log = (self.root / 'docker.log').read_text().splitlines()
        return result, [line for line in log if line.startswith('image rm')]

    def test_newest_builds_stay_and_older_ones_go_by_name(self):
        result, removed = self.run_cleanup('3')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(removed, [
            f'image rm {REG}/goldcast-backend:r2',
            f'image rm {REG}/goldcast-backend:r1 {REG}/goldcast-backend@sha256:1',
        ])
        self.assertNotIn('-f', ' '.join(removed).split())
        self.assertIn('kept', result.stdout)  # r2 dang duoc dung, bi tu choi, khong ep

    def test_other_projects_names_on_a_shared_image_are_never_removed(self):
        _, removed = self.run_cleanup('3')
        self.assertFalse(any('node:22-alpine' in line for line in removed))

    def test_nothing_to_remove_and_failed_cache_prune_never_fail_the_build(self):
        result, removed = self.run_cleanup('10', MOCK_PRUNE_FAILURE='1')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(removed, [])
        self.assertIn('buildx cache prune skipped', result.stdout)

    def test_cache_is_capped(self):
        self.run_cleanup('3', '7gb')
        self.assertIn('buildx prune -f --max-used-space 7gb', (self.root / 'docker.log').read_text())

    def test_invalid_keep_is_rejected_before_touching_docker(self):
        result = subprocess.run(['sh', str(SCRIPT), REG, 'all'], env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertFalse((self.root / 'docker.log').exists())


if __name__ == '__main__':
    unittest.main()
