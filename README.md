[![CI](https://github.com/duongtanminh1912/goldcast/actions/workflows/ci.yml/badge.svg)](https://github.com/duongtanminh1912/goldcast/actions/workflows/ci.yml)

# GoldCast

**Đây là một đồ án về quy trình DevOps.** Ứng dụng theo dõi giá vàng bên dưới là
*đối tượng* mà quy trình xây dựng, kiểm thử, đóng gói và triển khai — không phải
mục tiêu của đồ án.

Luồng hiện tại: G1 → build image → G2 độc lập → Deploy `main` → G3 → ghi bản tốt.
Nhánh khác `main` chạy tới G2 và bỏ qua Production. Việc chuyển production cũ
sang cơ chế release cần bootstrap một lần; xem [tutorial](docs/tutorial.md).

> **Ứng dụng có thể chứa dữ liệu mô phỏng.** Ingest thử lấy dữ liệu từ Stooq/SJC;
> khi bật seed và chuỗi còn rỗng, hệ thống sinh dữ liệu gắn nhãn `synthetic`.
> Xem nhãn nguồn và cảnh báo trên từng kết quả; đây là ứng dụng minh hoạ cho đồ án.

Nếu bạn tới đây để xem cách dựng một pipeline CI/CD từ con số không:
[Quy trình DevOps](#quy-trình-devops) · [nhật ký từng tuần](docs/nhat-ky.md) ·
[số liệu đo được](docs/so-lieu-chuong-3.md) · [hướng dẫn dựng lại từ đầu](docs/tutorial.md)

---

## Quy trình DevOps

```
git push
   │
   ├──► GitHub Actions ───────────────────────────────────┐
   │      G1: Java + Python/Node + TypeScript             │
   │                                                      ├──► xanh / đỏ trên pull request
   │                                                       ┘
   │
   └──► Jenkins  (poll nhánh cấu hình trong job)
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
| Tín hiệu trên pull request | toàn bộ G1 trong required check `backend-test` | job hiện tại poll một nhánh; Jenkinsfile hỗ trợ nhận diện PR khi có multibranch job |
| Môi trường | sạch mỗi lần | bền vững — giữ `~/.m2` và `target/` giữa các lần chạy |
| Sản phẩm | tín hiệu xanh hoặc đỏ | **image Docker đẩy lên GHCR + triển khai thật** |
| Chạy ở đâu | hạ tầng GitHub | máy cá nhân, sau router gia đình |

Jenkins trong thiết lập này dùng Poll SCM `H/5 * * * *`. Nhánh được chọn ở cấu hình
job, không bị cố định bởi Jenkinsfile. Build #55 đã chạy nhánh `ci/pre-deploy-gate`;
chỉ khi `SOURCE_BRANCH=main` và không phải PR thì stage Production mới được chạy.
GitHub Actions chạy Java, Python 3.13, Node 22 và TypeScript trong cùng check
`backend-test` đang được bảo vệ. Check chỉ xanh khi tất cả bước đạt. Jenkins
chạy cùng nhóm kiểm tra trước khi build image.

### Các cổng chất lượng

**G1 — kiểm code.** Chạy test Java, TypeScript typecheck và test Python cho các lệnh
vận hành/release, cùng test Node cho probe phiên bản và snapshot. Trong đó,
hợp đồng API đối chiếu DTO Java với khai báo TypeScript. Kiểu
TypeScript bị xoá lúc biên dịch nên không gì đối chiếu chúng với JSON thật: đổi tên một
trường ở backend thì `mvn test` xanh, `tsc` xanh, còn người dùng thấy ô trống. Cổng chạy
hai chặng — so tên kiểu, rồi duyệt đồ thị kiểu để **so tên từng trường**. Nằm trong
`mvn test` nên cũng được kiểm trên GitHub Actions.

→ [`ApiContractTest.java`](backend/src/test/java/vn/goldcast/api/dto/ApiContractTest.java)

**G2 — kiểm image trước deploy.** Dùng cùng digest sẽ triển khai, dựng Compose riêng
để kiểm cài mới và nâng cấp từ schema V1 có dữ liệu mẫu. Mỗi ca kiểm 35 bất biến API
và ba trang SSR, đối chiếu `RELEASE_ID` của frontend/backend qua `/api/release`,
với provider ngoài tắt và seed mô phỏng bật. Ví dụ:
`worldVndPerGram × troyOunceInGrams` phải bằng `usdPerOunce × vndPerUsd` — nối ba trường ở
ba khối khác nhau của cùng một phản hồi, nên công thức quy đổi sai là lộ ngay.

→ [`scripts/g2.sh`](scripts/g2.sh) · [`scripts/smoke.mjs`](scripts/smoke.mjs)

**G3 — xác minh production.** Kiểm readiness, image ID đúng digest và các trang HTTPS
qua Caddy. `/api/release` phải trả đúng `RELEASE_ID` của cả frontend và backend
so với manifest; trang từ bản cũ không được chấp nhận. Chỉ ghi bản tốt khi đạt;
lỗi thì quay lại bản tốt đã lưu ở đầu deploy và kiểm lại.
Xem cách chuẩn bị baseline và giới hạn rollback DB trong [tutorial](docs/tutorial.md#b10-g1--build--g2--deploy--g3).

Các kiểm tra hợp đồng G1 và công thức G2 đã được **kiểm chứng bằng cách cố tình làm hỏng**: đổi tên một trường DTO
để xem G1 có đỏ không, sửa một hằng số quy đổi để xem G2 có bắt được không. Một cổng chưa
bao giờ đỏ thì không phân biệt được với một cổng không làm gì cả. Bằng chứng pipeline
mới hiện có là build #55: G1/G2 đạt, Production bị bỏ qua. Chưa có log Deploy/G3/rollback
thực tế trên VPS cho luồng mới trong bộ số liệu đã lưu.

### Môi trường triển khai đã ghi nhận

| | |
|---|---|
| Máy chủ | VPS 1 vCPU / 2 GB RAM / 20 GB — 213.400đ/tháng |
| Tên miền + HTTPS | 19.000đ/năm; chứng chỉ do Caddy tự xin và tự gia hạn |
| Registry | GitHub Container Registry (GHCR), miễn phí cho repo công khai |
| Cổng mở ra internet | Cấu hình chỉ publish 80/443 qua Caddy; SSH dùng 22. Lần quét lịch sử được ghi trong nhật ký |
| Người dùng triển khai | `deploy`, không phải `root` |

Máy chủ được dựng bằng script chạy lại được, không gõ tay — để việc xoá sạch và dựng lại
từ đầu là chuyện vài phút thay vì vài giờ: [`deploy/`](deploy/)

Pipeline cũ đã được thử bằng cách huỷ máy chủ và dựng lại, mất 27 phút theo nhật ký.
Kết quả đó không xác nhận bootstrap hay G3 của luồng mới. Hướng dẫn khôi phục hiện
dùng manifest theo digest và kiểm baseline trước khi nối lại Jenkins.
[Hướng dẫn dựng lại khi hết hạn VPS](docs/dung-lai-khi-het-vps.md)

### Số liệu đo được

Build #55 (`81185938bbd3`, ngày 18/10/2026) là mẫu Jenkins đã lưu của luồng mới:

| Phép đo | Kết quả |
|---|---|
| G1 | 36,018 s |
| Build/push hai image | 22,531 s |
| G2, gồm hai ca DB | 93,214 s |
| Tổng build | 157,211 s |
| Production | `SKIPPED` vì chạy nhánh `ci/pre-deploy-gate` |

Đây là một mẫu có cache Docker và Maven biên dịch tăng dần, chưa phải thời lượng
đại diện hay thời gian triển khai đầy đủ. Các phép đo Docker/Maven cũ được giữ ở
phụ lục số liệu; không so thời gian Maven của Jenkins với toàn job GitHub Actions,
và không dùng độ dao động của vài mẫu làm ngưỡng kết luận chung.

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

G2 chạy trên Docker của Jenkins, trước khi chạm VPS. Giới hạn một VPS hiện tại là
chưa có triển khai không gián đoạn; giới hạn đó không ngăn được G2 trước deploy.

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

Lần khởi động đầu, backend chạy ingest sau khi sẵn sàng. Với cấu hình dev mặc định,
chuỗi nào vẫn rỗng sau ingest sẽ được seed **dữ liệu mô phỏng có tính xác định** —
xem phần [Dữ liệu mô phỏng](#dữ-liệu-mô-phỏng).

```bash
make logs           # xem log
make backend-test   # chạy unit test backend
make seed           # gọi ingest thủ công
make clean          # dừng và xoá volume database
```

`make seed` chạy trong container backend và gửi header `X-Admin-Token`. Đặt
`APP_ADMIN_TOKEN` trong `.env`, rồi chạy `docker compose up -d backend` để áp dụng
trước khi gọi. Token rỗng khiến lệnh thất bại; phản hồi HTTP lỗi cũng trả mã lỗi.
Lệnh này chạy ingest từ provider và chỉ seed chuỗi rỗng nếu đã bật seed.

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
| GET | `/api/v1/release` | Định danh backend; frontend đối chiếu qua `/api/release` |

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

Khi bật seed, chuỗi nào vẫn hoàn toàn trống sau ingest sẽ được nạp chuỗi mô phỏng
có tính xác định. Ba điểm cần biết:

- Mọi dòng được gắn `source = "synthetic"`, và API lẫn giao diện đều cảnh báo rõ.
- Seed chỉ thêm vào chuỗi rỗng. Nếu nguồn thật phục hồi sau khi seed, dữ liệu thật có thể
  cùng tồn tại với các điểm mô phỏng còn lại; hệ thống không tự xoá lịch sử mô phỏng.
- Chuỗi trong nước được suy ra từ chuỗi thế giới + tỷ giá mô phỏng cộng một mức chênh lệch
  trôi dần, nên phần quy đổi và chênh lệch vẫn nhất quán với nhau.

Compose dev mặc định bật seed; Compose production mặc định tắt. Đặt rõ
`APP_INGEST_SEED_ON_EMPTY=false` trong `.env` production nếu chỉ muốn dữ liệu từ provider.
Tắt seed không xoá những điểm mô phỏng đã lưu trước đó.

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
| `APP_INGEST_SEED_ON_EMPTY` | dev `true`, production `false` | Sinh dữ liệu mô phỏng cho chuỗi trống khi được bật |
| `APP_INGEST_CRON` | `0 7 * * * *` | Lịch ingest (hàng giờ, phút thứ 7) |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Origin được phép gọi API |
| `APP_ADMIN_TOKEN` | rỗng | Header `X-Admin-Token` cho `/api/v1/admin/**`; rỗng thì khoá endpoint |
| `BACKEND_IMAGE`, `FRONTEND_IMAGE` | bắt buộc với Compose production | Hai digest từ artifact `release.env` của build đã qua G2 |
| `RELEASE_ID` | bắt buộc với Compose CI/production | Cùng định danh truyền tới frontend/backend và được G2/G3 kiểm qua HTTP |
| `API_BASE_URL_INTERNAL` | Compose: `http://backend:8080`; ngoài Docker: `http://localhost:8080` | URL backend mà **frontend gọi từ phía máy chủ** |

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
node --test scripts/tests/test_*.mjs
```

Bộ test release/rollback/recovery/bootstrap cũng chạy trong G1 bằng Python 3.13
container, với Docker được giả lập bằng lệnh cục bộ; không truy cập VPS.

Baseline chụp image digest của cả bốn service, môi trường, command, healthcheck
và volume thực tế từ Docker. Các snapshot và `runtime.env` có thể chứa bí mật,
được lưu quyền 600 trên VPS. Nếu baseline được tạo trước cơ chế này, dùng
`sh scripts/bootstrap-release.sh --refresh` khi production đang khỏe và không
có deploy đang chạy; xác minh lỗi thì con trỏ cũ được giữ nguyên. Mỗi deploy
mới cũng giữ bản riêng của `.env` để rollback dùng lại cấu hình của release.

Ingest thủ công và scheduler dùng chung khóa trong service, giữ qua cả commit
hoặc rollback transaction. Yêu cầu thủ công chạy chồng được trả HTTP 409.

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

Những kiểm tra cần làm trên từng môi trường; trạng thái VPS không được xác nhận chỉ
bằng cấu hình trong repo:

- [ ] Đặt rõ `APP_INGEST_SEED_ON_EMPTY=false` nếu chỉ sử dụng dữ liệu provider
- [ ] Đổi mật khẩu Postgres, sinh thẳng vào `.env` trên máy chủ
- [ ] Giới hạn `APP_CORS_ALLOWED_ORIGINS` về đúng domain thật
- [ ] Đặt `APP_ADMIN_TOKEN` và xác nhận gọi thiếu/sai token bị trả 401; filter đã có trong code
- [ ] Quét cổng từ máy khác: chỉ 22, 80, 443 mở. Docker có thể publish cổng vượt qua luật ufw
- [ ] Bootstrap baseline khỏe mạnh; xác nhận `.last-good-release` và thử Deploy/G3 có lưu log
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
