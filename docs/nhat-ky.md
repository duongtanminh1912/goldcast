
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