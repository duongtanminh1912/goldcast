# Số liệu cho chương 3 — So sánh Jenkins với GitHub Actions

Mọi con số dưới đây đo trực tiếp trên hệ thống, không ước lượng. Nguồn của từng
số ghi kèm để kiểm chứng lại được.

## Cảnh báo về cách trích số

GitHub Actions hiển thị **ba** con số lồng nhau. Phải nói rõ dùng con số nào:

| Con số | Gồm những gì |
|---|---|
| Total duration | thời gian **chờ runner** cộng mọi thứ dưới |
| Job `backend-test` | dựng runner, checkout, cài JDK, chạy test |
| Step `Chay test backend` | chỉ `mvn -B test` |

Tuần 3 đã tính sai một lần vì dùng Total duration: trong 75 giây có 41 giây là
thời gian xếp hàng chờ runner. Con số đó nói GitHub đang bận, không nói gì về
pipeline. **Không dùng Total duration trong báo cáo.**

Jenkins báo "Took" ở cấp build, tương đương "Job" của GitHub Actions.

## Bảng 1 — Thời lượng build Jenkins, 14 điểm đo

Nguồn: trang Stages của job `goldcast-ci`.

| Bậc | Nguyên nhân | Build | Thời lượng |
|---|---|---|---|
| 1 | Không file nào trong `backend/` hay `frontend/` đổi | #3, #10, #13, #15 | 5–6 s |
| 1 | | #16, #12 | 7–8 s |
| 2 | Mã backend đổi | #14, #17 | 16–19 s |

| 3 | Cả hai phía đổi | #11, #9 | 38–49 s |
| 4 | Base image mới hoặc đổi builder | #7 | 76 s |
| 4 | | #5 | 155 s |
| 4 | | #6 | 245 s |

Biến thiên 5 giây tới 245 giây — gần **50 lần** trên cùng một pipeline, cùng ba
stage.

**Biến giải thích không phải số lượng test, mà là build context của Docker có
đổi hay không.** Build #16 thêm 200 dòng JavaScript thật (`scripts/smoke.mjs`)
và chỉ mất 7,8 giây, vì file đó nằm ngoài cả hai context `./backend` và
`./frontend` — Docker không nhìn thấy nó tồn tại. Đây là phép đối chứng rõ nhất
trong bộ số liệu: nó phân biệt được "repo có đổi" với "build context có đổi".

**Đừng vẽ biểu đồ đường theo số build.** Dữ liệu này không có xu hướng theo thời
gian; thời lượng do nội dung commit quyết định. Một đường từ #2 tới #17 sẽ gợi ý
một xu hướng không tồn tại. Biểu đồ đúng là biểu đồ cột nhóm theo bốn bậc trên.

### Hai dự đoán được ghi trước khi đo

Mô hình bốn bậc ở trên được rút ra từ dữ liệu đã có, nên nó mới chỉ là **mô tả**.
Để kiểm xem nó có phải là **hiểu cơ chế** hay không, hai lần tiếp theo được dự
đoán trước, viết ra trước khi commit, rồi mới đo.

| Build | Thay đổi | Dự đoán ghi trước | Thực tế |
|---|---|---|---|
| #18 | chỉ chạm `docs/` | bậc 1, 6–8 s | **7,4 s** |
| #19 | thêm `frontend/package-lock.json` | bậc 3–4, hàng chục giây tới vài phút | **35 s** |

Build #19 đáng chú ý nhất: từ 7,4 lên 35 giây mà **không một dòng mã nguồn nào
đổi**. Chỉ thêm một file khoá phụ thuộc, làm vỡ tầng
`COPY package.json package-lock.json* ./`, kéo theo `npm ci` phải chạy thật.

Một mô hình giải thích được dữ liệu đã thấy thì dễ. Một mô hình đoán đúng dữ liệu
chưa tồn tại thì khó hơn, và chỉ loại thứ hai mới đáng gọi là hiểu cơ chế.

*Còn cần kiểm: thời gian tăng thêm của #19 có nằm đúng ở stage `Build frontend
image` không. Tổng khớp chưa chứng minh được chỗ khớp.*

## Bảng 2 — Thời lượng từng stage

Nguồn: `localhost:8081/job/goldcast-ci/<số>/stages/`.

| Stage | #16 (không chạm backend) | #17 (backend đổi) |
|---|---|---|
| Checkout SCM | 1 s | 1 s |
| Test backend | 2 s | 4 s |
| Build backend image | 0,53 s | 10 s |
| Build frontend image | 0,54 s | 0,27 s |
| Tổng bốn stage | 4,07 s | 15,27 s |
| Jenkins báo "Took" | 7,8 s | 19 s |
| Chênh lệch | 3,7 s | 3,7 s |


Hai kết luận từ bảng này:

**Chi phí cố định của Jenkins là 3,7 giây**, trùng khít ở cả hai build dù khối
lượng việc chênh gần bốn lần. Nghĩa là sàn của pipeline này khoảng 4 giây, và
tối ưu các stage xuống nữa gần như vô nghĩa.

**Toàn bộ chênh lệch nằm ở một stage.** `Build backend image` từ 0,53 lên 10
giây, gấp 19 lần. `Test backend` chỉ từ 2 lên 4 giây.

## Bảng 3 — Cùng một công việc, hai môi trường

Phép so sánh phải đặt `mvn -B test` cạnh `mvn -B test`, không đặt tổng cạnh tổng:
Jenkins chạy ba stage, GitHub Actions chỉ chạy test.

| Chạy 97–102 test | Thời lượng | Nguồn |
|---|---|---|
| GitHub Actions, job `backend-test`, cache rỗng | 40 s | run #3, job view |
| GitHub Actions, step `Chay test backend`, cache rỗng | 33 s | run #3, step view |
| GitHub Actions, job `backend-test`, cache ấm | 21 s | run của PR `feat(security)` |
| GitHub Actions, step `Chay test backend`, cache ấm | 13 s | cùng run |
| Jenkins, stage `Test backend`, không đổi mã | 2 s | #16 |
| Jenkins, stage `Test backend`, mã đổi | 4 s | #17 |

Chênh **8 đến 20 lần** cho đúng cùng một lệnh.

Nguyên nhân không phải máy GitHub yếu:

- **Runner GitHub sạch mỗi lần.** Máy ảo mới, `target/` không tồn tại, Maven
  phải biên dịch lại toàn bộ trước khi chạy test nào.
- **Jenkins giữ workspace.** `target/` còn từ build trước. Log Maven nói đúng
  điều đó: `Nothing to compile — all classes are up to date`, và `Total time:
  1,705 s` cho 102 test.


## Cái giá của 2 giây

Workspace bền vững nhanh nhưng **tích luỹ trạng thái**. Xoá một file Java thì
file `.class` cũ vẫn nằm trong `target/`; test có thể vẫn xanh dựa trên một class
không còn trong mã nguồn, và vỡ trên máy người khác clone về.

Đó chính là loại lỗi runner sạch bắt được và Jenkins không bắt được. **40 giây là
tiền trả cho bảo đảm rằng build được từ mã nguồn, không phụ thuộc trạng thái sót
lại từ lần trước.**

## Mã nguồn được biên dịch hai lần mỗi build

- Stage `Test backend`: biên dịch gia tăng, ~2 giây
- Stage `Build backend image`: container mới, `target/` rỗng, biên dịch lại từ
  đầu, **8,2 giây** (số lấy từ dòng `#12 DONE 8.2s` trong log BuildKit của #17)

Đây là lựa chọn có ý thức, không phải sơ suất:

| Cách | Được | Mất |
|---|---|---|
| Dockerfile tự biên dịch (đang dùng) | image dựng lại được **chỉ từ mã nguồn**, không cần Jenkins | biên dịch hai lần, ~8 s |
| Jenkins build jar rồi `COPY` vào image | biên dịch một lần | image **không tự dựng lại được**, phải có đúng hệ thống CI đó |

Một image mà chỉ Jenkins của bạn tạo ra được thì không ai kiểm chứng lại được.

## Luận điểm chính: hai trục, không phải một

| | GitHub Actions | Jenkins |
|---|---|---|
| Thấy pull request | **có** → chặn được merge | không, chỉ thấy `main` |
| Môi trường | sạch mỗi lần | bền vững |
| Thời gian chạy test | 40 s / ~25 s | **2–4 s** |

| Bắt lỗi "chỉ chạy trên máy tôi" | **có** | không |
| Tạo artifact có tag | không | **có** |
| Công sức cài đặt | một file YAML, xanh trong một buổi | trọn một tuần |
| Kích hoạt | webhook, tức thì | poll 5 phút, gộp nhiều merge thành một build |
| Khi tắt máy | vẫn chạy | không chạy |
| Chi phí | miễn phí với repo public | điện và máy cá nhân |

Hai trục đầu — ai thấy PR, và môi trường sạch hay bền — **độc lập với nhau**, và
trên cả hai thì hai công cụ **bổ sung** chứ không thay thế.

Bỏ GitHub Actions thì mất cổng chặn merge **và** mất bảo đảm build sạch. Bỏ
Jenkins thì mất artifact có tag.

Phân vai này không phải giải pháp chắp vá vì thiếu thốn. Nó là hệ quả của một ràng
buộc kỹ thuật thật: Jenkins chạy sau router gia đình nên không nhận được webhook,
phải poll — mà poll thì chỉ theo dõi được nhánh, không đánh giá được pull request.

## Ba lần số liệu nói "đừng tối ưu"

1. **Bỏ build image khi chỉ đổi tài liệu.** Build #12 là merge tài liệu thuần
   tuý, tốn 8,3 giây. Viết logic phát hiện "có chạm mã nguồn không" tốn công hơn
   số giây tiết kiệm được, và thêm một chỗ có thể sai.
2. **Đổi `-DskipTests` thành `-Dmaven.test.skip=true`** để khỏi biên dịch test
   trong image build. Đo từ log: 0,45 giây trên tổng 8,2 giây của bước đó.
3. **Tối ưu các stage.** Chi phí cố định 3,7 giây của Jenkins chiếm gần một nửa
   build ngắn. Sàn là khoảng 4 giây bất kể làm gì.

## Phụ lục — Tính tái lập, câu trả lời chính xác cho câu hỏi tuần 5

So hai log BuildKit của stage `Build frontend image`, build #16 và #17, cách nhau
một ngày:


| | #16 | #17 | |
|---|---|---|---|
| Base image digest | `0a7108bf…e402` | `0a7108bf…e402` | giống |
| `exporting manifest` | `8b9feac5…c2e7` | `8b9feac5…c2e7` | **giống** |
| `exporting config` | `23a1eb06…f145` | `23a1eb06…f145` | **giống** |
| `exporting attestation manifest` | `d1d6df5b…865d` | `3a1937bc…5b54` | **khác** |
| `exporting manifest list` | `ec4fda22…fedc` | `c509107e…7e41` | **khác** |

Manifest và config là digest định danh **nội dung** image. Giống nhau nghĩa là
hai image y hệt nhau.

Attestation manifest là *provenance attestation* — bản ghi BuildKit tự sinh, mô
tả build đó diễn ra lúc nào, bằng builder nào, từ nguồn nào. Nó ghi lại lịch sử
nên **bắt buộc phải khác** giữa hai lần build; đó là mục đích tồn tại của nó.
Đây là hiện vật theo chuẩn SLSA về chuỗi cung ứng phần mềm, không phải metadata
vặt của Docker.

Manifest list gói cả hai thứ trên, nên nó khác. Và chính manifest list là thứ
`docker images` hiển thị ở cột IMAGE ID.

Chuỗi nhân quả đầy đủ:

> Nội dung giống → manifest giống → attestation ghi thời điểm build nên khác →
> manifest list gói cả hai nên khác → `docker images` báo ID khác.

Tắt được bằng `docker build --provenance=false`, nhưng **không nên tắt**:
provenance chính là thứ trả lời "image này tạo từ commit nào, bằng builder nào,
lúc nào" — đúng câu hỏi mà truy xuất nguồn gốc artifact cần.

Phát biểu đúng:

> **Nội dung image tái lập được. Danh tính cấp cao thì không, một cách có chủ

> đích, vì nó mang theo lịch sử của chính lần build đó.**

## Con số thuần khiết nhất: Maven tự đo chính nó

Mọi con số ở trên đều lẫn chi phí của hệ thống CI. Nhưng Maven in ra thời gian
của chính nó ở cuối mỗi lần chạy, và con số đó loại sạch mọi thứ khác: không
dựng runner, không checkout, không cài JDK, không chi phí Jenkins.

Cùng 102 test, cùng lệnh `mvn -B test`:

| Môi trường | Maven báo `Total time` |
|---|---|
| GitHub Actions, runner sạch, cache Maven ấm | **9,017 s** |
| Jenkins, workspace bền vững | **1,705 s** |

Chênh **5,3 lần**, và toàn bộ nằm ở một nguyên nhân: trên runner GitHub thư mục
`target/` rỗng, nên Maven phải biên dịch lại toàn bộ mã nguồn cộng 1.106 dòng
test trước khi chạy được test nào. Trên Jenkins nó mở `target/` ra, thấy mọi thứ

còn nguyên và chưa đổi, rồi chỉ chạy test.

Đây là con số nên đưa vào báo cáo khi muốn nói "cùng một công việc", vì nó là
con số duy nhất không ai bắt lỗi được về tính so sánh được.

## Bóc tách 21 giây của GitHub Actions

| Phần | Thời lượng |
|---|---|
| Dựng runner, checkout, cài JDK, phục hồi cache | ~7 s |
| Chi phí của step (shell, đổi thư mục, ghi log) | ~4 s |
| **Maven làm việc thật** | **9 s** |
| Post actions | ~1 s |
| Tổng job | 21 s |

So với Jenkins build #17:

| Phần | Thời lượng |
|---|---|
| Chi phí cố định của Jenkins | 3,7 s |
| Checkout SCM | 1 s |
| **Maven làm việc thật** | **1,7 s** |
| Hai stage build image | 10,3 s |
| Tổng build | 19 s |

Hai bảng này cho thấy điều đáng nói nhất: **Jenkins làm nhiều việc hơn trong
thời gian ngắn hơn** — nó còn đóng gói hai Docker image mà GitHub Actions không
làm. Nhưng nó làm được vậy nhờ tái dùng trạng thái của lần trước, tức là nó
**không kiểm chứng được** điều mà runner sạch kiểm chứng được.

## Bổ sung: số đo tuần 2–3 và tuần 10–11

Phần này gom các số đo còn nằm rải rác trong ảnh chụp màn hình và log, kèm
**điều kiện đo** của từng con số. Điều kiện quan trọng ngang con số: phần lớn
sai lầm khi so sánh CI đến từ việc đem một lần chạy cache nguội đọ với một lần
cache nóng.

### Điều kiện chung

| | |
|---|---|
| Máy cá nhân | MacBook Pro, kiến trúc arm64 |
| Máy chủ | VPS 1 vCPU / 2 GB, x86\_64 |
| GitHub Actions | runner `ubuntu-latest`, x86\_64 |
| Jenkins | chạy trong Docker trên chính máy Mac, dùng chung Docker daemon của host |

### Build Docker trên máy cá nhân

| Lệnh | Cache | Thời gian |
|---|---|---|
| `docker compose build --no-cache` (cả 2 image) | nguội hoàn toàn | **2 ph 15,5 s** |
| `docker compose up -d --build` | nóng | **36,0 s** |

Chênh **3,8 lần**. Đây là lý do mọi con số build phải ghi kèm trạng thái cache,
nếu không thì vô nghĩa.

### Chạy `mvn test` ở ba nơi

| Nơi | Số phép thử | Thời gian Maven | Ghi chú |
|---|---|---|---|
| Máy cá nhân (23/09) | 93 | 35,0 s | biên dịch lại từ đầu |
| GitHub Actions (24/09) | 93 | 26,1 s | cache `setup-java` 59 MB |
| Jenkins, build #39 (11/10) | 102 | 1,5 s | **không so sánh được** |

Con số 1,5 giây của Jenkins **không phải** bằng chứng Jenkins nhanh hơn. Log
ghi rõ `Nothing to compile - all classes are up to date`: workspace của Jenkins
còn nguyên từ lần build trước, nên nó chỉ chạy lại phép thử chứ không biên dịch
gì. Đem con số này đọ với 26,1 giây của GitHub Actions là so hai việc khác nhau.

Tương tự, toàn bộ thời gian build image trong build #39 (backend ~15 s,
frontend ~14 s) đều rơi vào trạng thái mọi layer đều `CACHED`, chỉ còn thời
gian đẩy lên registry. Jenkins chạy trên máy Mac nên việc cài lại máy chủ không
đụng tới cache của nó.

Số phép thử tăng từ 93 lên 102 trong khoảng 23/09 đến 11/10. Hai con số đều
đúng, chỉ khác thời điểm.

### Phân rã một job GitHub Actions

| Bước | Thời gian |
|---|---|
| Set up job | 2 s |
| `actions/checkout@v4` | 0 s |
| `actions/setup-java@v4` | 1 s |
| Chạy test backend | 33 s |
| Post `setup-java` | 2 s |
| Post `checkout` | 1 s |
| Complete job | 0 s |
| **Tổng job** | **40 s** |

Khoảng 7 giây là chi phí cố định của nền tảng, không liên quan tới mã nguồn.
Với một job 33 giây thì đó là **21% chi phí phụ**.

### Nhiễu: bốn lần chạy lại trên cùng một commit

Cùng commit `8501278`, không sửa một dòng nào, bấm Re-run bốn lần:

| Lần | Tổng | Riêng job `backend-test` |
|---|---|---|
| 1 | 1 ph 15 s | 34 s |
| 2 | 26 s | 21 s |
| 3 | 24 s | 19 s |
| 4 | 29 s | 23 s |

Lần đầu chậm gấp ba vì cache nguội. Ba lần sau đều nóng, nhưng vẫn dao động
19–23 giây, tức khoảng **±10%** quanh trung bình 21 giây.

**Hệ quả cho chương 3:** so sánh Jenkins với GitHub Actions bằng một lần chạy
mỗi bên là vô nghĩa, vì chênh lệch 10% có thể đến hoàn toàn từ nhiễu của hạ
tầng. Mọi so sánh phải chạy lặp ít nhất ba lần ở trạng thái cache nóng, và phải
nói rõ đang so cái gì.

### Bằng chứng cổng chất lượng thật sự chặn

Hai thứ dưới đây không phải số đo mà là bằng chứng, và chúng trả lời trực tiếp
yêu cầu 3 và 4 của đề bài.

**Phép thử cố tình sai làm đỏ CI.** `CiGateTest.coTinhSaiDeKiemTraCongCI` được
viết để luôn thất bại:

```
[ERROR] CiGateTest.coTinhSaiDeKiemTraCongCI:10
        Test nay co tinh sai - dung de chung minh CI biet chan
        ==> expected: <1> but was: <2>
[ERROR] Tests run: 94, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
Error: Process completed with exit code 1.
```

Job đỏ sau 18 giây. Một cổng chỉ biết báo xanh thì không chứng minh được gì;
phải thấy nó báo đỏ đúng lúc cần đỏ.

**Nhánh chính thật sự được bảo vệ.** Thử đẩy thẳng một commit rỗng vào `main`:

```
remote: error: GH006: Protected branch update failed for refs/heads/main.
remote: - Changes must be made through a pull request.
remote: - Required status check "backend-test" is expected.
 ! [remote rejected] main -> main (protected branch hook declined)
```

Luật chặn hoạt động ở phía máy chủ GitHub, không phải một quy ước tự giác.

### Giả lập kiến trúc

| Chế độ | Thời gian build Maven |
|---|---|
| Bản địa arm64 | 5,4 s |
| Giả lập amd64 qua QEMU | 22,3 s |
| Hệ số | **4,1 lần** |

Con số này chỉ đúng ở lần đo thứ tư. Ba lần trước lần lượt đo nhầm: thời gian
kéo base image, thời gian của một builder có cache rỗng, và một lần trúng cache
hoàn toàn vì nội dung file sửa ra giống hệt lần trước nên BuildKit coi là không
đổi. Cả ba lần đều chạy trót lọt và đều cho ra một con số hợp lệ cho một câu
hỏi không ai đặt ra.
