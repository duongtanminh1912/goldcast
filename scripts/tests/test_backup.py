from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'backup.py'


class BackupTest(unittest.TestCase):
    def test_backup_cannot_store_secrets_inside_checkout(self):
        result = subprocess.run(['python3', str(SCRIPT), '--key', '/nonexistent',
                                 '--destination', str(SCRIPT.parent)], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('outside the Git checkout', result.stderr)
        self.assertNotIn('Capturing', result.stdout)

    def test_public_destination_is_rejected_before_ssh(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            path.chmod(0o755)
            result = subprocess.run(['python3', str(SCRIPT), '--key', '/nonexistent',
                                     '--destination', directory], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('mode 700', result.stderr)
            self.assertEqual(list(path.iterdir()), [])

