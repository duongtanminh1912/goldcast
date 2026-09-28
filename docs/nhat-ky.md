
# Nhật ký đồ án DevOps — goldcast

## Tuần 2 — 22/09/2026
**Mục tiêu:** chạy được goldcast trên máy cá nhân

Đã làm: - Kiểm tra docker
        - Tạo file cấu hình env


Vướng mắc: 
- Vì sao không viết thằng mật khẩu vào java? Nếu dùng viết thẳng mật khẩu vào java thì khi build image thì mâtj khẩu sẽ bị hàn cứng vào đấy. Mật khẩu trong image đọc ra được rất dễ nhưng không thể sửa nó mà không build lại
- .env đi tới code Java bằng đường nào?
  + Chặng 1: Viết trong .env: APP_INGEST_SEED_ON_EMPTY=true
  + Chặng 2: Docker-compose.yml đọc nó và truyền vào container : 
  backend:
  environment:
    APP_INGEST_SEED_ON_EMPTY: ${APP_INGEST_SEED_ON_EMPTY:-true}
   + "Config" trong bộ 12-Factor App

**Cách xử lý:**

**Số liệu đo được:**
- Thời gian build lần đầu:

**Ảnh chụp:**
## Tuần 3 — Continuous Integration với GitHub Actions

**Mục tiêu:** Mỗi thay đổi đẩy lên GitHub được tự động chạy test; code không
qua được test thì không vào được nhánh chính.

### Đã làm

- Viết `.github/workflows/ci.yml`: job `backend-test` chạy `mvn -B test` trên
  `ubuntu-latest`, JDK 21 (Temurin), bật cache Maven qua `actions/setup-java@v4`.
- Kiểm chứng cổng chất lượng biết chặn: thêm `CiGateTest` cố tình sai
  (`assertEquals(1, 2)`) → CI báo đỏ (CI #4, ảnh `tuan03-ci-do.png`); gỡ file ra
  → CI xanh trở lại (CI #5).
- Bật branch protection cho `main`: bắt buộc pull request, bắt buộc check
  `backend-test` xanh, và tích "Do not allow bypassing the above settings".
- Kiểm chứng branch protection: push thẳng vào `main` bị từ chối với
  `protected branch hook declined` (ảnh `tuan03-branch-protection.png`).

### Số đo — ảnh hưởng của cache Maven

Cùng một commit (`8501278`), chạy lại 4 lần bằng "Re-run all jobs".

| Lần chạy | Cache | Job `backend-test` | Total duration |
| --- | --- | --- | --- |
| Nguội (n=1) | trống | 34s | 1m 15s |
| Nóng 1 | có sẵn | 21s | 26s |
| Nóng 2 | có sẵn | 19s | 24s |
| Nóng 3 | có sẵn | 23s | 29s |

Dung lượng cache: ... MB.


**Kết luận:** cache Maven giảm thời gian job từ 34s xuống 19–23s, tiết kiệm
khoảng 13 giây (≈38%).

**Vì sao lấy cột "Job" chứ không lấy "Total duration":** Total duration bao gồm
cả thời gian chờ GitHub cấp máy ảo — ở lần nguội, 41 trong 75 giây là thời gian
xếp hàng, không liên quan đến cache. Đánh giá một thay đổi kỹ thuật thì phải đo
đúng đại lượng mà thay đổi đó tác động.

**Hạn chế:** lần nguội chỉ có 1 mẫu, nên không có khoảng dao động để đối chiếu
với khoảng 19–23s của các lần nóng.

### Nhận xét

Cùng một nguyên lý cache xuất hiện ở ba tầng khác nhau trong đồ án: lớp
`RUN mvn dependency:go-offline` trong Dockerfile, volume `~/.m2` khi chạy Docker
Compose, và `cache: maven` trên GitHub Actions. Cả ba giải quyết cùng một việc:
tách phần "tải thư viện" khỏi phần "biên dịch code", để phần ít thay đổi không
phải làm lại mỗi lần.

### Vấn đề gặp phải

1. **Branch protection không có hiệu lực trên repo private gói Free.** GitHub
   tạo được luật nhưng gắn nhãn "Not enforced"; ruleset cũng yêu cầu gói Pro trở
   lên. Ba phương án: nâng gói Pro (~4 USD/tháng), chuyển repo sang public, hoặc
   chấp nhận luật không hiệu lực. Chọn **public** vì miễn phí, giảng viên xem
   được trực tiếp, và GitHub Actions không giới hạn phút trên repo public. Trước
   khi public đã rà toàn bộ lịch sử Git để chắc chắn `.env` và khoá SSH chưa
   từng bị commit.

2. **Luật đã có hiệu lực nhưng vẫn push thẳng vào `main` được.** Nguyên nhân:
   branch protection mặc định không áp dụng cho admin của repo. Khắc phục bằng
   cách tích "Do not allow bypassing the above settings". Bài học: một cơ chế

   kiểm soát thường có sẵn ngoại lệ cho người quyền cao nhất, và ngoại lệ đó bật
   sẵn — phải chủ động đóng lại thì cơ chế mới có hiệu lực thật.

3. **Đọc sai số liệu ở lần đo đầu.** Ban đầu lấy "Total duration" (75s so với
   22s) và kết luận cache giúp nhanh hơn 3,4 lần. Mở chi tiết từng step mới thấy
   phần lớn chênh lệch là thời gian chờ máy ảo. Con số đúng là 38%.

## Tuần 4 — Jenkins, CI tự vận hành

**Mục tiêu:** Dựng một hệ thống CI thứ hai chạy trên hạ tầng tự quản lý, để so
sánh với mô hình CI được quản lý của tuần 3.

### Đã làm

- Dọn Docker: gỡ container Jenkins cũ và ba container của dự án khác đang chiếm
  cổng 8081; xoá volume `jenkins_jenkins_home` để bắt đầu sạch.
- Dựng Jenkins bằng Docker, cổng 8081, dữ liệu trong volume `jenkins_home`.
- Viết `jenkins/Dockerfile`: multi-stage build, lấy Maven từ image
  `maven:3.9-eclipse-temurin-21` sang image Jenkins chính thức.
- Viết `Jenkinsfile`: một stage `Test backend` chạy `mvn -B test` trong `backend`.
- Tạo job `goldcast-ci` kiểu Pipeline, đọc pipeline từ SCM, nhánh `*/main`.
- Cấu hình trigger Poll SCM với lịch `H/5 * * * *`.

### Số đo

| Lần chạy | Trạng thái | Build duration | Maven `Total time` |
| --- | --- | --- | --- |
| #2 (nguội) | SUCCESS | 46s | ... |
| #3 (nóng) | SUCCESS | 5.2s | 1,693 s |

Hai cột thời gian đo hai thứ khác nhau. `Total time` của Maven chỉ tính lệnh
`mvn -B test`; build duration của Jenkins tính cả clone repo và chuẩn bị
workspace. Khi so sánh với GitHub Actions phải dùng build duration.

### Nhận xét

Jenkins dùng lại cùng một workspace giữa các lần build, nên lần chạy thứ hai

không phải tải thư viện và không phải biên dịch lại — Maven chỉ mất 1,7 giây.
GitHub Actions cấp một máy ảo mới mỗi lần, nên dù có cache Maven thì vẫn phải
biên dịch lại, mất 19-23 giây ở mọi lần chạy.

Đổi lại, workspace dùng lại giữ nguyên kết quả của lần build trước, tạo ra rủi
ro build thành công nhờ tàn dư cũ chứ không nhờ mã nguồn hiện tại.

### Vấn đề gặp phải

1. **Năm plugin Pipeline cài hỏng, nguyên nhân chỉ là một.** Log cho thấy
   `pipeline-groovy-lib` timeout sau 65 giây từ một máy gương ở Romania; bốn
   plugin còn lại tải về bình thường nhưng từ chối khởi động vì thiếu nó. Cài
   riêng plugin gốc trước, rồi cài gói `Pipeline` — xong. Hai plugin phụ vẫn lỗi
   được bỏ qua có chủ ý vì không cần cho phạm vi đồ án.

2. **Image Jenkins chính thức không có Maven.** Khác hẳn GitHub Actions, nơi
   runner có sẵn hàng chục bộ công cụ. Xử lý bằng Dockerfile riêng thay vì cấu
   hình trong giao diện, để môi trường CI nằm trong Git và dựng lại được.

3. **Build đầu tiên chết sau 1,6 giây.** Nguyên nhân: `main` chưa có
   `Jenkinsfile` vì hai pull request chưa được merge. Sai lầm dẫn tới: `git
   branch -d` cho phép xoá nhánh cục bộ nên tưởng đã merge, nhưng lệnh đó chỉ
   kiểm tra commit còn tồn tại ở remote chứ không kiểm tra đã vào `main` chưa.

4. **Pull request suýt merge nhầm nhánh.** Ô `base` mặc định là nhánh xem gần
   nhất chứ không phải `main`.

5. **`README.md` bị xoá nhầm trên nhánh.** Phát hiện nhờ dòng tổng kết của pull
   request: 21 dòng thêm nhưng 265 dòng xoá. Khôi phục bằng
   `git restore --source=origin/main`. Đáng chú ý: CI vẫn xanh với thay đổi này
   vì xoá tài liệu không làm hỏng test nào. Kiểm thử tự động và việc đọc diff
   bắt hai loại lỗi khác nhau, không thay thế cho nhau.


6. **Yêu cầu review chặn merge.** Branch protection được bật kèm "Require
   approvals", mà GitHub không cho tác giả tự duyệt pull request của mình. Với
   đồ án một người, đây là bế tắc. Đã tắt yêu cầu review, giữ lại yêu cầu CI
   xanh — tức giữ phần kiểm soát tự động và bỏ phần kiểm soát bởi con người vốn
   không áp dụng được cho một người.

### Kiểm chứng tự kích hoạt

Phép thử: cấu hình Poll SCM trước, sau đó merge một pull request vào `main`, rồi
không thao tác gì trên Jenkins.

Kết quả: build #4 tự chạy sau dưới 5 phút, ghi rõ **Started by an SCM change**
thay vì *Started by user*, và liệt kê đúng commit đã kích hoạt nó. Thời gian
chạy 6,1 giây, khớp với build nóng bấm tay (5,2 giây).

Lần thử đầu không có build nào chạy. Nguyên nhân: ô **Poll SCM** đã được tích
nhưng ô **Schedule** để trống, nên Jenkins bật cơ chế mà không có lịch đi hỏi.
Jenkins có ghi cảnh báo ngay dưới ô nhập — *"No schedules so will only run due
to SCM changes if triggered by a post-commit hook"* — nhưng bằng chữ xám nhỏ.
Dấu hiệu nhận biết cấu hình đã có hiệu lực là mục **Polling Log** xuất hiện
trong giao diện job.

Lưu ý khi đọc mốc thời gian: giờ hiển thị cạnh commit là lúc commit được tạo
trên máy cá nhân, không phải lúc nó vào nhánh `main`. Độ trễ của polling phải đo
từ thời điểm merge, không phải từ thời điểm commit.
