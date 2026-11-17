import test from 'node:test';
import assert from 'node:assert/strict';
import { probe } from '../external-probe.mjs';

const html = '<html>Giá vàng trong nước · Lịch sử giá · Chi tiết từng bước dự báo</html>';

function mockFetch(t, implementation) {
  const original = globalThis.fetch;
  globalThis.fetch = implementation;
  t.after(() => { globalThis.fetch = original; });
}

const fast = { attempts: 2, retryDelayMs: 0 };

test('healthy site with one agreed release identity is up', async t => {
  mockFetch(t, async url => url.endsWith('/api/release')
    ? Response.json({ releaseId: 'abc-1', backendReleaseId: 'abc-1' }) : new Response(html));
  assert.equal(await probe('https://example.test', fast), 'abc-1');
});

test('frontend and backend serving different releases is down', async t => {
  mockFetch(t, async url => url.endsWith('/api/release')
    ? Response.json({ releaseId: 'abc-2', backendReleaseId: 'abc-1' }) : new Response(html));
  await assert.rejects(probe('https://example.test', fast), /disagree/);
});

test('an error page behind HTTP 200 is still down', async t => {
  mockFetch(t, async url => url.endsWith('/api/release')
    ? Response.json({ releaseId: 'abc-1', backendReleaseId: 'abc-1' })
    : new Response('<html>Giá vàng trong nước · Không tải được dữ liệu</html>'));
  await assert.rejects(probe('https://example.test', fast), /Page check failed/);
});

test('unreachable release endpoint is retried, then reported down', async t => {
  let calls = 0;
  mockFetch(t, async () => { calls++; throw new TypeError('fetch failed'); });
  await assert.rejects(probe('https://example.test', fast), /fetch failed/);
  assert.equal(calls, 2);
});

test('plain http is refused before any request', async t => {
  mockFetch(t, async () => { throw new Error('must not request'); });
  await assert.rejects(probe('http://example.test', fast), /https/);
});
