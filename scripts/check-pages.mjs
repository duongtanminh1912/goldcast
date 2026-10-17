// Check rendered pages: HTTP 200 alone also accepts the app's error/empty states.
const base = process.argv[2];
if (!base) throw new Error('Missing base URL');
for (const [path, marker] of [
  ['/', 'Giá vàng trong nước'],
  ['/instruments/XAUUSD', 'Lịch sử giá'],
  ['/forecast/XAUUSD?model=NAIVE&horizon=7', 'Chi tiết từng bước dự báo'],
]) {
  const response = await fetch(base + path, {signal: AbortSignal.timeout(90000), redirect: 'error'});
  const html = await response.text();
  const rendered = html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '');
  const errors = ['Không tạo được dự báo', 'Không tải được dữ liệu', 'Chưa hiển thị được dữ liệu', 'Chưa có quan sát nào'];
  if (!response.ok || !rendered.includes(marker) || errors.some(error => rendered.includes(error))) {
    throw new Error(`Page check failed: ${path} (${response.status})`);
  }
  console.log(`OK page ${path}`);
}
