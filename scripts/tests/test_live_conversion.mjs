// Quy doi gia XAU/USD theo phut sang VND/luong tren trinh duyet. Cung cong thuc voi khoi
// "conversion" cua backend (smoke.mjs kiem phia backend): USD/oz x VND/USD / g/oz x g/luong.
import test from 'node:test';
import assert from 'node:assert/strict';
import { liveConversion, vndPerTael } from '../../frontend/src/lib/live.ts';

const summary = {
  fx: { vndPerUsd: 25400, asOf: '2026-11-24' },
  conversion: { troyOunceInGrams: 31.1034768, taelInGrams: 37.5 },
};

test('a tael is 37.5 g of gold at the ounce price and the daily rate, to the thousand', () => {
  const conversion = liveConversion(summary);
  // 2650 x 25400 / 31.1034768 x 37.5 = 81 152 503,18
  assert.equal(vndPerTael(2650, conversion), 81_153_000);
});

test('the rate date travels with the figure so the page can say which day it used', () => {
  assert.deepEqual(liveConversion(summary), {
    vndPerUsd: 25400, fxAsOf: '2026-11-24', troyOunceInGrams: 31.1034768, taelInGrams: 37.5,
  });
});

test('without an exchange rate there is no VND figure rather than a wrong one', () => {
  assert.equal(liveConversion({ ...summary, fx: null }), null);
  assert.equal(liveConversion({ ...summary, conversion: null }), null);
});
