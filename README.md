[![CI](https://github.com/duongtanminh1912/goldcast/actions/workflows/ci.yml/badge.svg)](https://github.com/duongtanminh1912/goldcast/actions/workflows/ci.yml)

# GoldCast

**Đây là một đồ án về quy trình DevOps.** Ứng dụng theo dõi giá vàng bên dưới là
*đối tượng* mà quy trình xây dựng, kiểm thử, đóng gói và triển khai — không phải
mục tiêu của đồ án.

Một thay đổi đi từ `git push` tới production đang chạy thật, không có bước nào gõ tay.

> **Giá vàng ở đây là dữ liệu tổng hợp, không phải giá thật.**
> Hệ thống tự sinh chuỗi giá mô phỏng khi database rỗng. Đừng dùng con số nào trong
> này để ra quyết định tài chính.

Nếu bạn tới đây để xem cách dựng một pipeline CI/CD từ con số không:
[Quy trình DevOps](#quy-trình-devops) · [nhật ký từng tuần](docs/nhat-ky.md) ·
[số liệu đo được](docs/so-lieu-chuong-3.md) · [hướng dẫn dựng lại từ đầu](docs/tutorial.md)

---

## Quy trình DevOps

```
git push
   │
   ├──► GitHub Actions ───────────────────────────────────┐
   │      mvn test  (gồm cổng G1)                         │
   │                                                      ├──► xanh / đỏ trên pull request
   │                                                       ┘
   │
   └──► Jenkins  (poll nhánh main mỗi 5 phút)
          G1: mvn test + TypeScript typecheck + test release/rollback
          docker buildx --platform linux/amd64  ──►  GHCR (digest)
          G2: Compose riêng + cài mới/nâng cấp DB có dữ liệu + smoke + SSR
          Deploy main: đúng digest đã qua G2
          G3: readiness + image ID + HTTPS + các trang chính
          Thành công: ghi bản tốt; lỗi: rollback và xác minh lại
```

### Hai công cụ, hai vai trò khác nhau

Không phải hai lựa chọn thay thế nhau:

| | GitHub Actions | Jenkins |
|---|---|---|
| Thấy pull request | **có**, nên chặn được merge | không, chỉ theo dõi `main` |
| Môi trường | sạch mỗi lần | bền vững — giữ `~/.m2` và `target/` giữa các lần chạy |
| Sản phẩm | tín hiệu xanh hoặc đỏ | **image Docker đẩy lên GHCR + triển khai thật** |
| Chạy ở đâu | hạ tầng GitHub | máy cá nhân, sau router gia đình |

Jenkins nằm sau router gia đình nên không nhận được webhook, phải poll `main` mỗi 5
phút — mà poll thì chỉ theo dõi được nhánh, không đánh giá được pull request. Cách phân
vai trên là **hệ quả của một ràng buộc kỹ thuật**, không phải lựa chọn tuỳ ý.

### Các cổng chất lượng

**G1 — hợp đồng API.** Đối chiếu DTO Java với khai báo TypeScript của frontend. Kiểu
TypeScript bị xoá lúc biên dịch nên không gì đối chiếu chúng với JSON thật: đổi tên một
trường ở backend thì `mvn test` xanh, `tsc` xanh, còn người dùng thấy ô trống. Cổng chạy
hai chặng — so tên kiểu, rồi duyệt đồ thị kiểu để **so tên từng trường**. Nằm trong
`mvn test` nên GitHub Actions chặn được merge. Chạy mất 0,029 giây.

→ [`ApiContractTest.java`](backend/src/test/java/vn/goldcast/api/dto/ApiContractTest.java)

**G2 — nội dung API.** Script độc lập nhận `BASE_URL`, gọi API của image trong môi trường riêng trước deploy và
kiểm **bất biến** thay vì giá trị, vì dữ liệu là tổng hợp. Ví dụ:
`worldVndPerGram × troyOunceInGrams` phải bằng `usdPerOunce × vndPerUsd` — nối ba trường ở
ba khối khác nhau của cùng một phản hồi, nên công thức quy đổi sai là lộ ngay.

→ [`scripts/smoke.mjs`](scripts/smoke.mjs)

**G3 — xác minh production.** Kiểm readiness, image ID đúng digest và các trang HTTPS
qua Caddy. Chỉ ghi bản tốt khi đạt; lỗi thì quay lại manifest tốt gần nhất và kiểm lại.
Xem cách chuẩn bị baseline và giới hạn rollback DB trong [tutorial](docs/tutorial.md#b10-g1--build--g2--deploy--g3).

Cả hai cổng đều đã được **kiểm chứng bằng cách cố tình làm hỏng**: đổi tên một trường DTO
để xem G1 có đỏ không, sửa một hằng số quy đổi để xem G2 có bắt được không. Một cổng chưa
bao giờ đỏ thì không phân biệt được với một cổng không làm gì cả.

### Triển khai

| | |
|---|---|
| Máy chủ | VPS 1 vCPU / 2 GB RAM / 20 GB — 213.400đ/tháng |
| Tên miền + HTTPS | 19.000đ/năm; chứng chỉ do Caddy tự xin và tự gia hạn |
| Registry | GitHub Container Registry (GHCR), miễn phí cho repo công khai |
| Cổng mở ra internet | **chỉ 22, 80, 443** — đã quét từ một máy khác để xác nhận |
| Người dùng triển khai | `deploy`, không phải `root` |

Máy chủ được dựng bằng script chạy lại được, không gõ tay — để việc xoá sạch và dựng lại
từ đầu là chuyện vài phút thay vì vài giờ: [`deploy/`](deploy/)

Toàn bộ quy trình đã được **kiểm chứng bằng cách huỷ máy chủ và dựng lại từ con số không**
theo đúng tài liệu. Mất 27 phút, phát hiện 10 lỗi trong tài liệu, tất cả đã sửa.
[Hướng dẫn dựng lại khi hết hạn VPS](docs/dung-lai-khi-het-vps.md)

### Số liệu đo được

Thời lượng build biến thiên gần 50 lần (5 đến 245 giây) trên cùng một pipeline. Biến giải
thích không phải số lượng test, mà là **build context của Docker có thay đổi hay không**.
Mô hình bốn bậc rút ra từ đó đã đoán đúng bốn lần liên tiếp, ghi trước khi đo.

| Phép đo | Kết quả |
|---|---|
| `docker compose build` lạnh so với ấm | 2m15,5s so với 36,0s (3,8 lần) |
| Jenkins so với GitHub Actions, **cùng lạnh** | 36,9s so với 34,0s |
| Jenkins so với GitHub Actions, **cùng ấm** | 1,70s so với 26,1s |
| Image backend, trước và sau khi gộp `chown` vào `COPY` | 500 MB → 393 MB |
| Image frontend trên production | 308 MB |
| Bộ nhớ cả stack sau 3 ngày chạy | 531 MiB |

Dòng thứ hai và thứ ba quan trọng hơn vẻ ngoài của chúng. So Jenkins ấm với GitHub Actions
lạnh cho ra "nhanh hơn 15 lần", và con số đó **sai**: đo lạnh với lạnh thì hai bên gần như
bằng nhau. Thứ tạo ra chênh lệch là **tính bền vững của môi trường chạy**, không phải tốc
độ của công cụ. Nhiễu phép đo cũng khác nhau — Jenkins ±0,3%, GitHub Actions ±10% ngay cả
khi ấm — nên mọi chênh lệch dưới 10% trên GitHub Actions là vô nghĩa.

Chi tiết kèm nguồn của từng con số: [`docs/so-lieu-chuong-3.md`](docs/so-lieu-chuong-3.md)

### Hạn chế đã biết

Liệt kê trước khi bị hỏi:

| Hạn chế | Hệ quả |
|---|---|
| **Không có giám sát** | Hệ thống chết lúc 2 giờ sáng thì không ai biết |
| **Migration G2 dùng baseline V1 và dữ liệu mẫu** | Chưa bao phủ mọi phiên bản cũ và hình dạng dữ liệu production |
| **Rollback không phục hồi DB** | Migration cần tương thích với phiên bản ứng dụng trước |
| **Một máy chủ, không dự phòng** | Máy chết là dịch vụ chết; mỗi lần triển khai gián đoạn vài giây |
| **Khoá triển khai không passphrase** | Bắt buộc vì Jenkins chạy tự động; khoá đó vào thẳng production |
| **Jenkins gắn `/var/run/docker.sock`** | Quyền tương đương quản trị trên máy chạy Jenkins |
| **Chưa kiểm trình duyệt tự động** | G2/G3 kiểm HTML từ server, chưa kiểm tương tác JavaScript |

Máy 1 vCPU không chạy nổi hai bản ứng dụng cùng lúc, nên triển khai không gián đoạn và
cổng chặn trước khi triển khai đều nằm ngoài tầm của phần cứng này.

---

## Chạy thử

```bash
git clone https://github.com/duongtanminh1912/goldcast && cd goldcast
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

```bash
make logs           # xem log
make backend-test   # chạy unit test backend
make seed           # gọi ingest thủ công
make clean          # dừng và xoá volume database
```

---

## Ứng dụng làm gì

Theo dõi giá vàng thế giới (XAU/USD), tỷ giá USD/VND và giá vàng miếng trong nước, kèm dự
báo thống kê **có backtest** và khoảng tin cậy ước lượng từ sai số out-of-sample thực đo.

Nguyên tắc xuyên suốt: **mỗi con số dự báo luôn đi kèm sai số đo được của chính nó.**
Nếu mô hình không vượt được baseline naive, giao diện nói thẳng điều đó thay vì giấu đi.

```
Next.js 15 (App Router, TypeScript, Tailwind, Recharts)
        │  REST /api/v1
Spring Boot 3.3 · Java 21
        │  JPA + Flyway
PostgreSQL 16
```

### Cấu trúc

```
goldcast/
├── Jenkinsfile              pipeline triển khai
├── docker-compose.yml       môi trường phát triển
├── docker-compose.prod.yml  môi trường production (kéo image từ GHCR)
├── deploy/                  Caddyfile + script dựng máy chủ + script siết SSH
├── docker-compose.ci.yml    môi trường G2 riêng cho từng build
├── scripts/                 smoke, G2, xác minh release và rollback
├── docs/                    nhật ký, số liệu, tutorial dựng lại từ đầu
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

Chính công thức quy đổi này là thứ cổng G2 kiểm, và nó đã được kiểm chứng bằng cách cố tình
sửa sai hằng số 31,1034768 để xem cổng có đỏ không.

---

## Cấu hình

Mọi thứ nằm trong `.env` hoặc biến môi trường:

| Biến | Mặc định | Ý nghĩa |
|------|----------|---------|
| `APP_INGEST_ENABLED` | `true` | Bật/tắt toàn bộ ingest |
| `APP_INGEST_SEED_ON_EMPTY` | `true` | Sinh dữ liệu mô phỏng cho chuỗi trống. **Tắt khi chạy thật** |
| `APP_INGEST_CRON` | `0 7 * * * *` | Lịch ingest (hàng giờ, phút thứ 7) |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Origin được phép gọi API |
| `APP_ADMIN_TOKEN` | rỗng | Token cho `/api/v1/admin/**` |
| `API_BASE_URL_INTERNAL` | `http://backend:8080` | URL backend mà **frontend gọi từ phía máy chủ** |

Frontend gọi API hoàn toàn ở phía máy chủ, bên trong mạng của compose. Trình duyệt không bao
giờ nói chuyện trực tiếp với backend, nên không cần công khai URL backend ra ngoài — và đó
cũng là lý do cổng 8080 đóng hoàn toàn trên production.

Tham số mô hình nằm trong `backend/src/main/resources/application.yml` dưới `app.forecast`:
`min-history`, `max-horizon`, `backtest-min-train`, `backtest-max-origins`, `max-train-window`.

Schema database do **Flyway** quản lý (`backend/src/main/resources/db/migration`), không phải
`ddl-auto` — để schema trên máy phát triển và trên production chắc chắn giống nhau.

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

Từ thư mục gốc:

```bash
mvn -f backend/pom.xml test
python3 -m unittest discover -s scripts/tests -v
```

Bộ test release/rollback/recovery/bootstrap cũng chạy trong G1 bằng Python 3.13
container, với Docker được giả lập bằng lệnh cục bộ; không truy cập VPS.

Bộ test tập trung vào phần toán và phần parse — hai nơi lỗi âm thầm gây thiệt hại nhất:

- `ApiContractTest` — **cổng G1**, đối chiếu DTO Java với khai báo TypeScript
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

## Trước khi đưa lên production thật

Danh sách này đã làm xong trên môi trường đang chạy, giữ lại để ai dựng bản sao còn biết:

- [x] `APP_INGEST_SEED_ON_EMPTY=false` — tắt dữ liệu mô phỏng
- [x] Đổi mật khẩu Postgres, sinh thẳng vào `.env` trên máy chủ, không đi qua màn hình
- [x] Giới hạn `APP_CORS_ALLOWED_ORIGINS` về đúng domain thật
- [x] Đóng mọi cổng trừ 22, 80, 443 — và **quét từ máy khác** để xác nhận, vì Docker ghi
      luật vào chuỗi `DOCKER-USER` nằm trước ufw
- [ ] Đặt authentication cho `/api/v1/admin/**`: nó gọi ra ngoài và ghi vào database
- [ ] Đặt cảnh báo khi ingest trả 0 dòng nhiều lượt liên tiếp — feed chết trông giống hệt
      feed yên ắng
- [ ] Thêm bảng ngày lễ Việt Nam nếu cần nhãn ngày chính xác quanh Tết — hiện
      `TradingCalendar` chỉ bỏ qua thứ Bảy và Chủ nhật

---

## Ba điều rút ra

Bỏ hết tên công cụ đi thì còn lại:

1. **Một phép đo có thể chạy hoàn hảo mà vẫn trả lời sai câu hỏi.** Con số so sánh Jenkins
   với GitHub Actions chỉ đúng ở lần đo thứ tư; ba lần trước đều chạy trót lọt và đều cho ra
   số hợp lệ, cho một câu hỏi không ai đặt ra.
2. **Kiểm bằng hành vi, không kiểm bằng nhãn.** Không tin `ufw status`, không tin một lệnh
   trả về 0, và không kiểm từ bên trong hệ thống đang được kiểm.
3. **Thứ chưa được kiểm chứng thì chưa tồn tại.** Tài liệu chưa ai làm theo là bản nháp; cổng
   chưa bao giờ đỏ là cổng không làm gì.

---

## Giới hạn

Tất cả mô hình ở đây là **ngoại suy thống kê từ giá quá khứ**. Không mô hình nào biết đến lãi
suất, chính sách tiền tệ, biến động địa chính trị hay thay đổi quy định về vàng miếng — mà đó
mới là thứ tạo ra các cú sốc giá lớn.

Đây không phải lời khuyên đầu tư và không thay thế giá niêm yết chính thức tại quầy.

## Giấy phép

MIT
