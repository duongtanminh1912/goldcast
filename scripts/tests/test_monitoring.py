"""Operational signals must fail closed and must not mistake cache for fresh data."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


jenkins = module('jenkins_exporter', 'monitoring/jenkins_exporter.py')
vps = module('vps_collector', 'monitoring/vps/collect.py')


class JenkinsMetricsTest(unittest.TestCase):
    def test_failure_preserves_age_of_last_success_and_only_monitors_real_jobs(self):
        data = {'lastCompletedBuild': {'result': 'FAILURE'},
                'lastSuccessfulBuild': {'timestamp': 100000, 'duration': 5000},
                'lastBuild': {'building': True, 'timestamp': 200000}}
        calls = []
        def fetch(job):
            calls.append(job)
            return data
        output = jenkins.collect(fetch, now=250)
        self.assertEqual(set(calls), {'goldcast-ci', 'goldcast-backup'})
        self.assertIn('goldcast_jenkins_job_last_result_success{job_name="goldcast-ci"} 0', output)
        self.assertIn('goldcast_jenkins_job_last_success_timestamp_seconds{job_name="goldcast-backup"} 105', output)
        self.assertIn('goldcast_jenkins_job_running_seconds{job_name="goldcast-ci"} 50', output)

    def test_api_error_never_exports_a_cached_success(self):
        def fetch(job):
            raise OSError('private error detail must not escape')
        output = jenkins.collect(fetch)
        self.assertEqual(output.count('scrape_success'), 2)
        self.assertNotIn('last_result_success', output)
        self.assertNotIn('private', output)

    def test_no_successful_build_is_explicitly_zero(self):
        output = jenkins.render('goldcast-backup', {}, now=100)
        self.assertIn('last_success_timestamp_seconds{job_name="goldcast-backup"} 0', output)
        self.assertIn('has_completed_build{job_name="goldcast-backup"} 0', output)


class VpsMetricsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.release = self.root / 'releases' / 'test-1'
        self.release.mkdir(parents=True)
        self.manifest = ('RELEASE_ID=test-1\n'
                         'BACKEND_IMAGE=ghcr.io/duongtanminh1912/goldcast-backend@sha256:' + 'a' * 64 + '\n'
                         'FRONTEND_IMAGE=ghcr.io/duongtanminh1912/goldcast-frontend@sha256:' + 'b' * 64 + '\n')
        (self.release / 'release.env').write_text(self.manifest)
        (self.root / '.last-good-release').write_text(str(self.release))
        self.items = [dict(name='/goldcast-' + name, image='sha256:actual', running=True,
                           health='healthy', restarts=0, oom=False) for name in vps.SERVICES]

    def command(self, *args):
        return '\n'.join(json.dumps(i) for i in self.items) if args[0] == 'inspect' else 'sha256:actual\n'

    def collect(self, **kwargs):
        return vps.collect(self.root, 'https://example.test', command=self.command,
                           fetch=lambda _: {'releaseId': 'test-1', 'backendReleaseId': 'test-1'}, **kwargs)

    def test_healthy_and_down_container_are_distinct(self):
        self.assertIn('goldcast_vps_collector_success 1', self.collect(now=100))
        self.items[1]['running'] = False
        output = self.collect(now=200)
        self.assertIn('goldcast_container_ready{service="backend"} 0', output)
        self.assertIn('goldcast_vps_collector_last_run_timestamp_seconds 200', output)

    def test_digest_and_public_identity_mismatch(self):
        output = vps.collect(self.root, 'https://example.test',
                             command=lambda *args: self.command(*args) if args[0] == 'inspect' else 'wrong',
                             fetch=lambda _: {'releaseId': 'new', 'backendReleaseId': 'test-1'})
        self.assertIn('goldcast_container_image_matches_manifest{service="backend"} 0', output)
        self.assertIn('goldcast_public_release_matches_manifest 0', output)

    def test_duplicate_manifest_and_external_pointer_fail_closed(self):
        (self.release / 'release.env').write_text(self.manifest + 'RELEASE_ID=duplicate\n')
        self.assertIn('goldcast_release_state_read_success 0', self.collect())
        (self.root / '.last-good-release').write_text('/other/releases/test-1')
        self.assertIn('goldcast_vps_collector_success 0', self.collect())

    def test_docker_failure_and_public_probe_error_have_explicit_signals(self):
        def failure(*_):
            raise OSError('secret detail')
        output = vps.collect(self.root, 'https://example.test', command=failure, fetch=failure)
        self.assertIn('goldcast_container_inspect_success 0', output)
        self.assertIn('goldcast_public_identity_probe_success 0', output)
        self.assertNotIn('secret', output)

    def test_atomic_replace_leaves_only_complete_prometheus_file(self):
        path = self.root / 'textfile' / 'goldcast.prom'
        vps.write_atomic(path, 'first 1\n')
        vps.write_atomic(path, 'second 2\n')
        self.assertEqual(path.read_text(), 'second 2\n')
        self.assertEqual(list(path.parent.iterdir()), [path])
        self.assertEqual(path.stat().st_mode & 0o777, 0o644)
