import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { runtimeFixture } from './runtime_fixture.mjs';

function setup(t) {
  const root = mkdtempSync(join(tmpdir(), 'goldcast-bootstrap-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const bin = join(root, 'bin'); mkdirSync(bin);
  const fixture = runtimeFixture();
  writeFileSync(join(root, '.env'), 'APP_INGEST_SEED_ON_EMPTY=false\n');
  writeFileSync(join(root, 'fixture.json'), JSON.stringify(fixture));
  writeFileSync(join(bin, 'docker'), `#!/usr/bin/env node
import fs from 'node:fs';
import {spawnSync} from 'node:child_process';
const root=process.env.GOLDCAST_ROOT;
const args=process.argv.slice(2);
if(!fs.existsSync(root+'/.release-lock/owner'))process.exit(97);
const {containers,images}=JSON.parse(fs.readFileSync(root+'/fixture.json','utf8'));
fs.appendFileSync(root+'/docker.log',args.join(' ')+'\\n');
if(args[0]==='inspect'){
  if(args[1]!=='--format'){console.log(JSON.stringify(containers));process.exit(0);}
  const c=containers.find(c=>c.Name==='/'+args[3]);const fmt=args[2];
  if(fmt.includes('com.docker.compose.project')) console.log(c.Config.Labels['com.docker.compose.project']);
  else if(fmt.includes('Mounts')) console.log(c.Mounts[0].Name);
  else console.log(c.Image);
}else if(args[0]==='image'&&args[1]==='inspect'){
  if(args[2]!=='--format')console.log(JSON.stringify(images));
  else console.log(images.find(i=>i.Id===args[4]||i.RepoDigests.includes(args[4])).Id);
}else if(args[0]==='cp'){
  fs.writeFileSync(args[2],'goldcast.click { reverse_proxy frontend:3000 }');
}else if(args[0]==='run'){
  const script=fs.readFileSync(0,'utf8');
  if(args.includes('none')){
    const mount=args[args.indexOf('--mount')+1];
    const dir=mount.split(',').find(v=>v.startsWith('src=')).slice(4);
    const result=spawnSync(process.execPath,['--input-type=module','-',dir],{input:script,encoding:'utf8'});
    process.stdout.write(result.stdout);process.stderr.write(result.stderr);process.exit(result.status);
  }
}else if(args[0]==='compose'){
  if(args.includes('ps'))console.log('goldcast-'+args.at(-1));
  else if(args.includes('exec'))console.log('{"status":"UP"}');
  else process.exit(99);
}else process.exit(98);
`, { mode: 0o755 });
  return { root, fixture, run(...args) {
    writeFileSync(join(root, 'fixture.json'), JSON.stringify(fixture));
    return spawnSync('sh', [resolve('scripts/bootstrap-release.sh'), ...args], {
      encoding: 'utf8', env: { ...process.env, GOLDCAST_ROOT: root, PATH: bin + ':' + process.env.PATH },
    });
  } };
}

test('real bootstrap wrapper captures runtime instead of the newer checkout defaults', t => {
  const state = setup(t); const result = state.run();
  assert.equal(result.status, 0, result.stderr);
  const release = readFileSync(join(state.root, '.last-good-release'), 'utf8').trim();
  const snapshot = JSON.parse(readFileSync(join(release, 'docker-compose.prod.yml'), 'utf8'));
  assert.equal(snapshot.services.backend.environment.APP_INGEST_SEED_ON_EMPTY, 'true');
  assert.equal(snapshot.services.backend.environment.APP_ADMIN_TOKEN, 'secret$$literal');
  assert.ok(readFileSync(join(release, 'release.env'), 'utf8').includes('RELEASE_ID=previous-122'));
  assert.ok(readFileSync(join(release, 'runtime.env'), 'utf8').includes("APP_INGEST_SEED_ON_EMPTY='true'"));
  const log = readFileSync(join(state.root, 'docker.log'), 'utf8');
  assert.ok(!log.includes('up -d') && !log.includes('restart'));
  assert.ok(!existsSync(join(state.root, '.release-lock')));
});

test('explicit refresh replaces an old baseline only after runtime verification', t => {
  const state = setup(t);
  writeFileSync(join(state.root, '.last-good-release'), join(state.root, 'releases/old') + '\n');
  const result = state.run('--refresh');
  assert.equal(result.status, 0, result.stderr);
  const release = readFileSync(join(state.root, '.last-good-release'), 'utf8').trim();
  assert.ok(release.includes('/releases/bootstrap-'));
  assert.ok(!readFileSync(join(state.root, 'docker.log'), 'utf8').includes('up -d'));
});

test('wrapper marks a pre-identity baseline for direct-origin verification', t => {
  const state = setup(t);
  state.fixture.containers[2].Config.Env = ['API_BASE_URL_INTERNAL=http://backend:8080'];
  const result = state.run(); assert.equal(result.status, 0, result.stderr);
  const release = readFileSync(join(state.root, '.last-good-release'), 'utf8').trim();
  assert.ok(existsSync(join(release, 'legacy-local-origin')));
  assert.ok(readFileSync(join(state.root, 'docker.log'), 'utf8').includes('--legacy-local-origin'));
});

test('wrapper refuses another Compose project without recording a baseline', t => {
  const state = setup(t);
  state.fixture.containers[1].Config.Labels['com.docker.compose.project'] = 'other';
  assert.notEqual(state.run().status, 0);
  assert.ok(!existsSync(join(state.root, '.last-good-release')));
  assert.ok(!existsSync(join(state.root, '.release-lock')));
});

test('wrapper rejects an application from another repository and releases its lock', t => {
  const state = setup(t);
  state.fixture.images[1].RepoDigests = [`ghcr.io/another-owner/goldcast-backend@sha256:${'2'.repeat(64)}`];
  const result = state.run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /invalid repository or SHA-256 digest for BACKEND_IMAGE/);
  assert.ok(!existsSync(join(state.root, '.last-good-release')));
  assert.ok(!existsSync(join(state.root, '.release-lock')));
  assert.doesNotMatch(readFileSync(join(state.root, 'docker.log'), 'utf8'), /^compose /m);
});

test('wrapper cannot capture while another deployment owns the lock', t => {
  const state = setup(t);
  mkdirSync(join(state.root, '.release-lock'));
  writeFileSync(join(state.root, '.release-lock/owner'), 'another-deployment\n');
  assert.notEqual(state.run().status, 0);
  assert.equal(readFileSync(join(state.root, '.release-lock/owner'), 'utf8'), 'another-deployment\n');
  assert.ok(!existsSync(join(state.root, 'docker.log')));
});
