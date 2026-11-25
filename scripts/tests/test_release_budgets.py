"""Ngan sach thoi gian cua remote-release.sh phai chua du cac buoc ben trong no.

Neu rollback bi `timeout` giet sau `up` nhung truoc `verify`, khong ghi duoc
.last-good-release va khoa bi giu lai: phai chay `recover` bang tay. Test nay doc
cac con so tu chinh cac script va Jenkinsfile, nen doi mot cho ma quen cho kia la do.
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
# Thoi gian du cho cac buoc khong co timeout rieng, tren VPS 1 vCPU.
PULL_ALLOWANCE = 240    # compose pull hai image ung dung
VERIFY_ALLOWANCE = 120  # docker run probe + check-pages, ke ca vai lan thu lai 502-504


def read(path):
    return (ROOT / path).read_text()


def budgets():
    text = read('scripts/remote-release.sh')
    found = dict(re.findall(r'^\s*(deploy|verify|rollback)\) budget=(\d+);;', text, re.M))
    return {name: int(value) for name, value in found.items()}, text


def wait_timeout(operation):
    """--wait-timeout cua `compose up` trong nhanh case cua thao tac do trong release.sh."""
    text = read('scripts/release.sh')
    branch = re.search(r'^  %s\)\n(.*?)^    ;;' % operation, text, re.M | re.S)
    assert branch, operation
    return int(re.search(r'up -d --wait --wait-timeout (\d+)', branch.group(1)).group(1))


class ReleaseBudgetsTest(unittest.TestCase):
    def setUp(self):
        self.budget, self.remote = budgets()
        self.lock_wait = int(re.search(r'^lock_wait=(\d+)', self.remote, re.M).group(1))
        self.kill_after = int(re.search(r'^kill_after=(\d+)', self.remote, re.M).group(1))

    def test_every_operation_has_a_budget(self):
        self.assertEqual({'deploy', 'verify', 'rollback'}, set(self.budget))

    def test_deploy_budget_covers_pull_and_wait(self):
        self.assertGreaterEqual(self.budget['deploy'], PULL_ALLOWANCE + wait_timeout('deploy'))

    def test_rollback_budget_covers_wait_and_verify(self):
        self.assertGreaterEqual(self.budget['rollback'], wait_timeout('rollback') + VERIFY_ALLOWANCE)

    def test_verify_budget_covers_the_probes(self):
        self.assertGreaterEqual(self.budget['verify'], VERIFY_ALLOWANCE)

    def test_outer_timeout_is_derived_from_lock_wait_and_budget(self):
        self.assertIn('timeout $((lock_wait + budget + kill_after + 15)) flock -w "$lock_wait"', self.remote)
        self.assertIn('timeout --kill-after="$kill_after" "$budget"', self.remote)

    def test_jenkins_waits_longer_than_the_slowest_rollback(self):
        jenkins = read('Jenkinsfile')
        post = jenkins[jenkins.index('unsuccessful {'):]
        minutes = int(re.search(r"timeout\(time: (\d+), unit: 'MINUTES'\)", post).group(1))
        self.assertIn('$GATE rollback', post)  # cong chay remote-release.sh rollback
        slowest = self.lock_wait + self.budget['rollback'] + self.kill_after + 15
        self.assertGreater(minutes * 60, slowest)


if __name__ == '__main__':
    unittest.main()
