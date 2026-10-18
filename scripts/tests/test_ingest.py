"""Check the operator command without starting Docker or contacting a feed."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'ingest.sh'


class IngestCommandTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.args = self.root / 'request.json'
        # Execute the same shell command that would run inside backend.
        docker = self.root / 'docker'
        docker.write_text('''#!/bin/sh
test "$1 $2 $3 $4" = 'compose exec -T backend' || exit 99
shift 4
exec "$@"
''')
        wget = self.root / 'wget'
        wget.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
Path(os.environ['REQUEST_ARGS']).write_text(json.dumps(sys.argv[1:]))
sys.exit(int(os.environ.get('HTTP_FAILURE', '0')))
''')
        docker.chmod(0o755)
        wget.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.root) + ':' + os.environ['PATH'],
                        REQUEST_ARGS=str(self.args))

    def run_ingest(self, token, **env):
        return subprocess.run(['sh', str(SCRIPT)],
                              env=dict(self.env, APP_ADMIN_TOKEN=token, **env),
                              capture_output=True, text=True)

    def test_missing_token_fails_before_request(self):
        result = self.run_ingest('')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Set APP_ADMIN_TOKEN', result.stderr)
        self.assertFalse(self.args.exists())

    def test_posts_with_token_without_printing_it(self):
        token = 'test-admin-secret'
        result = self.run_ingest(token)
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads(self.args.read_text())
        self.assertIn('X-Admin-Token: ' + token, args)
        self.assertIn('--post-data=', args)
        self.assertEqual(args[-1], 'http://localhost:8080/api/v1/admin/ingest')
        self.assertNotIn(token, result.stdout + result.stderr)

    def test_http_failure_is_not_reported_as_success(self):
        self.assertNotEqual(self.run_ingest('configured-token', HTTP_FAILURE='1').returncode, 0)


if __name__ == '__main__':
    unittest.main()
