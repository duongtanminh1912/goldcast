
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
