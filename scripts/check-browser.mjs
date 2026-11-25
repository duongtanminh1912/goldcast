// Kiem bang trinh duyet that (Chromium qua Playwright) nhung gi chi hien sau khi
// JavaScript chay: G2/G3 khac chi doc HTML tu server nen khong thay duoc.
//
//   node check-browser.mjs <base-url> up     # stack khoe
//   node check-browser.mjs <base-url> down   # backend da dung: trang loi 500 phai hien
//
// Chay trong image Playwright (docker-compose.ci.yml, service `browser`).
import { chromium } from 'playwright-core';

const [base, mode = 'up'] = process.argv.slice(2);
if (!base || !['up', 'down'].includes(mode)) {
  console.error('usage: check-browser.mjs <base-url> up|down');
  process.exit(2);
}

const failures = [];
function check(name, ok, detail = '') {
  console.log(`  ${ok ? 'OK  ' : 'HONG'}  ${name}${ok || !detail ? '' : '  --  ' + detail}`);
  if (!ok) failures.push(name);
}

const browser = await chromium.launch();
const page = await browser.newPage();
const pageErrors = [];
page.on('pageerror', error => pageErrors.push(error.message));

try {
  if (mode === 'up') {
    let response = await page.goto(base + '/', { waitUntil: 'domcontentloaded' });
    check('trang chu tra 200', response.status() === 200, String(response.status()));
    // Gia phut lay qua proxy /api/v1/market/world/live sau khi trang da tai.
    const live = page.locator('section[aria-label="Giá vàng thế giới theo phút"]');
    await live.getByText('VND/lượng').waitFor({ timeout: 30000 }).catch(() => {});
    const text = (await live.innerText().catch(() => '')).replace(/\s+/g, ' ');
    // So kieu vi-VN: 2.500,13 (cham ngan cach hang nghin, phay thap phan).
    const usd = Number((text.match(/([\d.,]+)\s*USD\/oz/) || [])[1]?.replace(/\./g, '').replace(',', '.'));
    const vnd = Number((text.match(/≈\s*([\d.]+)\s*VND\/lượng/) || [])[1]?.replace(/\./g, ''));
    check('the gia phut hien USD/oz', usd > 0, text.slice(0, 160));
    check('the gia phut hien VND/luong, lam tron toi nghin', vnd > 0 && vnd % 1000 === 0, text.slice(0, 160));
    // 1 luong = 37,5 g, 1 oz = 31,1034768 g: VND/luong / USD/oz phai bang ty gia x 1,2057.
    const rate = Number((text.match(/tỷ giá\s*([\d.]+)\s*VND\/USD/) || [])[1]?.replace(/\./g, ''));
    if (rate > 0 && usd > 0) {
      const expected = Math.round(usd * rate / 31.1034768 * 37.5 / 1000) * 1000;
      check('quy doi VND/luong dung cong thuc', Math.abs(vnd - expected) <= 1000, `${vnd} vs ${expected}`);
    } else {
      check('dong ghi chu co ty gia dung de quy doi', false, text.slice(0, 200));
    }

    response = await page.goto(base + '/instruments/XAUUSD', { waitUntil: 'networkidle' });
    check('trang XAUUSD tra 200', response.status() === 200, String(response.status()));
    const lines = await page.locator('.recharts-surface path.recharts-curve').count();
    check('bieu do lich su gia ve duoc duong', lines > 0, `${lines} duong`);

    // Chuyen trang phia trinh duyet (khong con loading.tsx): bam tab chuoi khac.
    await page.locator('a[href^="/instruments/USDVND"]').first().click();
    await page.waitForURL(/\/instruments\/USDVND/, { timeout: 15000 }).catch(() => {});
    await page.getByRole('heading', { level: 1 }).first().waitFor({ timeout: 15000 }).catch(() => {});
    const heading = await page.getByRole('heading', { level: 1 }).first().innerText().catch(() => '');
    check('chuyen tab sang USDVND hien noi dung moi', /USD/.test(heading) && page.url().includes('/instruments/USDVND'),
      `${page.url()} "${heading}"`);

    response = await page.goto(base + '/forecast/XAUUSD?model=NAIVE&horizon=7', { waitUntil: 'networkidle' });
    check('trang du bao tra 200', response.status() === 200, String(response.status()));
    const rows = await page.locator('table tbody tr').count();
    check('bang du bao co du 7 buoc', rows >= 7, `${rows} dong`);

    response = await page.goto(base + '/instruments/KHONG-TON-TAI', { waitUntil: 'networkidle' });
    check('ma khong ton tai tra 404', response.status() === 404, String(response.status()));
    check('trang 404 hien loi tieng Viet', await page.getByText('Không tìm thấy trang').isVisible());
  } else {
    // Khong cho 'networkidle': the gia phut van goi lai proxy (moi lan cho het timeout).
    // range=30 la loi goi chua tung vao Data Cache cua Next: trang da cache (luot 'up')
    // van tra 200 voi du lieu cu trong luc backend chet, va do la hanh vi dung.
    const response = await page.goto(base + '/instruments/XAUUSD?range=30', { waitUntil: 'domcontentloaded' });
    check('backend dung: trang tra 500', response.status() === 500, String(response.status()));
    // HTML 500 chi la khung __next_error__; error.tsx hien sau khi JS chay.
    const message = page.getByText('Không tải được dữ liệu');
    await message.waitFor({ timeout: 15000 }).catch(() => {});
    check('error.tsx hien thong bao tieng Viet', await message.isVisible());
    check('trang loi co ma digest de doi chieu log', await page.getByText(/Mã lỗi:/).isVisible());
    pageErrors.length = 0; // loi server render la chu y; chi quan tam pageerror khi stack khoe
  }
  check('khong co loi JavaScript tren trang', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 300));
} finally {
  await browser.close();
}

console.log(`\n${failures.length === 0 ? 'Tat ca dat' : failures.length + ' hong'} (${mode}).`);
process.exit(failures.length === 0 ? 0 : 1);
