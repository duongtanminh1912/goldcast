"""Nhung dieu Jenkinsfile phai giu dung ma GitHub Actions khong thay duoc.

GitHub chay G1 tren ca repo, con Jenkins chi dua vao container Python nhung gi lenh tar
liet ke. Mot test doc file ngoai danh sach do xanh tren GitHub nhung do tren Jenkins,
va main khong bao gio duoc deploy (da xay ra voi test_release_budgets.py).
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
TESTS = ROOT / 'scripts' / 'tests'


def jenkinsfile():
    return (ROOT / 'Jenkinsfile').read_text()


def python_test_inputs(text):
    """Cac muc tar dua vao container chay `unittest discover -s scripts/tests`."""
    for match in re.finditer(r"tar -cf - ([^|\n]+)\|\s*\n\s*docker run --rm -i python:", text):
        return set(match.group(1).split())
    raise AssertionError('Khong tim thay buoc tar cua test Python trong Jenkinsfile')


def files_read_by_tests():
    """Duong dan tuong doi goc repo ma cac test Python doc qua ROOT hoac read()."""
    found = {}
    for test in TESTS.glob('test_*.py'):
        source = test.read_text()
        for path in re.findall(r"ROOT\s*/\s*'([^']+)'", source) + re.findall(r"\bread\('([^']+)'\)", source):
            found.setdefault(path, test.name)
    return found


class JenkinsTestInputsTest(unittest.TestCase):
    def test_every_file_a_python_test_reads_reaches_the_jenkins_container(self):
        inputs = python_test_inputs(jenkinsfile())
        missing = {path: test for path, test in files_read_by_tests().items()
                   if path.split('/')[0] not in inputs}
        self.assertEqual(missing, {}, f'Them vao lenh tar cua G1 trong Jenkinsfile: {sorted(inputs)}')

    def test_the_scan_sees_the_files_known_to_be_read(self):
        # Neu regex hong thi test tren xanh vo nghia.
        read = files_read_by_tests()
        for path in ('Jenkinsfile', 'docker-compose.prod.yml', 'docker-compose.ci.yml', 'deploy/known_hosts'):
            self.assertIn(path, read)

    def test_a_missing_input_is_reported(self):
        text = jenkinsfile().replace(' Jenkinsfile docker-compose.prod.yml', ' docker-compose.prod.yml')
        self.assertNotIn('Jenkinsfile', python_test_inputs(text))


class SshHostKeyTest(unittest.TestCase):
    def ssh_options(self):
        match = re.search(r"SSH_OPTS = '([^']+)'", jenkinsfile())
        self.assertIsNotNone(match, 'SSH_OPTS phai khai bao mot lan trong environment')
        return match.group(1).split()

    def test_host_key_is_checked_against_the_pinned_file(self):
        options = self.ssh_options()
        self.assertIn('StrictHostKeyChecking=yes', options)
        self.assertIn('UserKnownHostsFile=deploy/known_hosts', options)

    def test_every_ssh_call_uses_the_pinned_options(self):
        text = jenkinsfile()
        calls = re.findall(r'\bssh -i "\$SSH_KEY"([^\n]*)', text)
        self.assertGreaterEqual(len(calls), 4)
        for call in calls:
            self.assertTrue(call.lstrip().startswith('$SSH_OPTS'), call)
        self.assertNotRegex(text, r'StrictHostKeyChecking=(no|accept-new)')

    def test_every_remote_command_goes_through_the_gate(self):
        # Khoa nay bi VPS gioi han vao goldcast-gate; lenh nao khac se bi tu choi luc deploy.
        text = jenkinsfile()
        self.assertIn("GATE = '/usr/local/bin/goldcast-gate'", text)
        remote = re.findall(r'\$SSH_OPTS "\$VPS" "([^"]*)"', text)
        self.assertGreaterEqual(len(remote), 4)
        for command in remote:
            self.assertRegex(command, r'^\$GATE (receive|deploy|verify|rollback) \$RELEASE_ID( \$BUILD_URL)?$')

    def test_uploaded_release_files_are_exactly_what_the_gate_accepts(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('gate', ROOT / 'deploy/goldcast-gate.py')
        gate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(gate)
        upload = re.search(r'tar -cf - (release\.env [^|]+)\|\s*\n\s*ssh [^\n]*\$GATE receive', jenkinsfile())
        self.assertIsNotNone(upload)
        self.assertEqual(set(upload.group(1).split()), gate.DATA_FILES | gate.CODE_FILES)

    def test_backup_never_sends_a_script_to_run(self):
        source = (ROOT / 'scripts/backup.py').read_text()
        self.assertNotIn("'sh -s", source)
        self.assertEqual(set(re.findall(r"GATE \+ ' (backup \w+)", source)),
                         {'backup snapshot', 'backup fetch', 'backup confirm'})

    def test_pinned_file_has_a_key_for_the_deploy_host(self):
        host = re.search(r"VPS = '[^@']+@([^']+)'", jenkinsfile()).group(1)
        entries = [line.split() for line in (ROOT / 'deploy/known_hosts').read_text().splitlines()
                   if line.strip() and not line.startswith('#')]
        self.assertTrue(entries)
        for names, key_type, key in entries:
            self.assertIn(host, names.split(','))
            self.assertIn(key_type, ('ssh-rsa', 'ssh-ed25519', 'ecdsa-sha2-nistp256'))
            self.assertRegex(key, r'^AAAA[0-9A-Za-z+/=]+$')


if __name__ == '__main__':
    unittest.main()
