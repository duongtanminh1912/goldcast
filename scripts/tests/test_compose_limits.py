"""Gioi han RAM cua Compose production va G2.

Backend chay -XX:MaxRAMPercentage=75: heap tinh theo gioi han cua container. Khong co
gioi han thi JVM tinh theo RAM ca VPS va gianh cho voi Postgres, Node va Caddy.
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
VPS_RAM_MB = 2048
# Phan de lai cho he dieu hanh, sshd, node exporter va bo thu trang thai VPS.
HOST_RESERVE_MB = 400
UNITS = {'k': 1 / 1024, 'm': 1, 'g': 1024}


def mem_limits(path):
    """{service: MB hoac None}, chi doc khoi services o muc thut dau 2 cach."""
    limits, current, in_services = {}, None, False
    for line in path.read_text().splitlines():
        if re.match(r'^\S', line):
            in_services = line.startswith('services:')
            current = None
            continue
        if not in_services:
            continue
        service = re.match(r'^  ([a-z][a-z0-9-]*):\s*$', line)
        if service:
            current = service.group(1)
            limits[current] = None
            continue
        limit = re.match(r'^    mem_limit:\s*"?(\d+)([kmg])"?', line, re.IGNORECASE)
        if current and limit:
            limits[current] = int(limit.group(1)) * UNITS[limit.group(2).lower()]
    return limits


class ComposeLimitsTest(unittest.TestCase):
    def test_every_production_service_is_capped_within_the_vps(self):
        limits = mem_limits(ROOT / 'docker-compose.prod.yml')
        self.assertEqual({'db', 'backend', 'frontend', 'caddy'}, set(limits))
        missing = sorted(name for name, mb in limits.items() if mb is None)
        self.assertEqual([], missing, 'service khong co mem_limit')
        self.assertLessEqual(sum(limits.values()), VPS_RAM_MB - HOST_RESERVE_MB)

    def test_g2_runs_the_application_under_the_production_caps(self):
        production = mem_limits(ROOT / 'docker-compose.prod.yml')
        g2 = mem_limits(ROOT / 'docker-compose.ci.yml')
        for service in ('db', 'backend', 'frontend'):
            self.assertEqual(production[service], g2.get(service), service)

    def test_parser_sees_a_missing_limit(self):
        sample = ROOT / 'scripts' / 'tests' / '.compose-limits-sample.yml'
        sample.write_text('services:\n  a:\n    image: x\n    mem_limit: 1g\n  b:\n    image: y\nvolumes:\n  c:\n')
        try:
            self.assertEqual({'a': 1024, 'b': None}, mem_limits(sample))
        finally:
            sample.unlink()


if __name__ == '__main__':
    unittest.main()
