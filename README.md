# goldcast

Theo dõi giá vàng thế giới (XAU/USD), tỷ giá USD/VND và giá vàng miếng trong nước, kèm dự
báo thống kê **có backtest** và khoảng tin cậy ước lượng từ sai số out-of-sample thực đo.

Nguyên tắc xuyên suốt dự án: **mỗi con số dự báo luôn đi kèm sai số đo được của chính nó.**
Nếu mô hình không vượt được baseline naive, giao diện nói thẳng điều đó thay vì giấu đi.

```
Next.js 15 (App Router, TypeScript, Tailwind, Recharts)
        │  REST /api/v1
Spring Boot 3.3 · Java 21
        │  JPA + Flyway
PostgreSQL 16
```

---

## Chạy thử

```bash
git clone <repo> && cd goldcast
cp .env.example .env
make up            # hoặc: docker compose up -d --build
```

| Dịch vụ  | URL                                  |
|----------|--------------------------------------|
| Frontend | http://localhost:3000                |
| API      | http://localhost:8080/api/v1         |
| Swagger  | http://localhost:8080/swagger-ui.html |
| Health   | http://localhost:8080/actuator/health |

Lần khởi động đầu, backend chạy ingest ngay sau khi sẵn sàng. Nếu lấy được dữ liệu thật thì
dùng dữ liệu thật; nếu không (không có mạng, nguồn đổi cấu trúc) nó sinh **dữ liệu mô phỏng
có tính xác định** để ứng dụng vẫn chạy được — xem phần [Dữ liệu mô phỏng](#dữ-liệu-mô-phỏng).

Lệnh hữu ích khác:

```bash
make logs           # xem log
make backend-test   # chạy unit test backend
make seed           # gọi ingest thủ công
make clean          # dừng và xoá volume database
```

---

## Cấu trúc

```
goldcast/
├── docker-compose.yml
├── backend/
│   └── src/main/java/vn/goldcast/
│       ├── domain/      Instrument, PricePoint, ForecastRun, ForecastPoint
│       ├── repository/  Spring Data JPA
│       ├── ingest/      PriceProvider + Stooq/SJC + parser + scheduler
│       ├── forecast/    Engine dự báo, backtest, khoảng tin cậy  ← không phụ thuộc Spring
│       ├── analytics/   Chỉ báo kỹ thuật                          ← không phụ thuộc Spring
│       ├── market/      Quy đổi đơn vị, lịch giao dịch, tổng hợp thị trường
│       ├── api/         Controller + DTO + xử lý lỗi
│       └── config/      Properties, CORS, HTTP client, OpenAPI
└── frontend/src/
    ├── app/         Dashboard, chi tiết chuỗi, dự báo, phương pháp luận
    ├── components/  UI dùng chung + biểu đồ Recharts
    └── lib/         API client có kiểu, types, format tiếng Việt
```

Toàn bộ phần toán — `forecast/` và `analytics/` — **không import Spring**. Đó là lựa chọn có
chủ đích: phần dễ sai nhất trong hệ thống cũng là phần test được bằng JUnit thuần, không cần
dựng context, và thay được bằng implementation ML sau này mà không đụng tới API.

---

## API

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| GET | `/api/v1/instruments` | Danh sách chuỗi giá đang theo dõi |
| GET | `/api/v1/market/summary` | Tổng quan: thế giới, tỷ giá, trong nước, chênh lệch |
| GET | `/api/v1/prices/{code}?from&to&limit` | Lịch sử giá |
| GET | `/api/v1/forecast/{code}?model&horizon&persist` | Dự báo + độ chính xác |
| GET | `/api/v1/backtest/{code}?horizon` | So sánh mọi mô hình bằng rolling-origin |
| GET | `/api/v1/indicators/{code}?lookback` | SMA, EMA, RSI, Bollinger |
| GET | `/api/v1/models` | Danh mục mô hình và mô tả |
| POST | `/api/v1/admin/ingest` | Chạy ingest ngay |
| GET | `/api/v1/admin/providers` | Trạng thái các nguồn dữ liệu |

Mã chuỗi mặc định: `XAUUSD`, `USDVND`, `SJC_HCM`, `SJC_HN`, `SJC_RING`.
Mô hình: `AUTO` (mặc định), `NAIVE`, `DRIFT`, `SMA`, `HOLT_DAMPED`, `AR_DIFF`.

Lỗi trả về theo RFC 7807. Đáng chú ý là **422 `insufficient-history`**: khi chuỗi chưa đủ dữ
liệu, API từ chối dự báo thay vì trả một con số kèm khoảng tin cậy rộng. Một dự báo khớp trên
vài quan sát không phải dự báo yếu — nó vô nghĩa, và người đọc vẫn sẽ đọc con số đó.

```bash
curl "http://localhost:8080/api/v1/forecast/XAUUSD?model=AUTO&horizon=14" | jq
```

---

## Nguồn dữ liệu

| Chuỗi | Nguồn | Lịch sử |
|-------|-------|---------|
| XAU/USD, USD/VND | Stooq (CSV) | Nhiều năm, một request lấy cả chuỗi |
| Vàng miếng SJC | Feed XML công khai của SJC | **Chỉ có giá ngày hiện tại** |

Giới hạn thứ hai là thật và đáng biết trước: SJC không có endpoint lịch sử, nên chuỗi trong
nước dày lên dần kể từ lúc hệ thống bắt đầu chạy. Dự báo cho vàng trong nước cần thời gian
tích luỹ dữ liệu; đến lúc đó API trả 422 chứ không đoán bừa.

Thêm nguồn mới là **thêm một implementation `PriceProvider` và một dòng INSERT**, không phải
sửa `IngestService`. Nếu feed SJC đổi endpoint, chỉnh `app.providers.sjc.url` — parser được
viết theo kiểu duyệt toàn bộ cây XML tìm thuộc tính `buy`/`sell` thay vì bám một XPath cố
định, vì XPath cứng sẽ hỏng âm thầm: nó trả về rỗng và mọi thứ phía sau trông chỉ như "đang
yên ắng" chứ không như đang hỏng.

Parser cũng chuẩn hoá đơn vị: feed loại này từng niêm yết bằng đồng và bằng nghìn đồng ở
những thời điểm khác nhau. Giá trị được nhân 1000 cho tới khi rơi vào khoảng hợp lý, và bị
**loại bỏ** nếu không bao giờ rơi vào — một dòng bị bỏ tốt hơn một dòng sai ba chữ số.

### Dữ liệu mô phỏng

Khi một chuỗi hoàn toàn trống và không nguồn nào truy cập được, hệ thống sinh chuỗi mô phỏng
có tính xác định. Ba điểm quan trọng:

- Mọi dòng được gắn `source = "synthetic"`, và API lẫn giao diện đều cảnh báo rõ.
- Dữ liệu mô phỏng **không bao giờ trộn vào chuỗi đã có dữ liệu thật** — biểu đồ pha trộn hai
  loại là thứ không disclaimer nào cứu được.
- Chuỗi trong nước được suy ra từ chuỗi thế giới + tỷ giá mô phỏng cộng một mức chênh lệch
  trôi dần, nên phần quy đổi và chênh lệch vẫn nhất quán với nhau.

Tắt trong môi trường thật: `APP_INGEST_SEED_ON_EMPTY=false`.

---

## Dự báo

Sáu lựa chọn, trong đó `AUTO` backtest tất cả rồi chọn mô hình có MASE thấp nhất (hoà thì mô
hình đơn giản hơn thắng):

| Mô hình | Cách hoạt động |
|---------|----------------|
| `NAIVE` | Giá ngày mai = giá hôm nay. Baseline, và là mẫu số của MASE. |
| `DRIFT` | Ngoại suy mức thay đổi trung bình của chuỗi huấn luyện. |
| `SMA` | Dự báo phẳng bằng trung bình k phiên gần nhất. |
| `HOLT_DAMPED` | San mũ có xu hướng tắt dần (φ < 1), grid search α/β/φ theo SSE một bước. |
| `AR_DIFF` | AR(p) trên sai phân bậc 1 — ARIMA(p,1,0) khớp bằng OLS. |

Hai chi tiết thiết kế đáng nói:

**Xu hướng bị tắt dần là cố ý.** Một mô hình xu hướng không tắt, khớp vào một đợt tăng giá
vàng, sẽ ngoại suy đợt tăng đó ra vô hạn. Đó đúng là kiểu sai mà các trang dự báo giá hay mắc.

**Khoảng tin cậy đến từ sai số out-of-sample, không phải phần dư in-sample.** Phần dư in-sample
luôn nhỏ hơn thực tế vì mô hình đã nhìn thấy chính những điểm đó rồi; khoảng tin cậy dựng từ
chúng là lý do chính khiến dashboard dự báo trông tự tin hơn mức xứng đáng. Ở đây độ rộng lấy
từ RMSE đo được tại từng bước trong backtest; khi một bước chưa đủ mẫu, nó lùi về sai số một
bước nhân √h.

**Backtest** theo kiểu rolling-origin: tại mỗi điểm gốc, mô hình được khớp lại từ đầu chỉ với
dữ liệu có trước thời điểm đó, rồi chấm điểm trên phần phía sau. Sai số được báo cáo cả tổng
hợp lẫn **tách theo từng bước dự báo** — gộp tất cả vào một con số trung bình là cách người ta
bán một dự báo quá mức.

MASE là chỉ số đáng đọc: dưới 1 thì mô hình mang thêm thông tin, từ 1 trở lên thì không, bất
kể MAPE trông đẹp đến đâu. Trên phần lớn khung thời gian, giá vàng theo ngày rất gần bước đi
ngẫu nhiên, nên MASE quanh hoặc trên 1 là kết quả bình thường — và giao diện hiển thị cảnh báo
đúng lúc đó.

### Quy đổi thế giới ↔ trong nước

```
1 troy ounce = 31,1034768 g      1 lượng (cây) = 37,5 g = 10 chỉ
VNĐ/lượng = USD/oz ÷ 31,1034768 × 37,5 × tỷ giá USD/VND
```

Khoảng cách giữa con số đó và giá SJC thực bán là **chênh lệch trong nước** — ở Việt Nam
khoản này lớn và biến động theo chính sách trong nước chứ không theo thị trường thế giới, nên
nó được hiển thị như một con số riêng thay vì gộp vào giá.

Khi so sánh, hệ thống dùng giá thế giới và tỷ giá **có hiệu lực đúng ngày của mức giá trong
nước đó**, không phải giá mới nhất: so giá niêm yết Chủ nhật với giá đóng cửa thứ Hai là so
một mức giá với thông tin chưa tồn tại ở thời điểm đó.

---

## Cấu hình

Mọi thứ nằm trong `.env` hoặc biến môi trường:

| Biến | Mặc định | Ý nghĩa |
|------|----------|---------|
| `APP_INGEST_ENABLED` | `true` | Bật/tắt toàn bộ ingest |
| `APP_INGEST_SEED_ON_EMPTY` | `true` | Sinh dữ liệu mô phỏng cho chuỗi trống. **Tắt khi chạy thật** |
| `APP_INGEST_CRON` | `0 7 * * * *` | Lịch ingest (hàng giờ, phút thứ 7) |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Origin được phép gọi API |
| `NEXT_PUBLIC_API_BASE_URL` | `http://localhost:8080` | URL API **trình duyệt** gọi tới |

Tham số mô hình nằm trong `backend/src/main/resources/application.yml` dưới `app.forecast`:
`min-history`, `max-horizon`, `backtest-min-train`, `backtest-max-origins`, `max-train-window`.

---

## Phát triển

```bash
# Chỉ chạy database
docker compose up -d db

# Backend (cần JDK 21 + Maven)
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Frontend (cần Node 22)
cd frontend && npm install && npm run dev
```

### Test

```bash
cd backend && mvn test
```

Bộ test tập trung vào phần toán và phần parse — hai nơi lỗi âm thầm gây thiệt hại nhất:

- `ForecasterTest` — từng mô hình trên chuỗi đã biết đáp án; AR(p) được kiểm bằng cách khôi
  phục hệ số của một quá trình AR(1) sinh sẵn
- `BacktesterTest` — Drift phải có sai số 0 trên đường thẳng, sai số phải tăng theo bước
- `IntervalEstimatorTest` — 95% rộng hơn 80%, nở theo √h, cận dưới không âm
- `MetricsTest` — MAE/RMSE/MAPE/MASE đối chiếu tính tay
- `IndicatorsTest` — RSI bão hoà ở 0 và 100, Bollinger co về đường giữa khi giá phẳng
- `GoldUnitsTest`, `TradingCalendarTest` — quy đổi khối lượng, lịch cuối tuần theo loại chuỗi
- `StooqCsvParserTest`, `SjcXmlParserTest`, `DateParsingTest` — CSV có dòng `N/D`, XML hỏng,
  trang HTML lỗi trả về thay XML, số kiểu `78.500` vs `1,5`, giá trị sai đơn vị

---

## Trước khi đưa lên production

- [ ] `APP_INGEST_SEED_ON_EMPTY=false` — tắt dữ liệu mô phỏng
- [ ] Đặt authentication cho `/api/v1/admin/**`: nó gọi ra ngoài và ghi vào database
- [ ] Đổi mật khẩu Postgres, dùng secret manager thay vì `.env`
- [ ] Giới hạn `APP_CORS_ALLOWED_ORIGINS` về đúng domain thật
- [ ] Kiểm tra lại `app.providers.sjc.url` còn sống; đặt cảnh báo khi ingest trả 0 dòng nhiều
      lượt liên tiếp — feed chết trông giống hệt feed yên ắng
- [ ] Cân nhắc đổi `spring.jpa.hibernate.ddl-auto` sang `validate` sau khi đã xác nhận mapping
      khớp schema (hiện đang là `none`; Flyway là nguồn chân lý của schema)
- [ ] Thêm bảng ngày lễ Việt Nam nếu cần nhãn ngày chính xác quanh Tết — hiện `TradingCalendar`
      chỉ bỏ qua thứ Bảy và Chủ nhật

---

## Giới hạn

Tất cả mô hình ở đây là **ngoại suy thống kê từ giá quá khứ**. Không mô hình nào biết đến lãi
suất, chính sách tiền tệ, biến động địa chính trị hay thay đổi quy định về vàng miếng — mà đó
mới là thứ tạo ra các cú sốc giá lớn.

Đây không phải lời khuyên đầu tư và không thay thế giá niêm yết chính thức tại quầy.

## Giấy phép

MIT
