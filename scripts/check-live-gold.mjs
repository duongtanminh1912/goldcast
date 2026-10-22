import assert from 'node:assert/strict';

const base = process.argv[2];
const source = 'http://live-gold-source:8000';
const publicBase = 'http://caddy:8080';
const endpoint = '/api/v1/market/world/live?limit=120';
const options = () => ({ cache: 'no-store', signal: AbortSignal.timeout(8000) });
async function json(url, extra = {}) {
  const response = await fetch(url, { ...options(), ...extra });
  assert.equal(response.status, 200, url);
  return response.json();
}
async function waitFor(predicate) {
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    const snapshot = await json(base + endpoint);
    if (predicate(snapshot)) return snapshot;
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw new Error('Live quote did not reach the expected state');
}

await json(source + '/control/normal', { method: 'POST' });
let data = await waitFor(s => s.status === 'FRESH' && s.lastFetchSucceeded);
assert.equal(data.symbol, 'XAUUSD');
assert.equal(data.currency, 'USD');
assert.equal(data.unit, 'USD_PER_TROY_OUNCE');
assert.equal(data.source, 'gold-api.com');
assert.equal(data.latest.usdPerOunce, 2500.1256);
assert.ok(Math.abs(Date.now() - Date.parse(data.latest.observedAt)) < 180000);
assert.ok(data.points.every((point, i) => i === 0 || Date.parse(point.observedAt) > Date.parse(data.points[i - 1].observedAt)));
assert.equal(new Set(data.points.map(p => Math.floor(Date.parse(p.observedAt) / 60000))).size, data.points.length);
const response = await fetch(base + endpoint, options());
assert.match(response.headers.get('cache-control'), /no-store/);
assert.equal((await fetch(base + '/api/v1/market/world/live?limit=0', options())).status, 400);
assert.equal((await fetch(publicBase + '/api/v1/market/world/live?limit=1441', options())).status, 400);
assert.equal((await json(publicBase + endpoint)).latest.usdPerOunce, 2500.1256);

const countBefore = (await json(source + '/calls')).calls;
const start = Date.now();
await Promise.all(Array.from({ length: 10 }, () => json(base + endpoint)));
const countAfter = (await json(source + '/calls')).calls;
assert.ok(countAfter - countBefore <= Math.ceil((Date.now() - start) / (data.pollIntervalSeconds * 1000)) + 2,
  'Visitor reads unexpectedly called the upstream');

const frozen = await json(source + '/control/freeze', { method: 'POST' });
data = await waitFor(s => s.lastFetchSucceeded && Date.parse(s.latest.observedAt) === Date.parse(frozen.updatedAt));
const observedAt = data.latest.observedAt;
await json(source + '/control/fail', { method: 'POST' });
const failed = await waitFor(s => !s.lastFetchSucceeded);
assert.equal(failed.latest.observedAt, observedAt, 'Outage changed the source timestamp');
assert.equal(failed.latest.usdPerOunce, 2500.1256);
await json(source + '/control/future', { method: 'POST' });
// Require a completed poll that started after future mode was confirmed, rather
// than accidentally observing an in-flight failure from the preceding mode.
const futureSince = Date.now();
const future = await waitFor(s => Date.parse(s.lastAttemptAt) >= futureSince && !s.lastFetchSucceeded);
assert.equal(future.latest.observedAt, observedAt, 'Future quote replaced the valid observation');
await json(source + '/control/normal', { method: 'POST' });
await waitFor(s => s.lastFetchSucceeded && s.status === 'FRESH');
console.log('PASS live gold: source contract, timestamp/units, minute dedup, read-only visitors, public proxy, outage cache and future rejection');
