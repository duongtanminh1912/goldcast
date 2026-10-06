#!/usr/bin/env node
/**
 * G2 - Smoke test noi dung cho GoldCast.
 *
 * Chay duoc o ba noi, chi khac BASE_URL:
 *   node scripts/smoke.mjs http://localhost:8090      (tay, tren may dev)
 *   node scripts/smoke.mjs http://backend:8080        (trong mang compose, Jenkins goi)
 *   node scripts/smoke.mjs https://ten-mien-that      (sau khi trien khai)
 *
 * Ma thoat:
 *   0  moi phep thu dat
 *   1  co phep thu hong          -> he thong co van de
 *   2  goi sai cach, thieu BASE_URL -> nguoi dung sai, khong phai he thong sai
 *
 * Du lieu he thong la tong hop nen KHONG khang dinh gia tri cu the.
 * Chi khang dinh bat bien, va phan biet ro hai loai:
 *   - TU CHUA: chi dung cac so trong chinh phan hoi do. Khong the sai ve mien nghiep vu.
 *   - CO KIEN THUC NGOAI: ma hoa mot su that ben ngoai. Co the sai neu su that do sai.
 */

const BASE_RAW = process.argv[2] || process.env.BASE_URL;
if (!BASE_RAW) {
  console.error("Thieu BASE_URL. Script nay khong tu doan dia chi.");
  console.error("  node scripts/smoke.mjs http://localhost:8090");
  console.error("  BASE_URL=http://backend:8080 node scripts/smoke.mjs");
  process.exit(2);
}
const BASE = BASE_RAW.replace(/\/+$/, "");
const TIMEOUT_MS = Number(process.env.SMOKE_TIMEOUT_MS || 15000);
const SAI_SO = 0.01; // 1% - du cho lam tron BigDecimal, khong du de giau mot cong thuc sai

const loi = [];
let soPhepThu = 0;

function kiem(ten, dieuKien, chiTiet) {
  soPhepThu++;
  if (dieuKien) {
    console.log("  OK    " + ten);
  } else {
    console.log("  HONG  " + ten + (chiTiet ? "  --  " + chiTiet : ""));
    loi.push(ten);
  }
}

function gan(a, b) {
  if (!Number.isFinite(a) || !Number.isFinite(b) || b === 0) return false;
  return Math.abs(a - b) / Math.abs(b) < SAI_SO;
}

function moTaLoi(e) {
  if (e && e.name === "AbortError") return "khong phan hoi trong " + TIMEOUT_MS + "ms";
  const ma = (e && e.cause && e.cause.code) || (e && e.code);
  if (ma === "ECONNREFUSED") return "khong ai nghe o dia chi nay (ECONNREFUSED)";
  if (ma === "ENOTFOUND") return "khong phan giai duoc ten may (ENOTFOUND)";
  if (ma === "ETIMEDOUT") return "het thoi gian cho o tang mang (ETIMEDOUT)";
  if (ma === "ECONNRESET") return "ket noi bi dong giua chung (ECONNRESET)";
  return (e && e.message) || String(e);
}

async function layJson(duongDan) {
  const url = BASE + duongDan;
  const ctrl = new AbortController();
  const hen = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(url, { signal: ctrl.signal });
    if (!res.ok) throw new Error("HTTP " + res.status + " " + res.statusText + " tu " + url);
    return await res.json();
  } finally {
    clearTimeout(hen);
  }
}

async function thuInstruments() {
  console.log("\n[1] GET /api/v1/instruments");
  const ds = await layJson("/api/v1/instruments");

  const laMang = Array.isArray(ds) && ds.length > 0;
  kiem("tra ve mang khong rong", laMang,
       "nhan duoc " + (Array.isArray(ds) ? ds.length + " phan tu" : typeof ds));
  if (!laMang) return;

  const KIND_HOP_LE = new Set(["SPOT_GOLD", "VN_GOLD", "FX"]);
  const kindLa = [...new Set(ds.map(i => i.kind))].filter(k => !KIND_HOP_LE.has(k));
  kiem("moi kind nam trong InstrumentKind", kindLa.length === 0,
       "gia tri la: " + JSON.stringify(kindLa));

  const thieu = ds.filter(i => !i.code || !i.name).length;
  kiem("moi instrument co code va name", thieu === 0, thieu + " phan tu thieu");
}

async function thuMarketSummary() {
  console.log("\n[2] GET /api/v1/market/summary");
  const s = await layJson("/api/v1/market/summary");

  const usd = Number(s && s.world && s.world.usdPerOunce);
  const vnd = Number(s && s.fx && s.fx.vndPerUsd);
  kiem("world.usdPerOunce > 0", usd > 0, "nhan " + usd);
  kiem("fx.vndPerUsd > 0", vnd > 0, "nhan " + vnd);

  const dom = s && s.domestic;
  const coDomestic = Array.isArray(dom) && dom.length > 0;
  kiem("domestic khong rong", coDomestic,
       "nhan " + (Array.isArray(dom) ? dom.length : typeof dom));
  if (coDomestic) {
    const xau = dom.filter(d => !(Number(d.buy) > 0 && Number(d.sell) > 0));
    kiem("moi gia vang trong nuoc deu duong", xau.length === 0,
         xau.length + " muc co gia <= 0");
  }

  const c = (s && s.conversion) || {};
  const gram  = Number(c.worldVndPerGram);
  const tael  = Number(c.worldVndPerTael);
  const chi   = Number(c.worldVndPerChi);
  const gTael = Number(c.taelInGrams);
  const gOz   = Number(c.troyOunceInGrams);

  // --- Bat bien TU CHUA ---
  kiem("quy doi: tael = gram x taelInGrams",
       gan(tael, gram * gTael),
       "tael=" + tael + " nhung gram*taelInGrams=" + (gram * gTael));

  kiem("quy doi: gram = usdPerOunce x vndPerUsd / troyOunceInGrams",
       gan(gram, (usd * vnd) / gOz),
       "gram=" + gram + " nhung usd*vnd/oz=" + ((usd * vnd) / gOz));

  // --- Bat bien CO KIEN THUC NGOAI: 1 luong = 10 chi ---
  kiem("quy doi: chi = tael / 10  (kien thuc ngoai)",
       gan(chi, tael / 10),
       "chi=" + chi + " nhung tael/10=" + (tael / 10));

  // --- Bat bien CO KIEN THUC NGOAI: hang so vat ly ---
  // Hai phep thu nay bit cho ho cua ba phep thu tu chua o tren:
  // neu hang so sai, he thong van TU NHAT QUAN nen ba phep thu kia deu dat.
  kiem("hang so: taelInGrams = 37,5  (kien thuc ngoai)",
       Math.abs(gTael - 37.5) < 1e-9,
       "taelInGrams=" + gTael);

  kiem("hang so: troyOunceInGrams = 31,1034768  (kien thuc ngoai)",
       Math.abs(gOz - 31.1034768) < 1e-6,
       "troyOunceInGrams=" + gOz);

  const t = Date.parse(s && s.generatedAt);
  kiem("generatedAt doc duoc va cach hien tai duoi 24h",
       Number.isFinite(t) && Math.abs(Date.now() - t) < 24 * 3600 * 1000,
       "generatedAt=" + (s && s.generatedAt));
}

async function thuModels() {
  console.log("\n[3] GET /api/v1/models");
  const ds = await layJson("/api/v1/models");

  const laMang = Array.isArray(ds) && ds.length > 0;
  kiem("tra ve mang khong rong", laMang,
       "nhan duoc " + (Array.isArray(ds) ? ds.length + " phan tu" : typeof ds));
  if (!laMang) return;

  const ids = ds.map(m => m.id);
  console.log("        id tra ve: " + JSON.stringify(ids));

  // Khong chep danh sach mo hinh vao day -- danh sach do da co o enum Java va
  // union TypeScript, va cong G1 gac cho hai noi do khop nhau. Chi kiem DANG,
  // de bat truong hop @JsonValue lam JSON tra ve nhan hien thi thay vi ten hang.
  const saiDang = ids.filter(id => typeof id !== "string" || !/^[A-Z][A-Z0-9_]*$/.test(id));
  kiem("moi id co dang hang enum CHU_HOA_GACH_DUOI", saiDang.length === 0,
       "sai dang: " + JSON.stringify(saiDang));

  kiem("co it nhat 5 mo hinh", ds.length >= 5, "chi co " + ds.length);
  kiem("co mo hinh NAIVE lam moc so sanh", ids.includes("NAIVE"), JSON.stringify(ids));

  const thieuLabel = ds.filter(m => !m.label).length;
  kiem("moi mo hinh co label", thieuLabel === 0, thieuLabel + " mo hinh thieu label");
}

async function main() {
  console.log("Smoke test GoldCast  --  BASE_URL = " + BASE);

  for (const thu of [thuInstruments, thuMarketSummary, thuModels]) {
    try {
      await thu();
    } catch (e) {
      soPhepThu++;
      console.log("  HONG  goi API that bai  --  " + moTaLoi(e));
      loi.push(thu.name + ": " + moTaLoi(e));
    }
  }

  console.log("\n" + soPhepThu + " phep thu, " + loi.length + " hong.");
  if (loi.length > 0) {
    console.log("Danh sach hong:");
    for (const l of loi) console.log("  - " + l);
    process.exit(1);
  }
  console.log("Tat ca dat.");
}

main();
