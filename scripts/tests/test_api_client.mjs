// frontend/src/lib/api.ts: moi loi goi backend co gioi han thoi gian, va trang cong khai
// khong bao gio thay URL noi bo. Node 22 tu bo kieu TypeScript nen import thang file .ts.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

process.env.API_TIMEOUT_MS = '200';

async function serve(handler) {
  const sockets = new Set();
  const server = http.createServer(handler);
  server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return {
    base: `http://127.0.0.1:${server.address().port}`,
    close: () => { for (const socket of sockets) socket.destroy(); return new Promise(resolve => server.close(resolve)); },
  };
}

async function client(base) {
  process.env.API_BASE_URL_INTERNAL = base;
  // Moi base mot ban module rieng, vi BASE_URL doc mot lan khi nap.
  return import(`../../frontend/src/lib/api.ts?base=${encodeURIComponent(base)}`);
}

async function failure(promise) {
  const started = Date.now();
  const originalError = console.error;
  const logged = [];
  console.error = (...args) => logged.push(args.map(String).join(' '));
  try {
    await promise;
  } catch (error) {
    return { error, elapsed: Date.now() - started, logged };
  } finally {
    console.error = originalError;
  }
  assert.fail('request should have failed');
}

test('a backend that never answers fails fast with a public message', { timeout: 5000 }, async t => {
  const backend = await serve(() => {});
  t.after(backend.close);
  const { api, ApiError } = await client(backend.base);
  const { error, elapsed, logged } = await failure(api.instruments());
  assert.ok(error instanceof ApiError);
  assert.equal(error.status, 0);
  assert.match(error.message, /không phản hồi kịp/);
  assert.ok(!error.message.includes('127.0.0.1'), error.message);
  assert.ok(elapsed < 2000, `took ${elapsed} ms`);
  assert.ok(logged.some(line => line.includes(backend.base)), 'URL belongs in the server log');
});

test('a backend that stalls after the headers ends the same way', { timeout: 5000 }, async t => {
  const backend = await serve((request, response) => {
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.write('[');
  });
  t.after(backend.close);
  const { api, ApiError } = await client(backend.base);
  const { error, elapsed } = await failure(api.instruments());
  assert.ok(error instanceof ApiError);
  assert.match(error.message, /không phản hồi kịp/);
  assert.ok(elapsed < 2000, `took ${elapsed} ms`);
});

test('a refused connection never shows the internal address', { timeout: 5000 }, async t => {
  const backend = await serve(() => {});
  const base = backend.base;
  await backend.close();
  const { api } = await client(base);
  const { error } = await failure(api.summary());
  assert.match(error.message, /Không kết nối được tới máy chủ dữ liệu/);
  assert.ok(!error.message.includes(base), error.message);
});

test('problem documents still reach the page', { timeout: 5000 }, async t => {
  const backend = await serve((request, response) => {
    response.writeHead(422, { 'Content-Type': 'application/problem+json' });
    response.end(JSON.stringify({ status: 422, title: 'Insufficient history', detail: 'Chưa đủ dữ liệu.' }));
  });
  t.after(backend.close);
  const { api } = await client(backend.base);
  const { error } = await failure(api.forecast('SJC_HCM'));
  assert.equal(error.status, 422);
  assert.ok(error.isInsufficientHistory);
  assert.equal(error.message, 'Chưa đủ dữ liệu.');
});
