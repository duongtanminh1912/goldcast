import { pathToFileURL } from 'node:url';

export async function checkPages(base, { releaseId, attempts = 5, retryDelayMs = 2000 } = {}) {
  if (!base) throw new Error('Missing base URL');
  if (!releaseId) throw new Error('Missing release identity');
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
  const response = await request('/api/release');
  const identity = response.ok ? await response.json() : {};
  if (!response.ok || identity.releaseId !== releaseId || identity.backendReleaseId !== releaseId) {
    throw new Error(`Public release mismatch: expected ${releaseId}`);
  }
  console.log(`OK public release ${releaseId}`);
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
  await checkPages(process.argv[2], { releaseId: process.argv[3] });
}
