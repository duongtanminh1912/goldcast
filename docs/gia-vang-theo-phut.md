# Giá vàng thế giới theo phút

Backend lấy spot XAU bằng USD/troy ounce từ
[Gold API](https://gold-api.com/docs), mặc định mỗi 60 giây. Theo
[bảng giá nhà cung cấp](https://gold-api.com/pricing), endpoint giá hiện tại
miễn phí; lịch sử/OHLC có hạn mức riêng. Tích hợp chỉ dùng endpoint hiện tại,
không đăng ký gói trả phí. Nhà cung cấp không cam kết tính liên tục hay độ chính
xác trong [điều khoản](https://gold-api.com/terms); kiểm thử kết nối một lần
không chứng minh SLA hoặc feed chính thức của một sở giao dịch.

## Luồng dữ liệu

`Gold API → scheduler backend trên VPS → live_gold_quote → API GoldCast → giao diện`

- Một lịch poll dùng chung cho mọi người xem; GET của người xem chỉ đọc DB.
  Trang chủ và trang XAU/USD làm mới mỗi phút, cập nhật lại khi tab trở về
  trạng thái hiển thị. Browser gọi proxy đọc riêng ở Next, không gọi nguồn ngoài.
- Parser kiểm `symbol=XAU`, `currency=USD`, giá dương và `updatedAt` hợp lệ.
  Timestamp vượt giờ nhận quá 30 giây bị từ chối. Request có deadline tổng,
  không theo redirect và body giới hạn 16 KiB trước khi buffer.
- `observedAt` là giờ nguồn cập nhật; `receivedAt` là giờ backend nhận dữ liệu.
  Tuổi giá luôn tính từ `observedAt`. API trả thành công với timestamp cũ
  không làm dữ liệu trở thành mới.
- Migration V3 thêm bảng `live_gold_quote`; giữ một quan sát mới nhất trong
  mỗi phút UTC. Poll trùng timestamp không sửa `received_at`; timestamp cũ
  trong cùng phút không ghi đè timestamp mới. Endpoint lấy giá có timestamp
  lớn nhất, nên phản hồi nguồn đi lùi không thay thế giá mới nhất.
- Giữ tối đa 30 ngày quan sát theo cấu hình; dọn cùng transaction ghi giá.
  Lỗi nguồn hoặc lỗi commit giữ giá đã lưu, không sinh giá mô phỏng. Telemetry
  chỉ ghi nhận thành công sau khi transaction đã commit.
- Giá mới không bảo đảm thay đổi từng phút. Nguồn có thể trả cùng timestamp
  hoặc cùng giá qua nhiều lượt; biểu đồ hiển thị các quan sát đã nhận, không
  tạo lịch sử trước khi triển khai hay lấp các phút không có dữ liệu.

## API và cấu hình

`GET /api/v1/market/world/live?limit=120`, giới hạn 1–1440, `Cache-Control: no-store`.
Proxy cùng đường dẫn ở frontend chỉ hỗ trợ GET tới endpoint cố định này.
Phản hồi có đơn vị, nguồn, poll interval, thời điểm nguồn/nhận/poll, `latest`,
`points` theo thứ tự thời gian và trạng thái:

| Trạng thái | Ý nghĩa |
|---|---|
| `FRESH` | Giá trong ngưỡng mới, mặc định 180 giây |
| `STALE` | Giá đã cũ trong khung phiên tham chiếu |
| `SESSION_PAUSE` | Giá cũ, đang ngoài khung phiên tham chiếu |
| `UNAVAILABLE` | Chưa có quan sát được lưu |
| `DISABLED` | Lịch lấy giá đang tắt |

HTTP 200 của endpoint snapshot không tự chứng minh giá mới: phải đọc trạng
thái và timestamp. UI giữ dữ liệu khi request lỗi và vẫn tăng tuổi giá, tránh
hiện trạng thái mới vô thời hạn khi mất kết nối.

Các biến môi trường trong `.env.example`: `APP_LIVE_GOLD_ENABLED`,
`APP_LIVE_GOLD_URL`, `APP_LIVE_GOLD_POLL_SECONDS` (60),
`APP_LIVE_GOLD_TIMEOUT_SECONDS` (10), `APP_LIVE_GOLD_STALE_SECONDS` (180),
`APP_LIVE_GOLD_RETENTION_DAYS` (30). Cấu hình lấy giá độc lập với ingest
lịch sử ngày. Giá vẫn được lấy khi Mac tắt vì backend chạy trên VPS;
monitoring/Telegram trên Mac vẫn phụ thuộc thời gian Mac/Docker hoạt động.

## Chuỗi ngày và dự báo

`price_point` tiếp tục lưu quan sát theo ngày cho thống kê, quy đổi và các
mô hình dự báo. V3 không sửa instrument/provider/ngày đóng cửa và không đẩy
giá spot trong phiên vào cột close dùng để huấn luyện. Giao diện phân biệt
giá theo phút với dữ liệu ngày. Giá phút mới không xóa cảnh báo chuỗi ngày
cũ hoặc mô phỏng; mô hình vẫn có giới hạn của lịch sử đang lưu.

Migration chỉ thêm bảng nên backend cũ có thể chạy lại trên schema sau V3;
việc quay về frontend/backend cũ sẽ không có tính năng giá theo phút.
Không tự downgrade/drop bảng khi rollback. Backup `pg_dump` toàn DB bao gồm
bảng mới; các bản backup trước V3 không có lịch sử phút của lần triển khai mới.

## Monitoring và kiểm chứng

Các gauge `goldcast_live_gold_*` đọc cache telemetry, không truy vấn DB khi
scrape. Theo dõi giá tham khảo, thời điểm quan sát/poll thành công, trạng thái
fetch và ngưỡng độ mới. Thêm ba luật cho lịch poll bị treo, fetch lỗi kéo dài
và timestamp giá cũ. Hạn chế cảnh báo trùng bằng inhibition.

Khung nhận cảnh báo giá cũ dùng lịch tham chiếu New York: nghỉ 17–18h các
ngày trong tuần, từ thứ Sáu 17h đến Chủ nhật 18h; timezone xử lý DST. Đây là
quy ước vận hành để giảm cảnh báo ngoài phiên, không phải xác nhận thị trường
đóng/mở của nhà cung cấp. Ngày lễ và lịch riêng của nguồn chưa được mô hình hóa.
Lỗi HTTP/DB và scheduler vẫn được theo dõi ngoài khung này.

G1 kiểm parser, timestamp tương lai/cũ, response chậm/quá lớn, cache khi lỗi,
commit thất bại, poll đồng thời, GET không gọi upstream, lịch tham chiếu/DST
và hợp đồng DTO Java/TypeScript. Promtool mô phỏng riêng việc fetch thành công
nhưng timestamp không mới, nghỉ phiên và lịch poll treo.

G2 chạy nguồn HTTP giả lập trong mạng CI, poll mỗi giây chỉ để kiểm thử; cả
cài mới và nâng cấp V1 có dữ liệu kiểm V3, snapshot thực, proxy qua Caddy,
mất nguồn giữ cache, từ chối timestamp tương lai, retention và checksum chuỗi
ngày không đổi. Các phép thử này không gọi Gold API thật hay dùng volume VPS.

Kết quả chạy CI và trạng thái triển khai cần ghi theo đúng commit/build;
code trên nhánh feature chưa tự chứng minh production đã chạy tính năng mới.
