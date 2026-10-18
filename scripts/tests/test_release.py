"""Exercise promotion/rollback state transitions without contacting a VPS."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'release.sh'


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.old = self.release('old')
        self.new = self.release('new')
        (self.root / '.env').touch()
        self.marker = self.root / '.last-good-release'
        self.marker.write_text(str(self.old) + '\n')
        docker = self.root / 'docker'
        docker.write_text('''#!/bin/sh
printf '%s\\n' "$*" >> "$GOLDCAST_ROOT/docker.log"
case "$*" in
  *'ps -q backend'*) echo backend;;
  *'ps -q frontend'*) echo frontend;;
  *'health/readiness'*) echo '{"status":"UP"}';;
  'inspect '*) echo image-id;;
  'image inspect '*) echo "${MOCK_IMAGE_ID:-image-id}";;
  'run '*) cat >/dev/null; exit "${MOCK_PAGE_FAILURE:-0}";;
  *'up -d '*) exit "${MOCK_UP_FAILURE:-0}";;
esac
''')
        docker.chmod(0o755)
        for command in ('rm', 'rmdir'):
            wrapper = self.root / command
            wrapper.write_text(f"""#!/bin/sh
if [ "${{MOCK_{command.upper()}_FAILURE:-0}}" = 1 ]; then exit 1; fi
exec /bin/{command} "$@"
""")
            wrapper.chmod(0o755)
        self.env = dict(os.environ, GOLDCAST_ROOT=str(self.root), PATH=str(self.root) + ':' + os.environ['PATH'])

    def release(self, name):
        path = self.root / 'releases' / name
        (path / 'scripts').mkdir(parents=True)
        (path / 'release.env').write_text(f'RELEASE_ID={name}\nBACKEND_IMAGE=test/backend@sha256:abc\nFRONTEND_IMAGE=test/frontend@sha256:def\n')
        (path / 'docker-compose.prod.yml').touch()
        (path / 'scripts/check-pages.mjs').touch()
        if name == 'old':
            (path / 'runtime.env').touch()
        return path

    def run_release(self, action, *args, **env):
        return subprocess.run(['sh', str(SCRIPT), action, str(self.new), *args], env=dict(self.env, **env), capture_output=True, text=True)

    def test_only_verified_release_is_promoted(self):
        self.assertEqual(self.run_release('deploy').returncode, 0)
        self.assertEqual(self.marker.read_text().strip(), str(self.old))
        self.assertEqual(self.run_release('verify').returncode, 0)
        self.assertEqual(self.marker.read_text().strip(), str(self.new))
        self.assertFalse((self.root / '.release-lock').exists())

    def test_g3_failure_keeps_good_marker_then_rolls_back(self):
        self.assertEqual(self.run_release('deploy').returncode, 0)
        self.assertNotEqual(self.run_release('verify', MOCK_PAGE_FAILURE='1').returncode, 0)
        self.assertEqual(self.marker.read_text().strip(), str(self.old))
        self.assertEqual(self.run_release('rollback').returncode, 0)
        self.assertIn(str(self.old / 'release.env'), (self.root / 'docker.log').read_text())
        self.assertFalse((self.root / '.release-lock').exists())

    def test_wrong_running_version_rejects_promotion(self):
        self.assertEqual(self.run_release('deploy').returncode, 0)
        self.assertNotEqual(self.run_release('verify', MOCK_IMAGE_ID='other-image').returncode, 0)
        self.assertEqual(self.marker.read_text().strip(), str(self.old))

    def test_public_release_identity_is_passed_to_https_probe(self):
        self.assertEqual(self.run_release('deploy').returncode, 0)
        self.assertEqual(self.run_release('verify').returncode, 0)
        self.assertIn('https://goldcast.click new', (self.root / 'docker.log').read_text())

    def test_old_baseline_requires_verified_refresh_before_production_changes(self):
        (self.old / 'runtime.env').unlink()
        self.assertNotEqual(self.run_release('deploy').returncode, 0)
        self.assertFalse((self.root / '.release-lock').exists())
        self.assertFalse((self.root / 'docker.log').exists())

    def test_refresh_keeps_old_good_pointer_when_verification_fails(self):
        self.assertNotEqual(self.run_release('bootstrap', '--refresh', MOCK_PAGE_FAILURE='1').returncode, 0)
        self.assertEqual(self.marker.read_text().strip(), str(self.old))
        self.assertFalse((self.root / '.release-lock').exists())

    def test_refresh_records_only_a_verified_baseline_without_restarting(self):
        self.assertEqual(self.run_release('bootstrap', '--refresh').returncode, 0)
        self.assertEqual(self.marker.read_text().strip(), str(self.new))
        self.assertNotIn('up -d', (self.root / 'docker.log').read_text())

    def test_candidate_without_public_identity_is_rejected_before_deploy(self):
        manifest = self.new / 'release.env'
        manifest.write_text(manifest.read_text().replace('RELEASE_ID=new\n', ''))
        self.assertNotEqual(self.run_release('deploy').returncode, 0)
        self.assertFalse((self.root / '.release-lock').exists())

    def test_candidate_cannot_select_legacy_verification_via_its_identity(self):
        manifest = self.new / 'release.env'
        manifest.write_text(manifest.read_text().replace('RELEASE_ID=new', 'RELEASE_ID=--legacy-local-origin'))
        self.assertNotEqual(self.run_release('deploy').returncode, 0)
        self.assertFalse((self.root / '.release-lock').exists())

    def test_release_freezes_environment_before_production_is_changed(self):
        (self.root / '.env').write_text('APP_INGEST_SEED_ON_EMPTY=true\n')
        self.assertEqual(self.run_release('deploy').returncode, 0)
        (self.root / '.env').write_text('APP_INGEST_SEED_ON_EMPTY=false\n')
        self.assertEqual((self.new / 'runtime.env').read_text(), 'APP_INGEST_SEED_ON_EMPTY=true\n')
        self.assertEqual((self.new / 'runtime.env').stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.run_release('verify').returncode, 0)
        self.assertIn(str(self.new / 'runtime.env'), (self.root / 'docker.log').read_text())

    def test_deploy_failure_can_rollback(self):
        self.assertNotEqual(self.run_release('deploy', MOCK_UP_FAILURE='1').returncode, 0)
        self.assertEqual(self.run_release('rollback').returncode, 0)

    def test_failed_rollback_keeps_lock_and_good_marker(self):
        self.assertEqual(self.run_release('deploy').returncode, 0)
        self.assertNotEqual(self.run_release('rollback', MOCK_UP_FAILURE='1').returncode, 0)
        self.assertTrue((self.root / '.release-lock').exists())
        self.assertEqual(self.marker.read_text().strip(), str(self.old))

    def test_rollback_uses_saved_target_when_good_pointer_changes(self):
        self.assertEqual(self.run_release('deploy').returncode, 0)
        other = self.release('other')
        self.marker.write_text(str(other) + '\n')
        (self.root / 'docker.log').write_text('')
        self.assertEqual(self.run_release('rollback').returncode, 0)
        log = (self.root / 'docker.log').read_text()
        self.assertIn(str(self.old / 'release.env'), log)
        self.assertNotIn(str(other / 'release.env'), log)
        self.assertEqual(self.marker.read_text().strip(), str(self.old))

    def test_promotion_cleanup_failure_is_warning_and_never_rolls_back(self):
        for failure in ('MOCK_RM_FAILURE', 'MOCK_RMDIR_FAILURE'):
            with self.subTest(failure=failure):
                # Restore fixture between the two independent failure scenarios.
                lock = self.root / '.release-lock'
                if lock.exists():
                    for entry in lock.iterdir():
                        entry.unlink()
                    lock.rmdir()
                self.marker.write_text(str(self.old) + '\n')
                self.assertEqual(self.run_release('deploy').returncode, 0)
                result = self.run_release('verify', **{failure: '1'})
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('WARNING: release promoted; lock cleanup failed', result.stderr)
                self.assertEqual(self.marker.read_text().strip(), str(self.new))
                self.assertEqual((self.new / 'previous-good-release').read_text().strip(), str(self.old))
                (self.root / 'docker.log').write_text('')
                self.assertEqual(self.run_release('rollback').returncode, 0)
                self.assertEqual((self.root / 'docker.log').read_text(), '')
                self.assertTrue(lock.exists())

    def test_missing_saved_target_prevents_guessing_rollback_target(self):
        self.assertEqual(self.run_release('deploy').returncode, 0)
        (self.new / 'previous-good-release').unlink()
        (self.root / 'docker.log').write_text('')
        self.assertNotEqual(self.run_release('rollback').returncode, 0)
        self.assertEqual((self.root / 'docker.log').read_text(), '')
        self.assertTrue((self.root / '.release-lock').exists())

    def test_status_records_build_and_previous_release(self):
        self.assertEqual(self.run_release('deploy', 'https://jenkins.example/job/main/12/').returncode, 0)
        result = self.run_release('status')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('https://jenkins.example/job/main/12/', result.stdout)
        self.assertIn('deploy-phase: deployed', result.stdout)
        self.assertIn(str(self.old), result.stdout)
        self.assertRegex((self.new / 'deploy-started-at').read_text(), r'^\d{4}-\d{2}-\d{2}T')

    def test_recovery_requires_explicit_stopped_confirmation(self):
        self.assertEqual(self.run_release('deploy').returncode, 0)
        (self.root / 'docker.log').write_text('')
        self.assertNotEqual(self.run_release('recover').returncode, 0)
        self.assertTrue((self.root / '.release-lock').exists())
        self.assertEqual((self.root / 'docker.log').read_text(), '')

    def test_recover_interrupted_deploy_rolls_back_saved_target(self):
        self.assertEqual(self.run_release('deploy').returncode, 0)
        result = self.run_release('recover', '--confirm-stopped')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.marker.read_text().strip(), str(self.old))
        self.assertFalse((self.root / '.release-lock').exists())
        self.assertEqual((self.new / 'deploy-phase').read_text().strip(), 'rolled-back')

    def test_recover_promoted_release_with_residual_lock(self):
        for failure in ('MOCK_RM_FAILURE', 'MOCK_RMDIR_FAILURE'):
            with self.subTest(failure=failure):
                self.marker.write_text(str(self.old) + '\n')
                self.assertEqual(self.run_release('deploy').returncode, 0)
                self.assertEqual(self.run_release('verify', **{failure: '1'}).returncode, 0)
                result = self.run_release('recover', '--confirm-stopped')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertFalse((self.root / '.release-lock').exists())
                self.assertEqual(self.marker.read_text().strip(), str(self.new))

    def test_recover_failed_verification_keeps_lock(self):
        self.assertEqual(self.run_release('deploy').returncode, 0)
        self.assertEqual(self.run_release('verify', MOCK_RM_FAILURE='1').returncode, 0)
        self.assertNotEqual(self.run_release('recover', '--confirm-stopped', MOCK_PAGE_FAILURE='1').returncode, 0)
        self.assertTrue((self.root / '.release-lock').exists())

    def test_recover_rejects_other_owner(self):
        self.assertEqual(self.run_release('deploy').returncode, 0)
        (self.root / '.release-lock/owner').write_text('another-release\n')
        (self.root / 'docker.log').write_text('')
        self.assertNotEqual(self.run_release('recover', '--confirm-stopped').returncode, 0)
        self.assertEqual((self.root / 'docker.log').read_text(), '')

    def test_bootstrap_verifies_before_recording_baseline(self):
        self.marker.unlink()
        result = self.run_release('bootstrap')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.marker.read_text().strip(), str(self.new))
        self.assertFalse((self.root / '.release-lock').exists())
        self.assertNotIn('up -d', (self.root / 'docker.log').read_text())

    def test_failed_bootstrap_does_not_record_baseline_or_keep_lock(self):
        self.marker.unlink()
        self.assertNotEqual(self.run_release('bootstrap', MOCK_PAGE_FAILURE='1').returncode, 0)
        self.assertFalse(self.marker.exists())
        self.assertFalse((self.root / '.release-lock').exists())

    def test_bootstrap_does_not_remove_another_deploy_lock(self):
        self.marker.unlink()
        lock = self.root / '.release-lock'
        lock.mkdir()
        (lock / 'owner').write_text('another-release\n')
        self.assertNotEqual(self.run_release('bootstrap').returncode, 0)
        self.assertEqual((lock / 'owner').read_text().strip(), 'another-release')
        self.assertFalse(self.marker.exists())

    def test_missing_baseline_prevents_deploy(self):
        self.marker.unlink()
        self.assertNotEqual(self.run_release('deploy').returncode, 0)
        self.assertFalse((self.root / '.release-lock').exists())

    def test_foreign_deployment_cannot_rollback(self):
        lock = self.root / '.release-lock'
        lock.mkdir()
        (lock / 'owner').write_text('another-release\n')
        self.assertEqual(self.run_release('rollback').returncode, 0)
        self.assertTrue(lock.exists())
        self.assertFalse((self.root / 'docker.log').exists())


if __name__ == '__main__':
    unittest.main()
