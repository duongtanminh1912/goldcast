# Số liệu chương 3 — Đánh giá pipeline G1/G2/G3 và đối chiếu Jenkins với GitHub Actions

Cập nhật mã nguồn ngày **22/10/2026**; các số đo Jenkins dưới đây giữ nguyên
thời điểm thực hiện, không gán cho bản sửa mới. Tài liệu tập trung vào
luồng **G1 → build image → G2 → Deploy → G3**, kết quả đã đo và giới hạn của
bằng chứng. Quá trình thử nghiệm theo tuần nằm trong [nhật ký](nhat-ky.md);
hướng dẫn vận hành nằm trong [tutorial](tutorial.md#b10-g1--build--g2--deploy--g3).

Bằng chứng Deploy/G3 production được lưu trong repo là **build #58 trên `main`**, commit
`a58f2cc`: G1, build image, G2, Deploy và G3 đều thành công. Có thêm phép thử
rollback thật trên VPS khi cấu hình định danh frontend sai. Build #55 và các
bảng pipeline cũ được giữ để truy nguyên, không ghép thành cùng một lần đo.

Đợt cải tiến vận hành bổ sung pin PostgreSQL/Caddy, healthcheck frontend,
retry G3 có giới hạn, cảnh báo dữ liệu cũ, timeout/retention, script backup
và job rollback biệt lập. Xem [phạm vi và bằng chứng](van-hanh-production.md).
Các job mới đã chạy và lưu riêng trong
[bộ kiểm chứng 21/10](measurements/operations-2026-10-21/README.md): CI nhánh
mới, backup có restore, G2 chặn trước deploy và G3 tự rollback biệt lập. Không
thay thời lượng #58 bằng các build feature/diễn tập hoặc kết quả local.

## 1. Phạm vi và phương pháp đo

Bổ sung trạng thái sau PR #55: Jenkins `goldcast-ci #61`, commit `73c7d8f`,
thành công 224,488 giây, G1/G2 và Production Deploy/G3 đều chạy. Đây là lần
xác minh runtime mới, chưa thay bảng stage #58 khi chưa bổ sung bộ số đo
cùng phạm vi. Monitoring có [tài liệu riêng](monitoring.md);
kết quả thử luật/cảnh báo không được tính thành thời lượng pipeline #58/#61.

| Đại lượng | Cách sử dụng |
|---|---|
| Toàn build Jenkins | `build.json.duration`, đổi mili giây sang giây; ghi cả kết quả và stage bị bỏ qua |
| Từng stage Jenkins | `graph.json.stages[].totalDurationMillis`, kèm `state` |
| Thời gian Maven | Dòng `Total time` trong console; chỉ là phần Maven, không phải toàn G1 |
| Job GitHub Actions | Gồm chuẩn bị môi trường, checkout, JDK, test và các bước hoàn tất |
| Step `Chay test backend` | Phạm vi lệnh `mvn -B test`; đối chiếu với cùng phạm vi ở Jenkins |
| Thời gian chờ | Ghi riêng theo nguồn đo; không quy toàn bộ thời gian từ lúc yêu cầu chạy đến lúc xong thành thời gian thực thi |
| Stage skipped | Ghi `SKIPPED`; thời gian xử lý điều kiện không phải thời gian thực hiện tác vụ |

Không cộng stage cha `Production` với các stage con Deploy/G3 khi chúng cùng
xuất hiện. Phần chênh giữa tổng stage và toàn build là số quan sát của từng
lần chạy, chưa xác định được nguyên nhân nếu chưa có phép đo riêng.

Mỗi mẫu cần có số build/URL, commit, nhánh, thời điểm, kết quả, kiến trúc máy,
trạng thái workspace Maven, cache BuildKit và image phụ đã có sẵn hay phải kéo.
Ghi riêng số đo trực tiếp, giá trị tính từ số đo và ước lượng. Muốn đánh giá
độ ổn định, chạy lặp ít nhất ba lần trong cùng điều kiện; giữ nguyên cả mẫu
thất bại và skipped nhưng phân loại chúng riêng.

Điều kiện môi trường đã ghi nhận trong dự án: Jenkins chạy trong Docker trên
Mac arm64, dùng Docker daemon của host; image ứng dụng được build cho
`linux/amd64`, VPS đích là x86_64. Các mẫu GitHub Actions lịch sử dùng runner
`ubuntu-latest` x86_64. Không coi hai môi trường này là phần cứng tương đương.

<a id="bổ-sung-ngày-17102026--pipeline-g1g2g3"></a>

## 2. Luồng hiện tại và phạm vi kiểm chứng

Nguồn cấu hình: [Jenkinsfile](../Jenkinsfile),
[workflow GitHub Actions](../.github/workflows/ci.yml),
[G2](../scripts/g2.sh), [release/rollback](../scripts/release.sh) và
[Compose CI](../docker-compose.ci.yml).
Commit `7494a4c` đưa vào luồng mới; `8013a3d` lấy digest từ metadata Buildx.

| Bước | Công việc trong mã hiện tại | Bằng chứng ở build #58 |
|---|---|---|
| G1 | `mvn test`, test vận hành bằng Python 3.13, test probe/snapshot bằng Node 22, `npm ci`, TypeScript typecheck và kiểm cấu hình/luật monitoring | 114 test Java, 29 test Python, 19 test Node và typecheck đạt |
| Build image | Đăng nhập GHCR; build/push backend và frontend một lần cho amd64, lấy digest từ metadata và lưu `release.env` | Stage build đạt; tái sử dụng một số layer và build lại phần ứng dụng |
| G2 | Compose riêng, không mở cổng và không dùng volume production; kiểm cài mới/nâng cấp V1 có dữ liệu mẫu, metrics và feed phút qua nguồn giả lập | Hai ca đều đạt migration, 35 phép kiểm API và 3 trang SSR |
| Deploy | Chỉ nhánh `main`, không phải PR; chuyển manifest và cấu hình release sang VPS, chạy đúng digest đã qua G2 | Đạt trên VPS, 46,920 giây |
| G3 | Readiness, image ID đúng digest, `RELEASE_ID` frontend/backend qua URL HTTPS công khai và các trang qua Caddy; đạt mới ghi `.last-good-release` | Đạt, 3,591 giây; định danh công khai và image ID khớp manifest |
| Xử lý lỗi Production | Rollback theo `previous-good-release` đã lưu; xác minh lại. Có khóa và lệnh `status`/`recover` cho lần chạy gián đoạn | Phép thử thủ công sau #58 xác nhận G3 từ chối cấu hình frontend sai và rollback thành công; chưa có ca Jenkins tự gọi rollback |

Hai ca G2 chạy cùng image ứng viên: ca đầu áp dụng migration lên DB rỗng;
ca sau khởi tạo đến V1 với ingest tắt, chèn dữ liệu vào `instrument`,
`price_point`, `forecast_run`, `forecast_point`, rồi áp dụng migration mới
nhất và kiểm dữ liệu còn nguyên. Mỗi ca chạy lại cùng bộ smoke API/trang;
không tính thành 70 loại bất biến khác nhau.

GitHub Actions hiện chạy toàn bộ G1 khi push `main` hoặc mở/cập nhật PR vào
`main`: Java, Python, Node và TypeScript đều phải đạt trong required check
`backend-test`. Jenkins còn có build image và G2, cùng Deploy/G3 có điều kiện.
Đây là phân công của cấu hình dự án, không phải giới hạn cố hữu của công cụ.
Jenkins đã build nhánh `ci/pre-deploy-gate` ở #55, nên mô tả cũ “Jenkins chỉ
thấy main” không còn phù hợp.

Các bổ sung sau #55 gồm kiểm phiên bản qua HTTP, snapshot runtime riêng cho
baseline/release, test CORS và chống ingest đồng thời. Chúng thay đổi khối
lượng kiểm tra hiện tại; số 102/19 và thời lượng #55 vẫn giữ đúng phiên bản
đã đo, không tự cập nhật thành số test hoặc thời lượng của mã mới.

Kiểm tra manifest bổ sung sau #58 từ chối khóa trùng, repository không đúng,
digest sai định dạng và khóa ngoài danh sách trước `pull`/`up`. Bộ test ở
thời điểm kiểm manifest đó có 36 test Python và 20 test Node; các số
29/19 của #58 giữ nguyên theo log.

Tính năng [giá vàng theo phút](gia-vang-theo-phut.md) trên nhánh
`feat/minute-world-gold` bổ sung V3 và test nguồn giá/cache/retention trong G2.
Kiểm tra local của bản này đạt 138 Java, 54 Python, 24 Node, typecheck và
10 kịch bản promtool; không thay số đo #58/#61 bằng các kết quả đó. Trạng thái
feature và triển khai được ghi riêng trong [nhật ký](nhat-ky.md).

## 3. Số đo Jenkins ngày 18/10/2026

### 3.1. Build #58 — toàn luồng trên main

Nguồn: [build #58](http://localhost:8081/job/goldcast-ci/58/),
[build.json](measurements/pipeline-new/build-58/build.json),
[graph.json](measurements/pipeline-new/build-58/graph.json),
[console.log](measurements/pipeline-new/build-58/console.log),
[durations.csv](measurements/pipeline-new/build-58/durations.csv) và
[release.env](measurements/pipeline-new/build-58/release.env).
Graph View lấy từ `/job/goldcast-ci/58/stages/tree`, giữ phần `data` của JSON.

| Điều kiện | Giá trị |
|---|---|
| Job / build | `goldcast-ci` / `#58` |
| Commit | `a58f2ccc5a6478f807b7e813107b596ce82d54f5` |
| Nhánh checkout | `main` |
| Thời điểm bắt đầu | 18/10/2026 20:34:25,395 — Asia/Ho_Chi_Minh |
| Nguyên nhân chạy | `Started by user Duong Tan Minh`; chủ động chạy để kiểm chứng sau khi chuyển job sang `*/main` |
| Kết quả | `SUCCESS`; Production thực thi, không skipped |
| Maven G1 | Biên dịch lại 66 source và 15 test source; `Total time: 5.392 s` |
| BuildKit | Tái sử dụng một số layer, build lại phần ứng dụng; không phải mẫu cache hoàn toàn ấm như #55 |
| Số mẫu | 1 build đầy đủ; chưa có trung bình hoặc khoảng dao động |

| Stage | Kết quả | Thời lượng (giây) |
|---|---|---:|
| Checkout SCM | SUCCESS | 1,617 |
| Checkout identity | SUCCESS | 0,704 |
| G1 - code | SUCCESS | 39,301 |
| Registry login | SUCCESS | 1,707 |
| Build images once | SUCCESS | 174,892 |
| G2 - isolated image tests | SUCCESS | 100,831 |
| Production — stage cha | SUCCESS | 50,627 |
| Deploy tested digests — con của Production | SUCCESS | 46,920 |
| G3 - verify and promote — con của Production | SUCCESS | 3,591 |
| Post Actions | SUCCESS | 0,288 |
| **Toàn build đầy đủ** | **SUCCESS** | **371,948** |

371,948 giây tương đương **6 phút 11,948 giây**. Tổng các stage cấp cao nhất
là 369,967 giây, chênh 1,981 giây so với toàn build; không cộng thêm hai stage
con vào tổng đã có Production. G2 đạt cả cài mới và nâng cấp V1 có dữ liệu:
mỗi ca có 35 phép kiểm API, đúng định danh frontend/backend và ba trang SSR.

Manifest lưu hai digest `7d239ef1…72c41` (backend), `68e67928…d3bf` (frontend).
G3 và kiểm tra VPS xác nhận image ID cùng định danh công khai khớp artifact;
`.last-good-release` trỏ tới release `a58f2cc…-f6650cdb917a-58`, phase `promoted`,
không còn khóa deploy. Không dùng thời lượng #55 để tính phần Deploy/G3 của #58.

### 3.2. Build #55 — mẫu cũ, Production skipped

Nguồn: [build #55 của job goldcast-ci](http://localhost:8081/job/goldcast-ci/55/).
Bản ghi gốc lưu trong repo:
[build.json](measurements/pipeline-new/build-55/build.json),
[graph.json](measurements/pipeline-new/build-55/graph.json),
[console.log](measurements/pipeline-new/build-55/console.log) và
[durations.csv](measurements/pipeline-new/build-55/durations.csv).
`graph.json` lấy từ bản ghi Pipeline Graph View đã lưu của build.

| Điều kiện | Giá trị |
|---|---|
| Job / build | `goldcast-ci` / `#55` |
| Commit | `81185938bbd3c99db8727f8e53fbe428a5760627` |
| Commit message | `fix: update Next.js and PostCSS dependencies` |
| Nhánh checkout | `ci/pre-deploy-gate` |
| Timestamp Jenkins | 18/10/2026 14:59:33,728 — Asia/Ho_Chi_Minh (UTC+7) |
| Kết quả toàn build | `SUCCESS`, đã kết thúc |
| Maven workspace | Có sẵn mã đã biên dịch; log ghi `Nothing to compile - all classes are up to date` |
| BuildKit | Các bước build ứng dụng trong cả hai image báo `CACHED`; stage vẫn thực hiện export/push và lấy digest |
| Node trong G1 | Container tạm chạy `npm ci`: cài 146 package trong 28 giây, sau đó typecheck |
| Số mẫu | 1 build; chưa có trung bình hay độ dao động |

Thời lượng lấy từ `build.json.duration` cho toàn build và
`graph.json.stages[].totalDurationMillis` cho từng stage, chia 1.000 để đổi từ
mili giây sang giây:

| Stage | Kết quả | Thời lượng (giây) |
|---|---|---:|
| Checkout SCM | SUCCESS | 0,857 |
| Checkout identity | SUCCESS | 0,726 |
| G1 - code | SUCCESS | 36,018 |
| Registry login | SUCCESS | 1,690 |
| Build images once | SUCCESS | 22,531 |
| G2 - isolated image tests | SUCCESS | 93,214 |
| Production — kiểm điều kiện chạy | SKIPPED | 0,090 |
| Post Actions | SUCCESS | 0,285 |
| **Toàn build, Production skipped** | **SUCCESS** | **157,211** |

157,211 giây tương đương **2 phút 37,211 giây**. Tổng thời lượng các node trong
Graph View là 155,411 giây, chênh 1,800 giây với thời lượng toàn build. Đây là
phần chênh quan sát ở riêng #55, chưa đủ để coi là chi phí cố định của Jenkins.

Console xác nhận G1 đạt 102 test backend, 19 test release và TypeScript
typecheck. Maven tự báo `Total time: 1.754 s`; đây là một thành phần của G1,
không phải thời lượng cả stage 36,018 giây. G2 đạt cả cài mới và nâng cấp V1
có dữ liệu: mỗi ca hoàn tất kiểm migration, 35 phép kiểm API và 3 trang SSR.

Log ghi `Stage "Production" skipped due to when conditional`, phù hợp với
nhánh `ci/pre-deploy-gate`. **0,090 giây chỉ là thời gian xử lý node bị bỏ qua,
không phải thời gian deploy. Deploy và G3 không chạy, chưa có số đo cho hai
bước này.** Lần chạy này cũng chưa chứng minh việc rollback/recovery trên VPS
hoặc G2 chặn một build cố tình lỗi trước production.

Đây là một mẫu thành công với workspace Maven và cache layer đã ấm. Chưa dùng
nó làm thời lượng trung bình của pipeline đầy đủ, hay so trực tiếp với tổng
job GitHub Actions/pipeline cũ có khối lượng công việc khác.

## 4. Đối chiếu Maven giữa các môi trường

Bảng dưới gộp các số Maven đã ghi trong tài liệu cũ với mẫu #55/#58. Các mẫu lịch
sử xuất phát từ log/ảnh được ghi lại trong [nhật ký](nhat-ky.md); riêng #55 có
[console gốc](measurements/pipeline-new/build-55/console.log) trong repo.
Chưa có bộ log GitHub Actions mới cùng commit `81185938bbd3` để đối chiếu #55.

| Mẫu | Số test | Maven `Total time` (giây) | Điều kiện đã ghi nhận |
|---|---:|---:|---|
| Máy cá nhân, 23/09 | 93 | 35,0 | Biên dịch lại từ đầu |
| GitHub Actions, 24/09 | 93 | 26,1 | Cache phụ thuộc `setup-java` 59 MB; runner mới |
| Jenkins #2, 27/09 | 93 | 36,9 | Lần chạy đầu trong nhóm cùng commit |
| Jenkins #3, 27/09 | 93 | 1,693 | Workspace giữ lại, chạy sau #2 khoảng 100 giây |
| Jenkins #4, 27/09 | 93 | 1,702 | Cùng commit, chạy sau #3 khoảng 4 giờ 31 phút |
| GitHub Actions, mẫu 102 test | 102 | 9,017 | Runner sạch, cache phụ thuộc ấm |
| Jenkins, mẫu 102 test lịch sử | 102 | 1,705 | Workspace bền vững, không biên dịch lại |
| Jenkins #39, 11/10 | 102 | ~1,5 | `Nothing to compile`; số được ghi làm tròn |
| Jenkins #55, 18/10 | 102 | 1,754 | `Nothing to compile`; có log gốc |
| Jenkins #58, 18/10 | 114 | 5,392 | Biên dịch lại source/test; có log gốc |

Các số này cho thấy những mẫu tái sử dụng `target/` có thời gian Maven thấp
hơn các mẫu phải biên dịch lại. Chưa thể tách ảnh hưởng của workspace khỏi
phần cứng, phụ thuộc và thời điểm chạy để kết luận Jenkins nhanh hơn GitHub
Actions bao nhiêu lần. Tỉ số 9,017 / 1,705 khoảng 5,3 chỉ mô tả hai mẫu đó.

G1 của #55 mất 36,018 giây vì còn chạy test Python, cài phụ thuộc Node và
kiểm kiểu. Không đặt G1 này cạnh một step chỉ chạy Maven rồi diễn giải chênh
lệch là tốc độ công cụ. Hai bước G1 và Dockerfile đều có lệnh biên dịch Java,
nhưng việc có thực sự biên dịch lại phụ thuộc workspace và cache layer; #55
không phải bằng chứng mã được biên dịch lại hai lần.

## 5. Bằng chứng cổng chất lượng và giới hạn của từng phép thử

| Phép thử | Kết quả đã ghi nhận | Chứng minh được | Chưa chứng minh được |
|---|---|---|---|
| Cố ý làm sai `CiGateTest`, giai đoạn CI ban đầu | 94 test, 1 lỗi; job đỏ sau 18 giây | Job Maven trả lỗi khi test thất bại | Luồng Deploy/G3 mới hoạt động |
| Cổng hợp đồng G1, tuần 6 | Đổi tên interface thành `Seriesx`: test đỏ; sửa lại: xanh. Maven 1,375 → 1,320 giây, hai mốc cách 14 giây | Test tự viết bắt được một ca lệch hợp đồng Java–TypeScript | Mọi kiểu lệch hợp đồng đều bị bắt |
| Smoke G2, tuần 7 | Đổi 37,5 gram thành 37,0: script cũ báo 18 phép thử, 1 hỏng, mã thoát 1 | Kiểm hằng số phát hiện lỗi dù công thức vẫn tự nhất quán | Pipeline G2 mới đã chặn một release lỗi trước Deploy |
| Test release ngày 17/10 và G1 #55 | 19 test đạt với lệnh Docker/VPS giả lập | Các chuyển trạng thái, rollback, cleanup, recovery và bootstrap trong các tình huống test | Rollback/recovery đã chạy thành công trên VPS |
| G2 #55 | Hai ca cài mới/nâng cấp đều xanh | Image ứng viên vượt qua các kiểm tra đã triển khai trên môi trường riêng | G3 hoặc production có cùng kết quả |
| G1/G2/Deploy/G3 #58 | Toàn bộ stage xanh, manifest và định danh HTTPS khớp | Một lần chạy đầy đủ trên main đã triển khai đúng image qua G2 | Thời lượng ổn định hoặc khả năng xử lý mọi sự cố |
| Rollback VPS sau #58 | G3 từ chối frontend sai định danh; rollback và probe lại đều đạt | Khôi phục cấu hình runtime của bản tốt, giữ đúng con trỏ và dọn khóa trên VPS thật | Quay lui sang digest ứng dụng khác, hoàn tác migration DB, hoặc nhánh Jenkins tự động gọi rollback |

Các đoạn log lịch sử dưới đây được rút gọn từ ba phép thử trong bảng, ngăn
cách bằng dòng trống. Giữ nguyên số test của từng lần, không thay bằng số test
hiện tại:

```text
[ERROR] Tests run: 94, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
Error: Process completed with exit code 1.

[ERROR] ApiContractTest.moiDtoCapCaoDeuCoInterfaceTypeScript:35
        DTO khong co interface TypeScript tuong ung.
[ERROR] Tests run: 1, Failures: 1

18 phep thu, 1 hong.
Danh sach hong:
  - hang so: taelInGrams = 37,5  (kien thuc ngoai)
ma thoat: 1
```

Ở thử nghiệm G2 cũ, các phép quy đổi vẫn xanh vì hệ thống dùng cùng hằng số
sai ở mọi nơi; kiểm giá trị chuẩn 37,5 mới phát hiện lỗi. Đây là lý do giữ cả
kiểm tính nhất quán và kiểm hằng số nghiệp vụ trong smoke test. Script mới
thêm kiểm dự báo NAIVE, trả 35 phép kiểm khi ca thành công chạy đủ.

Các phép thử cố tình làm hỏng G1/hợp đồng/công thức ở trên được thực hiện
trước hoặc ngoài build #55; #58 và phép thử rollback VPS là những mẫu riêng.
Khi đưa vào báo cáo phải gắn đúng phiên bản và môi trường, không ghép chúng
thành một lần chạy pipeline. Chi tiết xem trong [nhật ký](nhat-ky.md).

## 6. Bằng chứng cần bổ sung

Trạng thái dưới đây nói về dữ liệu **đã lưu trong repo**, không khẳng định
các tác vụ chưa từng được chạy ở nơi khác ngoài những mẫu này.

| Câu hỏi | Bằng chứng cần thu | Hiện có |
|---|---|---|
| Toàn luồng chạy được trên `main`? | Commit, URL/số build, trạng thái tất cả stage và artifact `release.env` | #58 đạt toàn luồng; có log, Graph View và manifest |
| G2 chặn release lỗi trước production? | Build G2 cố tình lỗi, Deploy bị bỏ qua, manifest production trước/sau không đổi | Job diễn tập #2 ngày 21/10 chặn định danh không hợp lệ ở G2, Deploy/G3 skipped; chưa tiêm lỗi trong job main trên VPS |
| Production dùng đúng image đã qua G2? | Manifest digest, image ID container và log G3 cùng release | #58 có đủ; log rollback VPS cũng ghi lại image ID/trạng thái sau khôi phục |
| G3 lỗi có rollback về bản trước? | Bản trước đã lưu, log G3 lỗi, rollback và xác minh, con trỏ bản tốt sau cùng | Thủ công trên VPS sau #58; thêm job diễn tập #3 ngày 21/10 tự rollback sang digest khác và có artifact PASS, trong môi trường riêng |
| Build gián đoạn có phục hồi được? | Metadata build, `status`, xác nhận đã dừng và output `recover` | Mới có test mô phỏng |
| Thời lượng ổn định của luồng mới? | Các lần chạy cùng commit/điều kiện cache, thời lượng từng stage và toàn build | 1 mẫu #55 skipped và 1 mẫu #58 đầy đủ; khác commit/cache nên không lấy trung bình chung |

Bootstrap và rollback ngày 18/10 được ghi trong
[biên bản VPS](measurements/pipeline-new/vps-2026-10-18/README.md) và
[log rollback](measurements/pipeline-new/vps-2026-10-18/rollback-drill.log).
Phép thử dùng lại digest đã qua G2 của #58 và chỉ thay cấu hình định danh
frontend để gây HTTP 503 ở endpoint phiên bản. Sau khi G3 từ chối, rollback
khôi phục cấu hình gốc, xác minh HTTPS và ba trang; backend/database không
restart. Đây là ca vận hành thủ công, không nằm trong thời lượng pipeline #58.

Khi có mẫu mới, lưu log, JSON stage, manifest và thông tin môi trường cùng
thư mục đo tương ứng. Giữ riêng các nhóm cache nguội/ấm và các lần có/không
chạy Production. Không dùng thời lượng build lỗi hoặc bị bỏ qua như một mẫu
thành công của toàn luồng.

## 7. Giới hạn của đánh giá hiện tại

- #55 và #58 khác commit, số test, cache và phạm vi thực thi. #58 có số đo
  Deploy/G3 nhưng chưa có các mẫu lặp tương đương để tính trung bình/dao động.
- Migration nâng cấp mới kiểm baseline V1 và dữ liệu mẫu ở bốn bảng; chưa bao
  phủ mọi phiên bản trước hoặc phân bố dữ liệu production.
- Rollback quay lui image/cấu hình ứng dụng, không hoàn tác migration DB.
  Migration cần tương thích với cả phiên bản ứng dụng trước và sau.
- Probe kiểm nội dung SSR qua HTTP/HTTPS, chưa kiểm tương tác JavaScript bằng
  trình duyệt và chưa thay thế giám sát dài hạn.
- Khóa không tự hết hạn; recovery cần xác nhận build/deploy đã dừng. Khóa
  thiếu owner ở một số trạng thái cần điều tra thủ công.
- Có retention bảo vệ release/backup và giới hạn log/build; chưa đo hiệu quả
  dung lượng dài hạn, chưa tự xóa Docker image/package còn có thể cần phục hồi.

## 8. Kiểm chứng vận hành ngày 21/10/2026

| Job/build | Phạm vi | Kết quả | Giây |
|---|---|---|---:|
| `goldcast-operations-ci #2`, `3a20500` | G1, build, hai ca G2 qua Caddy HTTP; Production skipped | SUCCESS; 119 Java, 46 Python, 22 Node, typecheck | 146,355 |
| `goldcast-backup #1`, `c077a88` | Release #60, truyền ngoài VPS và restore PostgreSQL riêng | SUCCESS; 5 instrument, 5.317 price_point, 2 migration thành công | 33,087 |
| `goldcast-rollback-drill #2`, `3a20500` | Cố ý lỗi định danh đầu vào G2 | FAILURE chủ đích; Deploy/G3 skipped | 4,273 |
| `goldcast-rollback-drill #3`, `3a20500` | Candidate của CI #2, baseline production #60 dựng trong mạng/DB riêng | FAILURE chủ đích ở G3; post.unsuccessful tự rollback và artifact PASS | 227,939 |

Nguồn [log/API/artifact](measurements/operations-2026-10-21/README.md). Ca G3
có hai bộ digest khác nhau, kiểm lại image, định danh, ba trang, 35 phép kiểm
API, con trỏ và khóa trước dọn tài nguyên. Không cộng thời lượng ca lỗi này
vào trung bình build thành công. Baseline/candidate cùng schema V2; chưa đo
một thay đổi schema mới hay nhánh SSH rollback tự động trên VPS.

Lần diễn tập #1 thất bại ở chuẩn bị baseline, phát hiện lỗi ưu tiên env và thứ
tự post; giữ log riêng, không tính là diễn tập đạt. Backup cron đã đăng ký
nhưng #1 được khởi chạy thủ công, chưa có mẫu cron. Production vẫn #60,
chưa merge/deploy runtime PR #55. Các số đo #55/#58 ở trên là lịch sử riêng.

## Phụ lục A — Thời lượng pipeline cũ

Các bảng này giữ số đo đã ghi ở tuần 4–11, trước luồng G1/G2/G3 hiện tại.
Nguồn lịch sử: trang Stages của job `goldcast-ci` và [nhật ký](nhat-ky.md).
Log/JSON gốc của các build này chưa được lưu trong thư mục đo hiện tại.

### A1. Nhóm 13 build được liệt kê

| Nhóm | Điều kiện đã ghi | Build | Thời lượng (giây) |
|---|---|---|---:|
| 1 | Không đổi file trong backend/frontend | #3, #10, #13, #15 | 5–6 |
| 1 | Không đổi file trong backend/frontend | #16, #12 | 7–8 |
| 2 | Backend đổi | #14, #17 | 16–19 |
| 3 | Cả hai phía đổi | #11, #9 | 38–49 |
| 4 | Base image mới hoặc đổi builder | #7 | 76 |
| 4 | Base image mới hoặc đổi builder | #5 | 155 |
| 4 | Base image mới hoặc đổi builder | #6 | 245 |

Bảng có 13 số build phân biệt. Phân nhóm mô tả những lần đã quan sát, chưa
đủ để suy ra thời lượng của commit mới. Đặc biệt ở pipeline hiện tại, thay
đổi ngoài build context vẫn có thể ảnh hưởng G1/G2 dù layer Docker được dùng lại.

| Build bổ sung | Thay đổi | Dự đoán đã ghi | Kết quả (giây) |
|---|---|---|---:|
| #18 | Chỉ docs | 6–8 giây | 7,4 |
| #19 | Thêm lockfile frontend | Hàng chục giây đến vài phút | 35 |

Hai giá trị này không thuộc bảng 13 build phía trên. Chưa có phân rã stage
của #19 trong bộ đo để quy toàn bộ mức tăng cho `npm ci`.

### A2. Stage của #16 và #17

| Stage/phạm vi | #16 (giây) | #17 (giây) |
|---|---:|---:|
| Checkout SCM | 1 | 1 |
| Test backend | 2 | 4 |
| Build backend image | 0,53 | 10 |
| Build frontend image | 0,54 | 0,27 |
| Tổng stage | 4,07 | 15,27 |
| Toàn build, Jenkins báo Took | 7,8 | 19 |
| Chênh, làm tròn | 3,7 | 3,7 |

Build backend tăng 9,47 giây, khoảng 85% mức tăng 11,20 giây của tổng stage;
bước test cũng tăng. Không suy ra chi phí cố định của Jenkins từ hai giá trị
chênh đã làm tròn. Log #17 còn ghi bước biên dịch trong image mất 8,2 giây;
đây không phải thời lượng biên dịch của image khi layer đó được dùng lại ở #55.

Ở #39, bản ghi cũ ghi backend build/push khoảng 15 giây, frontend khoảng
14 giây với các layer đã `CACHED`. Số này gồm công việc export/push và xử lý
artifact, không đại diện cho build lại mã nguồn.

## Phụ lục B — Job/step GitHub Actions và các lần chạy lặp cũ

### B1. Phạm vi job và step

Nguồn: job/step view của run #3 và run PR `feat(security)` đã ghi trong tài liệu.

| Mẫu | Phạm vi | Thời lượng (giây) |
|---|---|---:|
| Run #3, cache phụ thuộc rỗng | Job `backend-test` | 40 |
| Cùng run | Step `Chay test backend` | 33 |
| Run PR `feat(security)`, cache phụ thuộc ấm | Job `backend-test` | 21 |
| Cùng run | Step `Chay test backend` | 13 |

Phân rã run 40 giây được ghi lại như sau:

| Bước | Thời lượng (giây) |
|---|---:|
| Set up job | 2 |
| Checkout | 0 |
| Setup Java | 1 |
| Chạy test backend | 33 |
| Post setup-java | 2 |
| Post checkout | 1 |
| Complete job | 0 |
| Tổng job | 40 |

Phần ngoài step backend là 7 giây trong riêng run này. Không xem đó là hằng
số của nền tảng. Bản ghi run 21 giây có step khoảng 13 giây và Maven khoảng
9 giây; phần chênh chưa được đo riêng để gán cho shell, logging hay tác vụ nào.

### B2. Chạy lặp với cùng commit

GitHub Actions, commit `8501278`:

| Lần | Tổng hiển thị (giây) | Job `backend-test` (giây) |
|---|---:|---:|
| 1 | 75 | 34 |
| 2 | 26 | 21 |
| 3 | 24 | 19 |
| 4 | 29 | 23 |

Bản ghi lần đầu xác định 41 giây là chờ runner. Ba job sau có giá trị
19–23 giây, trung bình 21 giây; biên khoảng ±10% chỉ mô tả ba mẫu này.

Jenkins #2/#3/#4 cùng commit và 93 test, thời điểm kết thúc UTC lần lượt:
27/09 10:32:46, 10:34:26, 15:05:26. Thời gian Maven đã gộp vào mục 4. Hai
mẫu ấm 1,693 và 1,702 giây có biên khoảng ±0,3% quanh trung bình. Hai hoặc ba
mẫu chưa xác định được độ nhiễu chung hay ngưỡng chênh lệch có ý nghĩa thống kê
của một công cụ.

## Phụ lục C — Build image và truy nguyên artifact

### C1. Các số build ngoài pipeline hiện tại

| Phép đo cũ | Điều kiện/phạm vi | Thời lượng |
|---|---|---:|
| `docker compose build --no-cache` | Build cả hai image, yêu cầu không dùng lại cache bước build; trạng thái base image chưa ghi đầy đủ | 2 phút 15,5 giây |
| `docker compose up -d --build` | Build và khởi động stack; bản ghi ghi cache ấm | 36,0 giây |
| Maven trong image, arm64 bản địa | Một mẫu đã ghi | 5,4 giây |
| Maven trong image, amd64 qua QEMU | Một mẫu đã ghi | 22,3 giây |

Hai lệnh Compose làm công việc khác nhau nên không quy tỉ số thời gian hoàn
toàn cho cache. Tỉ số 22,3 / 5,4 khoảng 4,1 mô tả cặp mẫu kiến trúc này, không
phải hệ số cố định cho mọi build. Chi tiết các lần đo nhầm xem nhật ký tuần 10.

### C2. Digest trong hai lần build frontend cũ

Nguồn: log BuildKit của #16/#17 đã trích trong tài liệu trước đây.

| Thành phần | #16 | #17 |
|---|---|---|
| Base image digest | `0a7108bf…e402` | `0a7108bf…e402` |
| Image manifest | `8b9feac5…c2e7` | `8b9feac5…c2e7` |
| Image config | `23a1eb06…f145` | `23a1eb06…f145` |
| Attestation manifest | `d1d6df5b…865d` | `3a1937bc…5b54` |
| Manifest list | `ec4fda22…fedc` | `c509107e…7e41` |

Hai log này cho thấy manifest/config ứng dụng giữ nguyên trong khi
attestation và manifest list khác nhau. Chỉ kết luận trong phạm vi hai lần
quan sát; không suy ra attestation luôn phải khác hay khả năng tái lập của
mọi lần build. Với luồng hiện tại, việc Deploy dùng cùng artifact được thiết
kế bằng manifest digest qua G2; cần log G3/image ID production để kiểm chứng
việc triển khai thực tế.

### C3. Tag của pipeline cũ

Bản ghi `docker images` ở tuần 5:

| Tag | IMAGE ID được ghi |
|---|---|
| `goldcast-backend:5` | `af52e1fb8d97` |
| `goldcast-backend:6` | `72d5e2abf67a` |
| `goldcast-backend:7` | `8613ed92c89c` |
| `goldcast-backend:latest` | `8613ed92c89c` |

Tag `7` và `latest` cùng trỏ một ID trong mẫu này. Luồng mới định danh image
triển khai bằng digest, không dùng việc sửa tag cũ làm cơ chế rollback.
Các ghi chép về giữ/dọn image cũ xem nhật ký; bộ số liệu hiện chưa có phép
đo hiệu quả của chính sách dọn image.
