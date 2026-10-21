# Monitoring — kiểm chứng ngày 21/10/2026

- Jenkins `goldcast-monitoring-ci #1`: SUCCESS, commit `d5484cf`, 357,961 giây.
- G1: 122 Java, 54 Python, 23 Node, TypeScript typecheck; promtool kiểm 31 luật và 5 kịch bản, amtool kiểm receiver/template.
- G2 cài mới và nâng cấp V1 có dữ liệu: mỗi ca 35 kiểm API, 3 trang SSR và kiểm endpoint Prometheus/metric families/release_id/management tách cổng.
- Production **skipped**; image backend production ở thời điểm này vẫn thuộc `goldcast-ci #61`.
- Alertmanager thực tế gửi 1 FIRING và 1 RESOLVED của `GoldcastMonitoringTest`: `alertmanager_notifications_total{integration="telegram"}=2`, tổng lỗi Telegram bằng 0. Không gây outage production.
- VPS: node_exporter nghe loopback, timer active, collector success=1, phiên bản HTTPS khớp last-good.
- Trung tâm: Grafana API health database=ok, dashboard UID `goldcast-operations`, 24 panel. Prometheus thu được node/Jenkins/HTTPS; backend metrics còn chờ triển khai image mới. `up` của Blackbox chỉ chứng minh scrape; phải kiểm cả `probe_success`.
- Một mẫu `docker stats --no-stream`: VPS node_exporter 8,02 MiB; trung tâm Prometheus 51,69 MiB, Grafana 310,2 MiB, Alertmanager 43,6 MiB, Blackbox 24,76 MiB, Jenkins exporter 18,04 MiB, SSH 2,02 MiB. Đây là ảnh chụp lúc cấu hình, chưa đo tải/độ ổn định dài hạn. Giới hạn Grafana tăng lên 512 MiB từ mẫu quan sát này.
- SSH key riêng đã thử: lệnh `true` bị forced command từ chối, forwarding ngoài danh sách tới 5432 bị administratively prohibited; node metrics vẫn thu được qua key mới.

JSON stage đã rút gọn và manifest digest nằm cạnh file này. Console đầy đủ,
file xác thực, chat_id, mật khẩu và cấu hình Alertmanager riêng không đưa vào
repo. Luật cảnh báo được mô phỏng bằng promtool; không coi đó là bằng chứng
mất VPS hoặc phục hồi ứng dụng thật. Có silence chuyển đổi một giờ cho ba
luật thiếu metrics/readiness backend, không chặn HTTPS/VPS/Jenkins; cần gỡ
sau triển khai hoặc kiểm lại khi hết hạn.
