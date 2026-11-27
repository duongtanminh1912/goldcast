[![CI](https://github.com/duongtanminh1912/goldcast/actions/workflows/ci.yml/badge.svg)](https://github.com/duongtanminh1912/goldcast/actions/workflows/ci.yml)

# GoldCast

Ứng dụng theo dõi giá vàng thế giới (XAU/USD), tỷ giá USD/VND và giá vàng miếng
trong nước, kèm dự báo thống kê có backtest. Đi cùng là một quy trình CI/CD đầy
đủ đưa mỗi commit từ test tới production qua ba cổng kiểm tra.

Nguyên tắc xuyên suốt: **mỗi con số dự báo luôn đi kèm sai số đo được của chính
nó.** Nếu mô hình không vượt được baseline naive, giao diện nói thẳng điều đó.

> **Ứng dụng có thể chứa dữ liệu mô phỏng.** Khi bật seed và chuỗi còn rỗng, hệ
> thống sinh dữ liệu gắn nhãn `synthetic`; API và giao diện đều cảnh báo rõ.
> Đây không phải lời khuyên đầu tư.

**[Hướng dẫn đầy đủ →](docs/huong-dan.md)** dựng Jenkins, đưa lên VPS, vận hành,
backup, monitoring và khôi phục khi mất máy chủ.

---

## Chạy thử

Cần Docker và Git.

```bash
git clone https://github.com/duongtanminh1912/goldcast && cd goldcast
make up            # tạo .env từ .env.example nếu chưa có, rồi docker compose up -d --build
```

| Dịch vụ  | URL |
|----------|-----|
| Frontend | http://localhost:3000 |
| API      | http://localhost:8080/api/v1 |
| Swagger  | http://localhost:8080/swagger-ui.html |
| Health   | http://localhost:8080/actuator/health |

Lần đầu, backend chạy ingest ngay khi sẵn sàng; chuỗi nào vẫn rỗng sẽ được seed
dữ liệu mô phỏng (cấu hình dev mặc định).

```bash
make logs           # xem log
make seed           # gọi ingest thủ công (cần APP_ADMIN_TOKEN trong .env)
make down           # dừng
make clean          # dừng và xoá volume database
```

`make seed` chạy trong container backend và gửi header `X-Admin-Token`. Sau khi
đặt `APP_ADMIN_TOKEN` trong `.env`, chạy `docker compose up -d backend` để áp dụng.

---

## Kiến trúc

```
Trình duyệt
    │  HTML (SSR) + proxy chỉ đọc /api/v1/market/world/live
Next.js 15 (App Router, TypeScript, Tailwind, Recharts)
    │  REST /api/v1, chỉ gọi từ phía máy chủ
Spring Boot 3.3 · Java 21  ──►  Stooq, SJC, FRED (ingest hằng giờ) · Gold API (mỗi phút)
    │  JPA/JDBC + Flyway
PostgreSQL 16
```

Trình duyệt không bao giờ gọi thẳng backend, nên cổng 8080 đóng hoàn toàn trên
production; chỉ Caddy mở 80/443.

```
goldcast/
├── backend/src/main/java/vn/goldcast/
│   ├── forecast/    Mô hình dự báo, backtest, khoảng tin cậy   ← không phụ thuộc Spring
│   ├── analytics/   Chỉ báo kỹ thuật                            ← không phụ thuộc Spring
│   ├── ingest/      PriceProvider (Stooq, SJC), parser, scheduler, seed mô phỏng
│   ├── market/      Quy đổi đơn vị, lịch giao dịch, tổng hợp, độ mới dữ liệu
│   │   └── live/    Giá XAU/USD theo phút (bảng riêng, có retention)
│   ├── macro/       Lợi suất thực, chỉ số USD từ FRED cho mô hình ARX
│   ├── api/         Controller, DTO, lỗi RFC 7807, filter admin token
│   ├── domain/, repository/, config/, monitoring/
├── frontend/src/
│   ├── app/         Dashboard, chi tiết chuỗi, dự báo, phương pháp luận, route proxy
│   ├── components/  UI dùng chung + biểu đồ
│   └── lib/         API client có kiểu, types.ts (đối chiếu với DTO Java), format
├── Jenkinsfile, .github/workflows/ci.yml    Pipeline
├── .github/workflows/external-probe.yml     Probe HTTPS từ bên ngoài mỗi 10 phút
├── docker-compose{,.ci,.prod}.yml           Dev · G2 · production
├── scripts/         G2, smoke, release/rollback, bootstrap, backup, probe ngoài và test của chúng
├── deploy/          Caddyfile, host key VPS đã ghim, cổng SSH của khoá Jenkins, script dựng máy chủ, script siết SSH
├── jenkins/         Image Jenkins, job backup và diễn tập rollback
├── monitoring/      Prometheus, Alertmanager, Grafana, luật cảnh báo, SLO, Watchdog
└── docs/huong-dan.md
```

Toàn bộ phần toán (`forecast/`, `analytics/`) **không import Spring**: phần dễ sai
nhất cũng là phần test được bằng JUnit thuần, và thay được bằng mô hình khác mà
không đụng tới API.

---

## API

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| GET | `/api/v1/instruments` | Danh sách chuỗi giá đang theo dõi |
| GET | `/api/v1/market/summary` | Tổng quan: thế giới, tỷ giá, trong nước, chênh lệch, cảnh báo độ mới |
| GET | `/api/v1/market/world/live?limit` | Giá XAU/USD theo phút và trạng thái độ mới |
| GET | `/api/v1/prices/{code}?from&to&limit` | Lịch sử giá ngày |
| GET | `/api/v1/forecast/{code}?model&horizon&persist` | Dự báo kèm độ chính xác |
| GET | `/api/v1/backtest/{code}?horizon` | So sánh mọi mô hình bằng rolling-origin |
| GET | `/api/v1/indicators/{code}?lookback` | SMA, EMA, RSI, Bollinger |
| GET | `/api/v1/models` | Danh mục mô hình |
| GET | `/api/v1/release` | Định danh bản đang chạy của backend |
| POST | `/api/v1/admin/ingest` | Chạy ingest ngay (`X-Admin-Token`) |
| GET | `/api/v1/admin/ingest/status` | Lượt ingest gần nhất, trạng thái từng provider (`X-Admin-Token`) |
| GET | `/api/v1/admin/providers` | Danh sách nguồn dữ liệu (`X-Admin-Token`) |

Mã chuỗi: `XAUUSD`, `USDVND`, `SJC_HCM`, `SJC_HN`, `SJC_RING`.
Mô hình: `AUTO` (mặc định), `NAIVE`, `DRIFT`, `SMA`, `HOLT_DAMPED`, `AR_DIFF`, `ARX_MACRO` (chỉ XAU/USD).

Lỗi trả về theo RFC 7807. Đáng chú ý là **422 `insufficient-history`**: chuỗi chưa
đủ dữ liệu thì API từ chối dự báo, thay vì trả một con số kèm khoảng tin cậy rộng
mà người đọc vẫn sẽ tin. Tương tự, yêu cầu `ARX_MACRO` cho chuỗi không phải XAU/USD, hoặc khi
chưa đủ dữ liệu vĩ mô, trả **422 `macro-unavailable`**. Ingest thủ công chạy chồng với scheduler trả **409**.

```bash
curl "http://localhost:8080/api/v1/forecast/XAUUSD?model=AUTO&horizon=14" | jq
```

---

## Nguồn dữ liệu

| Chuỗi | Nguồn | Lịch sử |
|-------|-------|---------|
| XAU/USD, USD/VND (ngày) | Stooq (CSV) | Nhiều năm, một request lấy cả chuỗi |
| Vàng miếng SJC | Feed XML công khai của SJC | **Chỉ có giá ngày hiện tại** |
| XAU/USD (phút) | [Gold API](https://gold-api.com/docs) | Tích luỹ từ lúc chạy, giữ 30 ngày |
| Lợi suất thực 10 năm Mỹ, chỉ số USD rộng | [FRED](https://fred.stlouisfed.org/) (`DFII10`, `DTWEXBGS`) | Khoảng 3 năm; chỉ là biến đầu vào của mô hình |

SJC không có endpoint lịch sử, nên chuỗi trong nước dày lên dần kể từ lúc hệ thống
chạy; trước đó API trả 422 chứ không đoán.

Hai chuỗi vĩ mô nằm ở bảng riêng `macro_observation` (lợi suất thực có thể âm nên không
dùng chung bảng giá) và không hiện trên giao diện thị trường. FRED được gọi sau khi dữ liệu
giá đã commit, nên FRED lỗi không bao giờ làm mất dữ liệu giá.

Giá theo phút nằm ở bảng riêng `live_gold_quote` và **không bao giờ** sửa chuỗi
ngày dùng cho dự báo. Tuổi giá luôn tính từ timestamp của nguồn; khi nguồn lỗi,
giao diện giữ giá gần nhất và hiện rõ giá đã cũ.

Thêm nguồn mới là **thêm một implementation `PriceProvider` và một migration
INSERT**, không sửa `IngestService`. Parser SJC duyệt toàn bộ cây XML tìm
`buy`/`sell` thay vì bám một XPath cố định (XPath cứng hỏng âm thầm: trả về rỗng
và trông như "đang yên ắng"). Parser cũng chuẩn hoá đơn vị đồng/nghìn đồng: nhân
1000 tới khi giá hợp lý, không thì **bỏ dòng**. Một dòng bị bỏ tốt hơn một dòng
sai ba chữ số.

### Dữ liệu mô phỏng

- Chỉ thêm vào chuỗi **hoàn toàn trống** sau ingest; mọi dòng gắn `source = "synthetic"`.
- Có tính xác định. Chuỗi trong nước được suy ra từ thế giới + tỷ giá mô phỏng
  cộng một mức chênh lệch trôi dần, nên quy đổi và chênh lệch vẫn nhất quán.
- Nguồn thật phục hồi sau đó thì dữ liệu thật và mô phỏng cùng tồn tại; tắt seed
  không xoá điểm mô phỏng đã lưu.
- Compose dev bật seed, Compose production tắt.

---

## Dự báo

`AUTO` backtest mọi mô hình rồi chọn MASE thấp nhất; hoà thì mô hình đơn giản hơn thắng.

| Mô hình | Cách hoạt động |
|---------|----------------|
| `NAIVE` | Giá ngày mai = giá hôm nay. Baseline, và là mẫu số của MASE. |
| `DRIFT` | Ngoại suy mức thay đổi trung bình của chuỗi huấn luyện. |
| `SMA` | Dự báo phẳng bằng trung bình k phiên gần nhất. |
| `HOLT_DAMPED` | San mũ có xu hướng tắt dần (φ < 1), grid search α/β/φ theo SSE một bước. |
| `AR_DIFF` | AR(p) trên sai phân bậc 1, tức ARIMA(p,1,0) khớp bằng OLS. |
| `ARX_MACRO` | AR(2) trên sai phân cộng biến động trong hai phiên gần nhất (trước ngày dự báo) của lợi suất thực 10 năm Mỹ và chỉ số USD (ARIMAX khớp bằng OLS). Chỉ cho XAU/USD. |

- **Xu hướng tắt dần là cố ý.** Mô hình xu hướng không tắt, khớp vào một đợt tăng
  giá, sẽ ngoại suy đợt tăng đó ra vô hạn.
- **Khoảng tin cậy lấy từ sai số out-of-sample**, không phải phần dư in-sample
  (luôn nhỏ hơn thực tế vì mô hình đã thấy chính những điểm đó). Độ rộng lấy từ
  RMSE đo được ở từng bước trong backtest; bước nào thiếu mẫu thì lùi về sai số
  một bước × √h.
- **Backtest rolling-origin:** ở mỗi điểm gốc, mô hình được khớp lại chỉ với dữ
  liệu có trước đó. Sai số báo cả tổng hợp lẫn **theo từng bước dự báo**.
- **Biến vĩ mô chỉ được dùng khi chúng giúp thật.** Lợi suất thực và đồng USD là hai yếu tố
  kinh điển của giá vàng, nhưng `ARX_MACRO` chỉ dùng biến động **đến phiên trước** (không nhìn
  thấy thông tin của ngày cần dự báo) và giả định hai biến đứng yên trong tương lai. Nó cạnh
  tranh với các mô hình khác trong cùng backtest; AUTO không chọn nó nếu nó không thắng.
- **Backtest được nhớ theo đúng dữ liệu đầu vào.** Khoá cache chứa cả chuỗi giá (và dữ liệu vĩ
  mô đã căn ngày với `ARX_MACRO`), nên chỉ cần một giá mới hay một giá bị sửa là backtest chạy lại;
  không cần xoá cache bằng tay. Database chỉ được giữ trong lúc đọc dữ liệu, phần tính toán chạy
  sau khi đã trả connection.
- **Đọc MASE:** dưới 1 thì mô hình mang thêm thông tin, từ 1 trở lên thì không,
  bất kể MAPE đẹp đến đâu. Giá vàng theo ngày rất gần bước đi ngẫu nhiên, nên
  MASE quanh 1 là bình thường và giao diện cảnh báo đúng lúc đó.

Tham số nằm trong `backend/src/main/resources/application.yml` dưới `app.forecast`:
`min-history`, `max-horizon`, `backtest-min-train`, `backtest-max-origins`, `max-train-window`.

### Quy đổi thế giới ↔ trong nước

```
1 troy ounce = 31,1034768 g      1 lượng (cây) = 37,5 g = 10 chỉ
VNĐ/lượng = USD/oz ÷ 31,1034768 × 37,5 × tỷ giá USD/VND
```

Khoảng cách giữa con số đó và giá SJC thực bán là **chênh lệch trong nước**, hiển
thị riêng vì nó biến động theo chính sách trong nước chứ không theo thị trường
thế giới. Khi so sánh, hệ thống dùng giá thế giới và tỷ giá **có hiệu lực đúng
ngày của giá trong nước**, không phải giá mới nhất.

---

## Cấu hình

Đặt trong `.env` hoặc biến môi trường (xem `.env.example`):

| Biến | Mặc định | Ý nghĩa |
|------|----------|---------|
| `APP_INGEST_ENABLED` | `true` | Bật/tắt ingest ngày |
| `APP_INGEST_CRON` | `0 7 * * * *` | Lịch ingest (hằng giờ, phút thứ 7) |
| `APP_INGEST_SEED_ON_EMPTY` | dev `true`, production `false` | Seed dữ liệu mô phỏng cho chuỗi trống |
| `APP_LIVE_GOLD_ENABLED` | `true` | Bật/tắt lấy giá theo phút |
| `APP_LIVE_GOLD_URL` | `https://api.gold-api.com/price/XAU` | Nguồn giá XAU/USD theo phút |
| `APP_LIVE_GOLD_POLL_SECONDS` | `60` | Chu kỳ lấy giá theo phút |
| `APP_LIVE_GOLD_TIMEOUT_SECONDS` | `10` | Thời gian chờ tối đa mỗi lần gọi Gold API |
| `APP_LIVE_GOLD_STALE_SECONDS` | `180` | Quá ngưỡng này thì giá phút bị coi là cũ |
| `APP_LIVE_GOLD_RETENTION_DAYS` | `30` | Số ngày giữ giá theo phút |
| `APP_MACRO_ENABLED` | `true` | Lấy lợi suất thực và chỉ số USD từ FRED cho `ARX_MACRO` |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Origin được phép gọi API |
| `APP_ADMIN_TOKEN` | rỗng | Header `X-Admin-Token` cho `/api/v1/admin/**`; rỗng thì khoá endpoint |
| `API_BASE_URL_INTERNAL` | Compose: `http://backend:8080` | URL backend mà frontend gọi từ phía máy chủ |
| `API_TIMEOUT_MS` | `5000` | Thời gian tối đa cho mỗi lời gọi backend từ frontend, kể cả phần thân phản hồi |
| `RELEASE_ID`, `BACKEND_IMAGE`, `FRONTEND_IMAGE` | bắt buộc với Compose CI/production | Lấy từ artifact `release.env` của build đã qua G2 |

Schema database do **Flyway** quản lý (`backend/src/main/resources/db/migration`),
không dùng `ddl-auto`, để máy dev và production chắc chắn cùng schema.

---

## Phát triển

```bash
docker compose up -d db                                                # chỉ database
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev       # JDK 21 + Maven
cd frontend && npm install && npm run dev                              # Node 22
```

Chạy ngoài Docker thì đặt `API_BASE_URL_INTERNAL=http://localhost:8080` trong
`frontend/.env.local`.

### Test

Từ thư mục gốc, đây cũng chính là cổng G1:

```bash
mvn -f backend/pom.xml test                          # một test: -Dtest=ForecasterTest
python3 -m unittest discover -s scripts/tests -v     # release, rollback, backup, monitoring
node --test scripts/tests/test_*.mjs                 # probe G3, probe ngoài, snapshot bootstrap
sh monitoring/check.sh                               # promtool/amtool, định tuyến cảnh báo
(cd frontend && npm ci && npm run typecheck)
```

Test script vận hành giả lập Docker bằng lệnh cục bộ, không truy cập VPS. Test
Java tập trung vào phần toán và phần parse, hai nơi lỗi âm thầm gây thiệt hại
nhất:

- `ApiContractTest`: đối chiếu DTO Java với `frontend/src/lib/types.ts`
- `ForecasterTest`: từng mô hình trên chuỗi đã biết đáp án; AR(p) phải khôi phục
  được hệ số của một quá trình AR(1) sinh sẵn
- `BacktesterTest`, `IntervalEstimatorTest`, `MetricsTest`: Drift sai số 0 trên
  đường thẳng, khoảng 95% rộng hơn 80% và nở theo √h, MAE/RMSE/MAPE/MASE đối chiếu tính tay
- `IndicatorsTest`, `GoldUnitsTest`, `TradingCalendarTest`, `DataFreshnessTest`
- `StooqCsvParserTest`, `SjcXmlParserTest`, `DateParsingTest`: CSV có dòng `N/D`,
  XML hỏng, trang HTML lỗi thay XML, số kiểu `78.500` vs `1,5`, giá trị sai đơn vị
- `AdminTokenFilterTest`, `AdminCorsTest`, `ReleaseControllerTest`, `GoldcastMetricsTest`: khoá
  endpoint admin khi token rỗng, CORS, định danh release, metric cho Prometheus
- `IngestConcurrencyTest` và các test `market/live`: khoá ingest, parser và lưu
  trữ giá theo phút
- `ArxMacroForecasterTest`, `FredCsvParserTest`, `MacroAlignmentTest`: khôi phục đúng hệ số
  của một tác động vĩ mô đã biết, không bao giờ đọc dữ liệu vĩ mô sau ngày dự báo, CSV FRED
  có ô trống hoặc sai chuỗi

---

## Quy trình DevOps

```
git push
   │
   ├──► GitHub Actions ── G1 ──► xanh / đỏ trên pull request (check `backend-test`)
   │
   └──► Jenkins (Poll SCM)
          G1: cùng bộ test như trên
          Build: docker buildx linux/amd64 một lần ──► GHCR, chốt digest vào release.env
          G2: Compose riêng, cài mới + nâng cấp DB có dữ liệu, smoke API, trang SSR
          Deploy (chỉ main): đúng digest đã qua G2
          G3: readiness + image ID + HTTPS + RELEASE_ID công khai
          Đạt: ghi .last-good-release · Lỗi: rollback về bản tốt và xác minh lại
```

| | GitHub Actions | Jenkins |
|---|---|---|
| Vai trò | tín hiệu xanh/đỏ trên PR, probe site từ bên ngoài | build image, G2, triển khai thật |
| Môi trường | sạch mỗi lần | bền vững, giữ cache Maven/Docker |
| Chạy ở đâu | hạ tầng GitHub | máy cá nhân |

**G1 — kiểm code.** Ngoài unit test, [`ApiContractTest`](backend/src/test/java/vn/goldcast/api/dto/ApiContractTest.java)
so tên từng trường của DTO Java với kiểu TypeScript. Kiểu TypeScript bị xoá lúc
biên dịch, nên thiếu cổng này thì đổi tên một trường ở backend vẫn để `mvn test`
và `tsc` xanh, còn người dùng thấy ô trống.

**G2 — kiểm image trước deploy.** Dùng đúng digest sẽ triển khai, dựng Compose riêng
để kiểm cài mới và nâng cấp từ schema V1 có dữ liệu mẫu: 39 phép thử bất biến API (gồm
`ARX_MACRO` trên dữ liệu vĩ mô mô phỏng) và ba trang SSR. Ví dụ `worldVndPerGram × troyOunceInGrams` phải bằng `usdPerOunce × vndPerUsd`,
nên công thức quy đổi sai là lộ ngay.
→ [`scripts/g2.sh`](scripts/g2.sh) · [`scripts/smoke.mjs`](scripts/smoke.mjs)

**G3 — xác minh production.** Image ID đúng digest, readiness, các trang HTTPS qua
Caddy, và `/api/release` phải trả đúng `RELEASE_ID` của cả frontend lẫn backend.
Trang trả mã HTTP thật (mã không tồn tại là 404, backend lỗi là 500), nên cổng không
phải dò lỗi qua câu chữ tiếng Việt. Jenkins SSH vào VPS với host key ghim trong
[`deploy/known_hosts`](deploy/known_hosts).
→ [`scripts/release.sh`](scripts/release.sh)

**Hạ tầng:** một VPS Ubuntu 24.04 dựng bằng script chạy lại được ([`deploy/`](deploy/)),
Caddy tự cấp HTTPS, chỉ mở 22/80/443, triển khai bằng user `deploy` không phải root.
Backup DB và cấu hình release chạy hằng đêm ra ngoài VPS, có phục hồi thử.

**Giám sát:** Prometheus, Alertmanager, Grafana và Blackbox chạy trên máy Jenkins,
nối về VPS qua SSH tunnel; 35 luật cảnh báo ngưỡng gửi Telegram (HTTPS, tài nguyên,
container, API, độ mới dữ liệu kể cả dữ liệu vĩ mô, sai digest, Jenkins, backup) cùng luật `GoldcastWatchdog`.
Thêm hai SLO trong 7 ngày cuốn (HTTPS công khai 99,5%, API backend 99% không 5xx) với 4 luật
burn rate multi-window và dashboard error budget riêng, chỉ dựng trên metrics đã thu sẵn. Hai đường độc lập với
máy đó: Watchdog gửi nhịp sống tới healthchecks.io (bộ giám sát chết là có tin báo),
và một workflow GitHub Actions kiểm site từ bên ngoài mỗi 10 phút.

Cách dựng và vận hành từng phần: [docs/huong-dan.md](docs/huong-dan.md).

---

## Hạn chế đã biết

| Hạn chế | Hệ quả |
|---|---|
| Mô hình chỉ dựa trên dữ liệu đã xảy ra | `ARX_MACRO` có tính lợi suất thực và USD đến phiên trước, nhưng không mô hình nào biết trước quyết định của Fed hay tin địa chính trị, vốn là thứ tạo ra cú sốc giá lớn |
| Lịch giao dịch chỉ bỏ thứ Bảy/Chủ nhật | Chưa có ngày lễ Việt Nam; nhãn ngày quanh Tết có thể lệch |
| Monitoring đầy đủ chạy trên máy Mac cá nhân | Mac tắt thì Watchdog báo và probe GitHub vẫn kiểm site, nhưng luật chi tiết (VPS, dữ liệu, backup) tạm ngừng |
| Chưa có log tập trung, tracing | Log chỉ nằm trong Docker trên VPS; SLO chỉ đo được khi bộ giám sát trên Mac chạy, cửa sổ 7 ngày theo retention |
| Hạ tầng dựng bằng script shell, chưa có IaC | Dựng lại được nhưng không có trạng thái khai báo như Terraform |
| Một máy chủ, không dự phòng | Máy chết là dịch vụ chết; mỗi lần deploy gián đoạn vài giây |
| Rollback không phục hồi DB | Migration phải tương thích với bản ứng dụng trước |
| G2 nâng cấp từ baseline V1 và dữ liệu mẫu | Chưa bao phủ mọi phiên bản cũ và dữ liệu production |
| Khoá deploy không passphrase, Jenkins gắn `docker.sock` | Trên VPS khoá chỉ chạy được cổng SSH, cấu hình phải khớp GitHub `main`; nhưng image Jenkins đẩy lên GHCR chưa được ký ngoài máy Jenkins |
| Chưa kiểm trình duyệt tự động | G2/G3 kiểm HTML từ server, chưa kiểm tương tác JavaScript |

Đây không phải lời khuyên đầu tư và không thay thế giá niêm yết chính thức tại quầy.
