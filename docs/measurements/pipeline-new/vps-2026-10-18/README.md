# Bootstrap, triển khai và rollback trên VPS ngày 18/10/2026

Các quan sát dưới đây được thu trực tiếp trên `goldcast-prod`; không lấy từ
test Docker/VPS giả lập. Phép thử rollback chạy thủ công sau pipeline #58,
không cộng vào thời lượng Jenkins.

## Bootstrap trước build #58

- Trạng thái ban đầu: backend/frontend chạy image `:49`, chưa có
  `.last-good-release` và không có khóa deploy.
- Script lấy từ main `a58f2ccc5a6478f807b7e813107b596ce82d54f5`, truyền vào
  `/opt/goldcast/maintenance/bootstrap-a58f2cc`; không đổi checkout đang có.
- Sao lưu PostgreSQL bằng `pg_dump -Fc` trước khi triển khai:
  `/opt/goldcast/backups/pre-release-gates-20261018T133237Z.dump`, 83.360 byte.
  Đã xác nhận file có dữ liệu; chưa thử khôi phục bản dump này. Dump và các
  file môi trường chứa bí mật được giữ riêng trên VPS, không đưa vào Git.
- `bootstrap-release.sh` chụp cấu hình thực tế và kiểm readiness/image ID,
  HTTPS cùng ba trang. Output kết thúc bằng:

```text
Captured running images, environment, mounts and service configuration
OK page /
OK page /instruments/XAUUSD
OK page /forecast/XAUUSD?model=NAIVE&horizon=7
Verified baseline: /opt/goldcast/releases/bootstrap-20261018203238
```

Đây là trích output quan sát trong phiên bootstrap, không phải log đầy đủ.
Baseline legacy được tạo lúc 20:32:38 UTC+7, giữ volume/cấu hình runtime thật
và không restart production.

## Triển khai main qua Jenkins

Sau bootstrap, job `goldcast-ci` chuyển `Branches to build` từ
`*/ci/pre-deploy-gate` sang `*/main`; giữ các cấu hình khác, gồm Poll SCM.
Chủ động chạy [#58](http://localhost:8081/job/goldcast-ci/58/) bằng tài khoản
Jenkins, không mô tả lần chạy này là `Started by an SCM change`.

G1, build, cả hai ca G2, Deploy và G3 đều đạt. Artifact
[release.env](../build-58/release.env), [console](../build-58/console.log),
[metadata](../build-58/build.json) và [Graph View](../build-58/graph.json)
ghi cùng commit/release. Sau G3, con trỏ bản tốt là
`/opt/goldcast/releases/a58f2ccc5a6478f807b7e813107b596ce82d54f5-f6650cdb917a-58`,
phase `promoted`, không còn khóa.

## Rollback cấu hình frontend thật

[Log đầy đủ](rollback-drill.log) ghi thời điểm **14:11:17–14:11:29 UTC**
(21:11:17–21:11:29 UTC+7). Thư mục drill giữ nguyên manifest/digest đã qua G2
của #58, chỉ đổi `GOLDCAST_RELEASE_ID` trong Compose của frontend thành
`rollback-drill-wrong-identity`.

1. `release.sh deploy` lưu #58 vào `previous-good-release`, thay frontend và
   cấu hình Caddy của thư mục drill; backend/database giữ nguyên container.
2. Endpoint HTTPS `/api/release` trả **503** với định danh frontend sai và
   backend vẫn đúng #58. G3 trả lỗi `Public release mismatch`.
3. `.last-good-release` vẫn trỏ #58. Chạy `release.sh rollback` dùng mục tiêu
   đã lưu, khôi phục frontend/Caddy, đợi sẵn sàng và chạy lại probe thật.
4. Log có `Rollback verified`, định danh công khai khớp, ba trang đạt; phase
   drill là `rolled-back` và khóa được dọn. ID container frontend trước,
   trong và sau phép thử khác nhau; ID backend/database giữ nguyên.

[Phản hồi HTTPS sau rollback](public-release-after-rollback.json) khớp
`RELEASE_ID` trong manifest #58. Nội dung stack trace `Public release mismatch`
trong log là lỗi cố ý của phép thử, không phải trạng thái sau khôi phục.

Phạm vi đã chứng minh là rollback **cấu hình runtime** trên VPS thật với cùng
digest ứng dụng, do người vận hành gọi script. Chưa chứng minh Jenkins tự
gọi nhánh `post unsuccessful`, rollback sang digest ứng dụng khác, phục hồi
build gián đoạn hoặc khôi phục database. Không hoàn tác migration/schema.
