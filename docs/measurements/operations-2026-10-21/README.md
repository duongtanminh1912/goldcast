# Kiểm chứng vận hành ngày 21/10/2026

Các job dưới đây chạy trên Jenkins Docker của máy Mac, cùng Docker daemon;
ứng dụng amd64 chạy qua giả lập trên arm64. Mỗi dòng là một lần chạy riêng,
không phải mẫu lặp tương đương và không dùng để so tốc độ CI/CD giữa công cụ.

| Job / build | Commit checkout | Kết quả | Thời lượng toàn build |
|---|---|---|---:|
| `goldcast-operations-ci #1` | `c077a88` | SUCCESS; G1/G2 đạt; Production skipped | 193,979 giây |
| `goldcast-operations-ci #2` | `3a20500` | SUCCESS; 119 Java, 46 Python, 22 Node và typecheck; hai ca G2 đạt; Production skipped | 146,355 giây |
| `goldcast-backup #1` | `c077a88` | SUCCESS; chụp release #60, truyền ngoài VPS, checksum và restore đạt | 33,087 giây |
| `goldcast-rollback-drill #1` | `c077a88` | FAILURE ngoài dự kiến ở bước chuẩn bị baseline; chưa chứng minh rollback | 128,438 giây |
| `goldcast-rollback-drill #2`, FAIL_AT=G2 | `3a20500` | FAILURE chủ đích; định danh không hợp lệ bị chặn trước Docker; Deploy/G3 skipped, không có artifact PASS | 4,273 giây |
| `goldcast-rollback-drill #3`, FAIL_AT=G3 | `3a20500` | FAILURE chủ đích tại G3; post.unsuccessful tự rollback, artifact PASS; cleanup sau rollback | 227,939 giây |

Mỗi thư mục giữ `build.json`, `graph.json`, `console.log` và artifact công khai
nếu có. URL job nằm trong `build.json`, truy cập từ máy có Jenkins local.
`release.env` của CI là manifest dùng cho candidate diễn tập #3. Thời lượng
được lấy từ API Jenkins, không gồm thời gian chờ hàng đợi. Build #1 CI có
45 test Python; bản sửa sau diễn tập thêm một test chống ghi đè manifest bằng
biến môi trường, nên #2 có 46. Không sửa log lịch sử thành số test mới.

## Rollback tự động trong môi trường riêng

Baseline là manifest production đã xác minh ở `goldcast-ci #60`, commit
`c31670e`. Candidate là manifest `goldcast-operations-ci #2`, commit `3a20500`,
đã qua G2. Các bộ digest khác nhau; `release.sh` kiểm image thực tế khi xác
minh baseline, G3 và rollback. G3 bị cố ý làm hỏng bằng định danh frontend sai.
Log #3 có `Public release mismatch`, tiếp đó `Rollback verified`, định danh
baseline khớp, ba trang và 35 phép kiểm API đạt. Artifact
[rollback-evidence.txt](goldcast-rollback-drill-3/rollback-evidence.txt) chỉ
được ghi sau khi kiểm con trỏ và khóa. Dọn container/network/volume diễn tập
ở `post.cleanup`, sau `post.unsuccessful`.

Lần #1 phát hiện biến môi trường parameter Jenkins ưu tiên hơn `--env-file`
và thứ tự `post.always` trước `post.unsuccessful`. Bản `3a20500` bỏ ảnh hưởng
của ba biến manifest trong lệnh Compose, có test hồi quy và chuyển dọn dẹp
sang `post.cleanup`. #1 được giữ như một mẫu thất bại, không tính là diễn tập đạt.

Không có SSH/VPS trong job diễn tập, không có cổng host hoặc volume production.
Schema ứng viên hiện vẫn V2 như baseline; phép thử xác minh ứng dụng cũ đọc
được schema hiện tại sau candidate, chưa chứng minh một migration mới bất kỳ
tương thích. Không hoàn tác DB và chưa chứng minh nhánh SSH rollback tự động
trong job production trên VPS.

## Backup và trạng thái production

Backup #1 được phục hồi trong PostgreSQL 16.15 tạm, network=none, không publish
cổng: 5 instrument, 5.317 price_point, 0 forecast_run, 0 forecast_point,
2 migration thành công, 0 thất bại. Hai manifest ứng dụng GHCR truy cập được.
Backup chứa DB/runtime/env lưu **riêng trong named volume
`goldcast-private-backups` của máy Jenkins**, không đưa vào thư mục bằng chứng.
Có thêm một lần chạy script local với kết quả đã lọc trong
[local-backup-verification.json](local-backup-verification.json).

`jobs.jsonl` xác nhận `goldcast-ci` vẫn checkout `*/main`, Poll SCM `H/5 * * * *`.
Job backup có cron `H 2 * * *`, ghim commit đã chạy đạt `c077a88` để không phụ
thuộc nhánh feature. Job rollback ghim `3a20500`, chỉ chạy thủ công. Lịch đã
được đăng ký; build #1 được khởi chạy thủ công, chưa có mẫu chạy do cron.

Kiểm VPS sau thử nghiệm: last-good vẫn release
`c31670e3d774be68ad7d279e0cc8c3f0dc62ef5c-f6650cdb917a-60`, DB/backend
healthy, frontend/Caddy running, không có `.release-lock`. Chưa merge/deploy
các thay đổi runtime của PR #55. Kết quả Jenkins/VPS lịch sử #58 vẫn ở bộ đo cũ.
