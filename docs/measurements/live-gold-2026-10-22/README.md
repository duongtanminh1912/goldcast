# Giá vàng theo phút — kiểm chứng feature ngày 22/10/2026

Jenkins `goldcast-live-gold-ci #1`, commit
`00c8b7151670db29acf8fa92fbca8a3ee9aa9dd8`: **SUCCESS**, 390,702 giây.
Đây là job riêng cho nhánh `feat/minute-world-gold`; Production **skipped**.

| Stage | Kết quả | Giây |
|---|---|---:|
| G1 - code | SUCCESS | 44,136 |
| Build images once | SUCCESS | 196,442 |
| G2 - isolated image tests | SUCCESS | 143,614 |
| Production | SKIPPED | — |

G1 đạt 138 Java, 54 Python, 24 Node, TypeScript typecheck và 10 kịch bản
promtool; cấu hình có 34 luật cảnh báo. Cả hai ca G2 cài mới/nâng cấp V1
có dữ liệu đạt migration V3, 35 kiểm API, metrics, 3 trang SSR, hợp đồng
giá phút/proxy, GET không gọi nguồn, cache khi mất nguồn, từ chối timestamp
tương lai, retention và checksum chuỗi ngày không đổi.

Một bản xem thử biệt lập dùng đúng digest của #1 đã lấy giá Gold API thật
bằng image Java, qua proxy Next. Chrome headless kiểm trang chủ desktop
1440 px, mobile 390 px và trang XAU/USD: có giá, timestamp, đơn vị, nguồn,
không tràn ngang và không có exception chưa xử lý. Kiểm tra kết nối từ VPS
chỉ là HTTP GET đọc nguồn; không triển khai ứng dụng trên VPS.

JSON stage được rút gọn và manifest digest nằm cạnh file này. Log đầy đủ,
profile/screenshot trình duyệt và dữ liệu xem thử nằm ngoài Git. Đây là
phép thử chức năng có thời hạn, chưa chứng minh SLA, khả năng chịu tải hoặc
đã có lịch sử phút trước khi chạy collector. Số liệu ngày của bản xem thử
được seed cho kiểm thử; không coi chúng là giá thị trường thật.

Các commit tiếp theo siết điều kiện chờ poll tương lai và thêm lời giải thích
khi biểu đồ chưa đủ hai điểm; kết quả từng commit được cập nhật trong
[PR #57](https://github.com/duongtanminh1912/goldcast/pull/57), không gán lại
số đo #1 cho chúng. Feature chưa merge và chưa deploy production tại mốc này.
