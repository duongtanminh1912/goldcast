import test from 'node:test';
import assert from 'node:assert/strict';
import { checkPages } from '../check-pages.mjs';

const html = '<html>Giá vàng trong nước · Lịch sử giá · Chi tiết từng bước dự báo</html>';

function mockFetch(t, implementation) {
  const original = globalThis.fetch;
  globalThis.fetch = implementation;
  t.after(() => { globalThis.fetch = original; });
}

test('matching public release and all three rendered pages pass', async t => {
  const calls = [];
  mockFetch(t, async (url, options) => {
    calls.push(url);
    assert.equal(options.redirect, 'error');
    assert.equal(options.headers['Cache-Control'], 'no-cache');
    return url.endsWith('/api/release')
      ? Response.json({ releaseId: 'candidate-123', backendReleaseId: 'candidate-123' }) : new Response(html);
  });
  await checkPages('https://example.test', { releaseId: 'candidate-123' });
  assert.equal(calls.length, 4);
});

test('old public release fails even when every page would look healthy', async t => {
  const calls = [];
  mockFetch(t, async url => {
    calls.push(url);
    return url.endsWith('/api/release')
      ? Response.json({ releaseId: 'previous-122' }) : new Response(html);
  });
  await assert.rejects(checkPages('https://example.test', { releaseId: 'candidate-123' }), /Public release mismatch/);
  assert.equal(calls.length, 1);
});

test('missing release endpoint is not accepted as a legacy release', async t => {
  mockFetch(t, async () => new Response('not found', { status: 404 }));
  await assert.rejects(checkPages('https://example.test', { releaseId: 'candidate-123' }), /Public release mismatch/);
});

test('matching identity cannot hide a rendered error page', async t => {
  mockFetch(t, async url => url.endsWith('/api/release')
    ? Response.json({ releaseId: 'candidate-123', backendReleaseId: 'candidate-123' })
    : new Response(html + 'Không tải được dữ liệu'));
  await assert.rejects(checkPages('https://example.test', { releaseId: 'candidate-123' }), /Page check failed/);
});

test('matching frontend cannot hide an old backend behind it', async t => {
  mockFetch(t, async () => Response.json({ releaseId: 'candidate-123', backendReleaseId: 'previous-122' }));
  await assert.rejects(checkPages('https://example.test', { releaseId: 'candidate-123' }), /Public release mismatch/);
});

test('temporary proxy startup failure retries and then verifies identity', async t => {
  let calls = 0;
  mockFetch(t, async url => {
    if (++calls === 1) return new Response('starting', { status: 502 });
    return url.endsWith('/api/release')
      ? Response.json({ releaseId: 'new', backendReleaseId: 'new' }) : new Response(html);
  });
  await checkPages('https://example.test', { releaseId: 'new', retryDelayMs: 0 });
  assert.equal(calls, 5);
});

test('persistent unavailable release stops after the configured retry budget', async t => {
  let calls = 0;
  mockFetch(t, async () => { calls++; return new Response('unavailable', { status: 503 }); });
  await assert.rejects(checkPages('https://example.test', { releaseId: 'new', attempts: 2, retryDelayMs: 0 }), /mismatch/);
  assert.equal(calls, 2);
});

test('legacy baseline requires HTTPS before attempting DNS or page requests', async t => {
  mockFetch(t, async () => { throw new Error('must not request'); });
  await assert.rejects(checkPages('http://127.0.0.1', { legacyLocalOrigin: true }), /requires HTTPS/);
});

test('explicit legacy baseline can check the local HTTPS origin', async t => {
  mockFetch(t, async () => new Response(html));
  await checkPages('https://127.0.0.1', { legacyLocalOrigin: true });
});

test('legacy baseline cannot bless pages from a different public host', async t => {
  mockFetch(t, async () => { throw new Error('must not request'); });
  await assert.rejects(checkPages('https://203.0.113.254', { legacyLocalOrigin: true }), /directly to this VPS/);
});
