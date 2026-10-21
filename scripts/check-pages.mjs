import { lookup } from 'node:dns/promises';
import { networkInterfaces } from 'node:os';
import { pathToFileURL } from 'node:url';

// Legacy images have no release endpoint. Only explicitly adopted baselines may
// use this check, on a VPS whose public DNS addresses belong to its host network.
async function requireLocalOrigin(base) {
  const { hostname, protocol } = new URL(base);
  if (protocol !== 'https:') throw new Error('Legacy baseline requires HTTPS');
  const local = new Set(Object.values(networkInterfaces()).flat().map(i => i.address));
  const addresses = await lookup(hostname, { all: true });
  if (!addresses.length || addresses.some(a => !local.has(a.address))) {
    throw new Error('Legacy baseline DNS must resolve directly to this VPS; version cannot be verified through another host/CDN');
  }
}

export async function checkPages(base, { releaseId, legacyLocalOrigin = false, attempts = 5, retryDelayMs = 2000 } = {}) {
  if (!base) throw new Error('Missing base URL');
  if (!Number.isInteger(attempts) || attempts < 1 || attempts > 10) throw new Error('Invalid attempts');
  const options = () => ({
    signal: AbortSignal.timeout(15000), redirect: 'error',
    headers: { 'Cache-Control': 'no-cache', Pragma: 'no-cache' },
  });
  async function request(path) {
    for (let attempt = 1; ; attempt++) {
      try {
        const response = await fetch(base + path, options());
        if (![502, 503, 504].includes(response.status) || attempt === attempts) return response;
        await response.body?.cancel();
      } catch (error) {
        if (attempt === attempts) throw error;
      }
      await new Promise(resolve => setTimeout(resolve, retryDelayMs));
    }
  }
  if (releaseId) {
    const response = await request('/api/release');
    const identity = response.ok ? await response.json() : {};
    if (!response.ok || identity.releaseId !== releaseId || identity.backendReleaseId !== releaseId) {
      throw new Error(`Public release mismatch: expected ${releaseId}`);
    }
    console.log(`OK public release ${releaseId}`);
  } else if (legacyLocalOrigin) {
    await requireLocalOrigin(base);
  }
  for (const [path, marker] of [
    ['/', 'Giá vàng trong nước'],
    ['/instruments/XAUUSD', 'Lịch sử giá'],
    ['/forecast/XAUUSD?model=NAIVE&horizon=7', 'Chi tiết từng bước dự báo'],
  ]) {
    const response = await request(path);
    const html = await response.text();
    const rendered = html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '');
    const errors = ['Không tạo được dự báo', 'Không tải được dữ liệu', 'Chưa hiển thị được dữ liệu', 'Chưa có quan sát nào'];
    if (!response.ok || !rendered.includes(marker) || errors.some(error => rendered.includes(error))) {
      throw new Error(`Page check failed: ${path} (${response.status})`);
    }
    console.log(`OK page ${path}`);
  }
}

if ((process.argv[1] === '-' && import.meta.url.endsWith('/[eval1]')) || (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)) {
  const arg = process.argv[3];
  await checkPages(process.argv[2], {
    releaseId: arg === '--legacy-local-origin' ? undefined : arg,
    legacyLocalOrigin: arg === '--legacy-local-origin',
  });
}
