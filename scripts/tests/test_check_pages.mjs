import test from 'node:test';
import assert from 'node:assert/strict';
import { checkPages } from '../check-pages.mjs';

const html = '<html>Giá vàng trong nước · Lịch sử giá · Chi tiết từng bước dự báo</html>';
const page = url => url.includes('KHONG-TON-TAI') ? new Response('missing', { status: 404 }) : new Response(html);

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
      ? Response.json({ releaseId: 'candidate-123', backendReleaseId: 'candidate-123' }) : page(url);
  });
  await checkPages('https://example.test', { releaseId: 'candidate-123' });
  assert.equal(calls.length, 5);
});

test('old public release fails even when every page would look healthy', async t => {
  const calls = [];
  mockFetch(t, async url => {
    calls.push(url);
    return url.endsWith('/api/release')
      ? Response.json({ releaseId: 'previous-122' }) : page(url);
  });
  await assert.rejects(checkPages('https://example.test', { releaseId: 'candidate-123' }), /Public release mismatch/);
  assert.equal(calls.length, 1);
});

test('missing release endpoint is rejected', async t => {
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
      ? Response.json({ releaseId: 'new', backendReleaseId: 'new' }) : page(url);
  });
  await checkPages('https://example.test', { releaseId: 'new', retryDelayMs: 0 });
  assert.equal(calls, 6);
});

test('persistent unavailable release stops after the configured retry budget', async t => {
  let calls = 0;
  mockFetch(t, async () => { calls++; return new Response('unavailable', { status: 503 }); });
  await assert.rejects(checkPages('https://example.test', { releaseId: 'new', attempts: 2, retryDelayMs: 0 }), /mismatch/);
  assert.equal(calls, 2);
});

test('missing release identity is rejected before any request', async t => {
  mockFetch(t, async () => { throw new Error('must not request'); });
  await assert.rejects(checkPages('https://example.test', {}), /Missing release identity/);
});

test('an unknown code rendered as HTTP 200 fails the check', async t => {
  mockFetch(t, async url => url.endsWith('/api/release')
    ? Response.json({ releaseId: 'candidate-123', backendReleaseId: 'candidate-123' })
    : new Response(html));
  await assert.rejects(checkPages('https://example.test', { releaseId: 'candidate-123' }),
    /G3-KHONG-TON-TAI should be 404 \(200\)/);
});

test('a page answering HTTP 500 fails even if its body looks healthy', async t => {
  mockFetch(t, async url => url.endsWith('/api/release')
    ? Response.json({ releaseId: 'candidate-123', backendReleaseId: 'candidate-123' })
    : url.includes('/forecast/') ? new Response(html, { status: 500 }) : page(url));
  await assert.rejects(checkPages('https://example.test', { releaseId: 'candidate-123' }), /forecast.*\(500\)/);
});
