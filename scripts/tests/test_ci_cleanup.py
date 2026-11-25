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
            f'{REG}/goldcast-backend|2026-11-20 10:00:00 +0700|b0|{REG}/goldcast-backend:r0 node:22-alpine',
            f'{REG}/goldcast-backend|2026-11-21 10:00:00 +0700|b1|{REG}/goldcast-backend:r1 {REG}/goldcast-backend@sha256:1',
            f'{REG}/goldcast-backend|2026-11-22 10:00:00 +0700|b2|{REG}/goldcast-backend:r2',
            f'{REG}/goldcast-backend|2026-11-23 10:00:00 +0700|b3|{REG}/goldcast-backend:r3',
            f'{REG}/goldcast-backend|2026-11-24 10:00:00 +0700|b4|{REG}/goldcast-backend:r4',
            f'{REG}/goldcast-backend|2026-11-25 10:00:00 +0700|b5|{REG}/goldcast-backend:r5',
            f'{REG}/goldcast-frontend|2026-11-25 10:00:00 +0700|f1|{REG}/goldcast-frontend:r5',
        ]) + '\n')
        docker = self.root / 'docker'
        docker.write_text('''#!/bin/sh
printf '%s\\n' "$*" >> "$MOCK_ROOT/docker.log"
# Nhu Docker that: xoa ten cuoi cung thi xoa ca image; ten con lai sau do bao "no such image".
case "$1 $2" in
  'image ls') awk -F'|' -v repo="$3" '$1 == repo { print $2 "|" $3 }' "$MOCK_ROOT/images";;
  'image inspect')
    if [ "$3" = --format ]; then awk -F'|' -v id="$5" '$3 == id { print $4 }' "$MOCK_ROOT/images"
    else grep -q "|$3|" "$MOCK_ROOT/images" || exit 1; fi;;
  'image rm')
    case "$3" in *goldcast-backend:r2*) echo 'conflict: image is being used' >&2; exit 1;; esac
    grep -q " $3\\( \\|\$\\)\\||$3\\( \\|\$\\)" "$MOCK_ROOT/images" 2>/dev/null || { echo 'No such image' >&2; exit 1; }
    awk -F'|' -v ref="$3" 'BEGIN { OFS = "|" } { n = split($4, names, " "); kept = "";
        for (i = 1; i <= n; i++) if (names[i] != ref) kept = kept (kept ? " " : "") names[i];
        if (kept == "" && $4 != "") next; $4 = kept; print }' "$MOCK_ROOT/images" > "$MOCK_ROOT/images.new"
    mv "$MOCK_ROOT/images.new" "$MOCK_ROOT/images";;
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
            f'image rm {REG}/goldcast-backend:r1',
            f'image rm {REG}/goldcast-backend@sha256:1',
            f'image rm {REG}/goldcast-backend:r0',
        ])
        self.assertNotIn('-f', ' '.join(removed).split())
        self.assertIn('kept ' + REG + '/goldcast-backend b2 (in use or named by another project)', result.stdout)
        # b1 co hai ten: ten thu hai bao loi sau khi image da mat, van phai bao la da xoa.
        self.assertIn('removed ' + REG + '/goldcast-backend b1', result.stdout)
        images = (self.root / 'images').read_text()
        self.assertNotIn('|b1|', images)
        self.assertIn('|b2|', images)
        # b0 cung mang ten cua project khac: chi mat ten goldcast, image va ten kia o lai.
        self.assertIn('|b0|node:22-alpine', images)
        self.assertNotIn(':r0', images)

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
