# Vận hành production và diễn tập rollback

Thay đổi ngày 21/10/2026 giữ luồng **G1 → build một lần → G2 → Deploy → G3**.
Các cấu hình dưới đây có hiệu lực trên VPS sau khi bản chứa chúng đi qua
pipeline `main`; việc sửa file local không tự thay đổi release đang chạy.

## 1. Image và readiness

PostgreSQL 16.15 và Caddy 2.11.7 được ghim bằng digest multi-platform đã đối
chiếu với image đang chạy ở release #60. Cùng digest hạ tầng xuất hiện trong
Compose CI và production. Hai digest ứng dụng vẫn nằm trong `release.env`;
digest hạ tầng nằm cố định trong Compose được đóng gói theo release. Đổi chúng
bằng PR, chạy lại G2 và kiểm tương thích DB trước khi deploy.

G2 kiểm cả đường Caddy → frontend → backend bằng HTTP trong mạng riêng, không
publish cổng. HTTPS/chứng chỉ thật được kiểm ở G3. Frontend healthcheck gọi
`/api/release`, chỉ healthy khi định danh frontend/backend khớp; Caddy đợi
frontend healthy. Probe thử lại tối đa năm lần với lỗi kết nối/502/503/504,
mỗi request tối đa 15 giây, nghỉ hai giây giữa các lần. HTTP 200 nhưng sai
định danh, hoặc nội dung trang lỗi, vẫn bị từ chối.

Các bản rollback lịch sử giữ nguyên Compose lúc phát hành, có thể vẫn dùng
tag hạ tầng. Muốn thay baseline bằng snapshot pin đủ bốn image, dùng quy trình
`bootstrap-release.sh --refresh` khi production đang khỏe và đã dừng deploy.
Không sửa lại Compose bên trong bản tốt cũ.

## 2. Độ mới dữ liệu và ingest

`MarketSummary.warnings` cảnh báo từng mã khi thiếu dữ liệu, ngày quan sát
nằm trong tương lai hoặc chậm hơn **hai ngày công bố** theo múi giờ
`Asia/Ho_Chi_Minh`. XAUUSD và tỷ giá loại thứ bảy/chủ nhật; vàng trong nước
tính mọi ngày. Chưa có lịch ngày lễ; cảnh báo dịp lễ cần được kiểm tra thủ công.
Chuỗi vàng trong nước có dữ liệu synthetic cũng được cảnh báo.

`generatedAt` vẫn là thời điểm tạo response, không phải thời điểm cập nhật giá.
Đọc ngày quan sát và cảnh báo để đánh giá dữ liệu. Độ mới không được dùng làm
readiness: nguồn ngoài ngừng cập nhật không tự khiến ứng dụng bị rollback.

Endpoint nội bộ `GET /api/v1/admin/ingest/status` yêu cầu `X-Admin-Token`, trả
lượt thử gần nhất, trạng thái đang chạy/lỗi transaction, report đã commit gần
nhất và lần nhận dữ liệu thành công của từng provider. Feed rỗng/lỗi không
ghi đè lần thành công trước; commit thất bại không ghi nhận thành công. Fetch
nhận lại dữ liệu không đổi vẫn là fetch thành công, không chứng minh giá mới.
Telemetry này reset khi backend restart; ngày quan sát lưu trong DB là căn cứ
lâu dài. Đọc từ backend qua Docker/SSH như lệnh ingest hiện có, không mở
endpoint admin ra Internet.

## 3. Giới hạn và lưu trữ

Pipeline chính giới hạn 45 phút, giữ 30 build và artifact của 20 build gần
nhất. SSH kết nối tối đa 10 giây, kiểm kết nối mỗi 15 giây và ngắt sau ba lần
không nhận phản hồi. `remote-release.sh` dùng `flock` để một lệnh đang chạy
trên VPS không đua với rollback khi SSH mất kết nối: chờ khóa tối đa 390 giây,
lệnh release tối đa 360 giây, TERM rồi KILL sau 15 giây; giới hạn ngoài 780 giây.
Khóa owner của lần deploy vẫn tồn tại xuyên Deploy → G3. Khi build bị ngắt,
kiểm tiến trình/build đã dừng rồi dùng `status`/`recover`; không xóa khóa theo tuổi.

Log mỗi service production xoay vòng `3 × 10 MB`. Không chạy Docker prune
toàn máy và không tự xóa image trên GHCR. Giữ digest của các release còn được
hỗ trợ; chính sách xóa package phải xét cả manifest và backup còn giữ.

Trên VPS có Python ≥3.9, sau khi deploy bản chứa script này, xem kế hoạch:

```bash
RELEASE_DIR=$(cat /opt/goldcast/.last-good-release)
python3 "$RELEASE_DIR/scripts/retention.py" release /opt/goldcast --keep 14
```

Thêm `--apply` để thực hiện sau khi xem kế hoạch. Script chỉ xét release
`promoted`/`rolled-back` cũ ít nhất bảy ngày; giữ last-good, mục tiêu rollback
của nó, release được container đang chạy tham chiếu, release chưa hoàn tất và
mục tiêu của các lần đó, cùng ít nhất 14 release gần nhất. Có khóa deploy/backup
thì từ chối. Không xóa volume hay Docker image; image không còn tham chiếu chỉ
nên xóa đích danh sau khi xác minh các release/backup cần phục hồi còn kéo được.

## 4. Backup ngoài VPS

Script [backup.py](../scripts/backup.py) dùng SSH key và known_hosts, chụp
release/runtime/DB dưới khóa deploy, truyền về máy khác, kiểm SHA-256 và phục
hồi vào PostgreSQL tạm không có mạng/cổng. Không dừng ứng dụng hoặc ghi DB
production. Thư mục đích bắt buộc nằm ngoài checkout, mode 700; file mode 600.

```bash
python3 scripts/backup.py \
  --key "$HOME/.ssh/jenkins_deploy" \
  --destination "$HOME/goldcast-private-backups"
```

Đích chưa tồn tại sẽ được tạo riêng tư. Nếu đã tồn tại, đặt quyền 700 trước.
Backup chứa bí mật, không thêm vào Git hay artifact công khai. Script yêu cầu
release đã promoted, `runtime.env`, Python ≥3.9, Docker, SSH và đủ dung lượng
để tải image PostgreSQL ghim digest. Dùng quy trình trong
[hướng dẫn phục hồi](dung-lai-khi-het-vps.md) để dựng VPS mới.

Job riêng `goldcast-backup` dùng [Jenkinsfile.backup](../jenkins/Jenkinsfile.backup),
credential `vps-deploy`, known_hosts đã xác minh của user Jenkins. Job hiện
ghim commit đã kiểm chứng `c077a88`; khi tạo mới sau merge có thể chọn SCM `main`.
Python/SSH/Docker CLI chạy trong runner riêng; dữ liệu nằm ở named volume
`goldcast-private-backups` trên **máy Jenkins**, khác VPS. Runner nhận SSH key
qua stdin, không ghi key vào image. Lịch `H 2 * * *` chạy khoảng 02:00 theo múi
giờ Jenkins; đổi `TZ` hệ điều hành Jenkins nếu cần giờ Việt Nam. Khi cấu hình
job mới, chạy một lần thành công để đăng ký lịch, rồi kiểm `Build periodically`.
Job fail khi chụp/truyền/checksum/restore/registry fail; kiểm mục failed builds
hằng ngày. Chưa có thông báo email/chat tự động.

Nếu backup bị ngắt và để lại `.release-lock`, `status` của release hiện tại
sẽ cho thấy owner là thư mục backup. Xác nhận cả job, SSH và `pg_dump` đã dừng
trước khi dọn khóa đó; lệnh `recover` cho release không xử lý owner backup.
Không tự xóa khóa chỉ theo tuổi. Giữ snapshot dở dang để điều tra.

Sau backup thành công, giữ 14 backup đã kiểm chứng ngoài VPS; backup chưa hoàn
tất/hỏng được giữ để điều tra và không được tính thay cho bản tốt. Trên VPS
chỉ xóa bản remote đã có dấu xác nhận truyền + restore, ngoài 14 bản mới nhất
và cũ hơn bảy ngày. Các bản thủ công cũ chưa có dấu không tự bị xóa. Có kế hoạch
dọn riêng các bản lỗi khi đã xử lý nguyên nhân. Named volume không bảo vệ khỏi
mất máy Jenkins; sao chép định kỳ backup sang thiết bị lưu trữ riêng tư khác.

## 5. Jenkins tự gọi rollback

Job thủ công riêng `goldcast-rollback-drill` dùng
[Jenkinsfile.rollback-drill](../jenkins/Jenkinsfile.rollback-drill), credential
GHCR chỉ cần nếu package private; job không có bước SSH/VPS. Nhập ba giá trị
`RELEASE_ID`, `BACKEND_IMAGE`, `FRONTEND_IMAGE` từ **cùng artifact đã qua G2**.
Job hiện ghim commit đã kiểm chứng `3a20500`. `FAIL_AT=G3` chạy ca rollback;
`FAIL_AT=G2` cố ý đặt định danh không hợp lệ để kiểm Jenkins dừng trước deploy.
Job chạy lại G2, tạo baseline #60 trong mạng/DB riêng, deploy candidate có bộ
digest khác, cố ý làm sai định danh frontend rồi để G3 fail. Nhánh
`post.unsuccessful` thật phải gọi rollback và kiểm lại image, định danh, ba
trang, 35 phép kiểm API, con trỏ và khóa. Container/volume diễn tập được dọn ở cuối.

Kết quả job cố ý **FAILURE**. Chỉ kết luận diễn tập đạt nếu artifact
`rollback-evidence.txt` có `PASS` của chính build đó; không chỉ nhìn build đỏ.
Xóa artifact/report cũ trước mỗi lần chạy để tránh lấy nhầm bằng chứng từ lần
trước. G2 fail thì không có evidence PASS. Baseline chỉ đổi sang release đã
được xác minh, không dùng `latest`.

Phép thử kiểm ứng dụng cũ chạy trên schema do candidate để lại; không hạ cấp
schema, không dùng dữ liệu production. Khi chưa thêm migration mới, nó chỉ
chứng minh tương thích schema hiện tại. Chưa thay thế diễn tập lỗi production
thật trên VPS, cũng chưa đo thao tác người dùng trong trình duyệt.

## 6. Trạng thái bằng chứng

Đã lưu [bộ kiểm chứng ngày 21/10](measurements/operations-2026-10-21/README.md):
CI nhánh mới #2 đạt G1/G2, backup #1 đạt restore và đã đăng ký cron, diễn tập
#2 chặn ở G2, #3 tự rollback sau G3 lỗi và có artifact PASS. Lần #1 diễn tập
thất bại ngoài dự kiến được giữ riêng để truy nguyên lỗi và bản sửa.

Build #58/rollback thủ công trên VPS là bằng chứng lịch sử riêng. Production
vẫn release #60, chưa triển khai thay đổi runtime của PR #55. Backup #1 được
khởi chạy thủ công; lịch đã bật nhưng chưa có mẫu chạy do cron. Diễn tập tự
động chạy trong môi trường riêng, chưa phải nhánh SSH rollback production.
