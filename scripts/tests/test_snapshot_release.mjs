import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, statSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { captureRelease } from '../snapshot-release.mjs';
import { runtimeFixture } from './runtime_fixture.mjs';

test('baseline keeps live seed=true, literal secrets and all four image digests', () => {
  const { containers, images } = runtimeFixture();
  const { compose, manifest, runtimeEnv } = captureRelease(containers, images);
  assert.equal(compose.services.backend.environment.APP_INGEST_SEED_ON_EMPTY, 'true');
  assert.equal(compose.services.backend.environment.APP_ADMIN_TOKEN, 'secret$$literal');
  assert.equal(manifest.RELEASE_ID, 'previous-122');
  assert.equal(runtimeEnv.POSTGRES_PASSWORD, 'database-secret');
  assert.equal(runtimeEnv.APP_INGEST_SEED_ON_EMPTY, 'true');
  for (const service of ['db', 'backend', 'frontend', 'caddy']) {
    assert.match(compose.services[service].image, /@sha256:/);
  }
});

test('baseline retains DB volume identity, Caddy config, healthcheck and command', () => {
  const { containers, images } = runtimeFixture();
  const { compose } = captureRelease(containers, images);
  assert.deepEqual(compose.volumes['goldcast-pgdata'], { name: 'goldcast_goldcast-pgdata' });
  assert.equal(compose.services.db.volumes[0].source, 'goldcast-pgdata');
  assert.equal(compose.services.caddy.volumes[0].source, './deploy/Caddyfile');
  assert.equal(compose.services.db.healthcheck.interval, '5000000000ns');
  assert.deepEqual(compose.services.frontend.command, ['run-frontend']);
  assert.equal(compose.services.backend.mem_limit, 268435456);
});

test('legacy mode is explicit when the running image has no release identity', () => {
  const { containers, images } = runtimeFixture();
  containers[2].Config.Env = ['API_BASE_URL_INTERNAL=http://backend:8080'];
  assert.equal(captureRelease(containers, images).legacy, true);
});

test('unexpected volume, network or published application port rejects adoption', () => {
  for (const change of [
    state => { state.containers[0].Mounts[0].Name = 'other-database'; },
    state => { state.containers[0].Mounts = []; },
    state => { state.containers[1].NetworkSettings.Networks = { other: {} }; },
    state => { state.containers[1].HostConfig.PortBindings = { '8080/tcp': [{ HostPort: '8080' }] }; },
    state => { state.containers[0].Config.Healthcheck = null; },
  ]) {
    const state = runtimeFixture(); change(state);
    assert.throws(() => captureRelease(state.containers, state.images));
  }
});

test('missing registry digest cannot create a baseline', () => {
  const { containers, images } = runtimeFixture(); images[0].RepoDigests = [];
  assert.throws(() => captureRelease(containers, images), /Registry digest required/);
});

test('snapshot CLI stores private configuration without printing its secrets', t => {
  const directory = mkdtempSync(join(tmpdir(), 'goldcast-snapshot-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const { containers, images } = runtimeFixture();
  writeFileSync(join(directory, 'containers.json'), JSON.stringify(containers));
  writeFileSync(join(directory, 'images.json'), JSON.stringify(images));
  const result = spawnSync(process.execPath, [resolve('scripts/snapshot-release.mjs'), directory], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.ok(!result.stdout.includes('secret'));
  assert.equal(statSync(join(directory, 'docker-compose.prod.yml')).mode & 0o777, 0o600);
  assert.equal(statSync(join(directory, 'runtime.env')).mode & 0o777, 0o600);
  assert.ok(readFileSync(join(directory, 'release.env'), 'utf8').includes('RELEASE_ID=previous-122'));
});
