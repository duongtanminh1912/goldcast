# Số liệu cho chương 3 — So sánh Jenkins với GitHub Actions

Các bảng thời lượng tuần 2–11 là số đo lịch sử của pipeline tại thời điểm đó;
nguồn ghi kèm theo từng bảng. Giá trị suy ra, tỉ lệ tính toán và ước lượng phải
được phân biệt với số đo trực tiếp.

Từ ngày 17/10/2026, mã pipeline đã chuyển sang G1 → build → G2 riêng → Deploy →
G3. Những thời lượng cũ không mô tả chi phí của luồng mới. Phần bổ sung cuối
file tách kết quả kiểm chứng local đã có khỏi số đo Jenkins/VPS còn thiếu.
Rà soát tài liệu ngày 18/10/2026.

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

## Bảng 1 — Thời lượng build Jenkins, 13 build được liệt kê

Nguồn: trang Stages của job `goldcast-ci`, pipeline cũ. Bảng liệt kê 13 số
build phân biệt; chưa có bản ghi thứ 14 trong tài liệu để đưa vào thống kê.

| Bậc | Nguyên nhân | Build | Thời lượng |
|---|---|---|---|
| 1 | Không file nào trong `backend/` hay `frontend/` đổi | #3, #10, #13, #15 | 5–6 s |
| 1 | | #16, #12 | 7–8 s |
| 2 | Mã backend đổi | #14, #17 | 16–19 s |
| 3 | Cả hai phía đổi | #11, #9 | 38–49 s |
| 4 | Base image mới hoặc đổi builder | #7 | 76 s |
| 4 | | #5 | 155 s |
| 4 | | #6 | 245 s |

Biến thiên 5 giây tới 245 giây — gần **50 lần** trên pipeline cũ, với ba stage
khai báo ngoài bước checkout. Không suy ra thời lượng của G1/G2/G3 mới từ bảng này.

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

**Phần chênh ngoài tổng stage ở hai build này xấp xỉ 3,7 giây.** Đây là quan
sát từ #16/#17 với số hiển thị đã làm tròn, chưa đủ để coi là chi phí cố định
của Jenkins hoặc sàn cho mọi pipeline. Cần đo lại khi số stage/công việc đổi.

**Phần lớn chênh lệch nằm ở build backend.** Stage này từ 0,53 lên 10 giây,
tăng 9,47 giây, chiếm khoảng 85% mức tăng 11,20 giây của tổng stage. `Test
backend` cũng tăng từ 2 lên 4 giây, nên không thể quy toàn bộ chênh lệch cho
một stage.

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

Bảng chứa cả thời lượng job và step. Chỉ đối chiếu step `Chay test backend`
với stage `Test backend` khi so lệnh Maven; không lấy 40 giây của cả job chia
cho 2 giây của một stage để kết luận công cụ nhanh hơn 20 lần.

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

Đó là rủi ro của bước test dùng workspace gia tăng so với test từ mã nguồn
sạch. Jenkins vẫn có thể chạy `clean` hoặc dùng workspace mới; build Docker
trong dự án cũng tự biên dịch. Vì vậy 40 giây của job không thể coi hoàn toàn
là chi phí trả cho bảo đảm build sạch: còn có runner, phụ thuộc và chi phí nền tảng.

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
3. **Tối ưu các stage của pipeline cũ.** Phần chênh khoảng 3,7 giây ở #16/#17
   chiếm gần nửa build ngắn. Đây là gợi ý ưu tiên tối ưu từ hai lần đo, không
   phải sàn thời lượng của pipeline G1/G2/G3.

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

Hai số này cùng phạm vi thời gian Maven, nhưng một bên có biên dịch lại còn
bên kia không. Có thể dùng để giải thích ảnh hưởng của workspace; muốn so tốc
độ công cụ phải đồng nhất thêm mã nguồn, phụ thuộc, cache và thao tác biên dịch.

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
| Phần chênh ngoài tổng stage, suy từ #17 | ~3,7 s |
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

## Bổ sung phần 2: số đo và bằng chứng tuần 4–7

### Jenkins chạy lặp trên cùng một mã nguồn (tuần 4)

Job `goldcast-ci`, cùng 93 phép thử, cùng commit:

| Build | Giờ kết thúc (UTC) | Thời gian Maven |
|---|---|---|
| #2 | 27/09 10:32:46 | **36,9 s** |
| #3 | 27/09 10:34:26 | **1,693 s** |
| #4 | 27/09 15:05:26 | **1,702 s** |

Build #3 chạy chỉ 100 giây sau #2; build #4 chạy sau đó bốn tiếng rưỡi. Cả hai
đều 1,70 giây, dao động **±0,3%**.

### So sánh Jenkins với GitHub Actions

Ghép với số đo tuần 2–3:

| | Lần chạy đầu | Các lần sau |
|---|---|---|
| Jenkins — Maven | 36,9 s | 1,70 s (±0,3%) |
| GitHub Actions — Maven | — | 26,1 s |
| GitHub Actions — cả job | 34 s | 19–23 s (±10%) |

**Kết luận không phải "Jenkins nhanh hơn 15 lần".** Khác biệt nằm ở trạng thái
của môi trường thực thi, không ở tốc độ công cụ:

- GitHub Actions cấp một runner mới cho mỗi lần chạy. Chỉ cache phụ thuộc
  (`setup-java`, 59 MB) sống sót; `target/` thì không. Nên Maven luôn phải biên
  dịch lại.
- Jenkins giữ nguyên workspace giữa các lần chạy. Log ghi
  `Nothing to compile - all classes are up to date`, nên nó chỉ chạy lại phép
  thử.

Mỗi bên có cái giá riêng. Jenkins nhanh hơn rất nhiều từ lần thứ hai, nhưng mất
đảm bảo build sạch: một file rác còn sót từ lần trước vẫn có thể làm build xanh
giả, và lỗi kiểu đó chỉ lộ ra khi có người xoá workspace. GitHub Actions chậm
hơn và tốn 7 giây chi phí nền tảng mỗi lần, đổi lại mỗi lần chạy là môi trường
trắng, nên kết quả xanh của nó đáng tin hơn.

Độ nhiễu cũng chênh lệch rõ: Jenkins ±0,3%, GitHub Actions ±10% ngay cả khi
cache đã nóng. Với GitHub Actions, một khác biệt dưới 10% giữa hai lần đo không
nói lên điều gì.

### Gắn tag theo số build (tuần 5)

Stage build trong Jenkins giai đoạn này còn dùng `docker build` thường, chưa
dùng `buildx`:

```
docker build -t goldcast-backend:7 -t goldcast-backend:latest backend/
```

Kiểm bằng `docker images`:

```
goldcast-backend:5        af52e1fb8d97
goldcast-backend:6        72d5e2abf67a
goldcast-backend:7        8613ed92c89c
goldcast-backend:latest   8613ed92c89c
```

Tag `7` và `latest` **cùng một image ID**: hai cái tên trỏ vào một image, không
tốn thêm dung lượng. Nhưng tag 5 và 6 vẫn nằm lại — image cũ tích luỹ theo từng
build. Tuy nhiên `docker image prune -f` chỉ dọn image dangling, không tự xóa
các image vẫn có tag trong ví dụ này. Cần chính sách giữ/dọn riêng; pipeline
mới chưa có chính sách đó và phải giữ digest của bản tốt để rollback.

### Cổng G1 bắt lệch pha giữa hai ngôn ngữ (tuần 6)

`ApiContractTest.moiDtoCapCaoDeuCoInterfaceTypeScript` kiểm mọi DTO cấp cao của
backend đều có interface TypeScript tương ứng ở frontend.

Khi đỏ, nó in luôn danh sách interface đang có để biết thiếu cái nào:

```
[ERROR] ApiContractTest.moiDtoCapCaoDeuCoInterfaceTypeScript:35
        DTO khong co interface TypeScript tuong ung.
        Interface dang co: [Accuracy, ApiProblem, Backtest, BacktestModelResult,
        Conversion, DomesticQuote, ..., Seriesx, WorldQuote]
        ==> expected: <true> but was: <false>
[ERROR] Tests run: 1, Failures: 1
```

| | Giờ | Kết quả | Maven |
|---|---|---|---|
| Đỏ | 03/10 22:39:34 | 1 phép thử, 1 hỏng | 1,375 s |
| Xanh | 03/10 22:39:48 | 1 phép thử, 0 hỏng | 1,320 s |

**14 giây** từ đỏ sang xanh. Đó là vòng phản hồi thật khi chạy ở máy cá nhân, và
là lý do G1 phải chạy được ngoài CI chứ không chỉ trong CI.

Đây cũng là câu trả lời cho "vì sao tự viết thay vì dùng công cụ có sẵn": không
công cụ phân tích tĩnh thông dụng nào bắt được sự lệch pha giữa kiểu dữ liệu
Java và kiểu dữ liệu TypeScript trong cùng một dự án, vì với chúng đó là hai
ngôn ngữ không liên quan.

### Tiêm lỗi để kiểm chính cổng G2 (tuần 7, script 18 phép thử)

Đây là bằng chứng của script ở tuần 7, không phải lần chạy G2 trước deploy
của pipeline mới. Script hiện tại có thêm kiểm dự báo và đạt 35 phép kiểm ở
ca thành công đã chạy local ngày 17/10; giữ nguyên output 18 phép thử dưới đây.

Một cổng chỉ biết báo xanh thì chưa chứng minh được gì. Phép kiểm dưới đây cố ý
làm hỏng hệ thống theo một cách đã biết trước, rồi xem cổng có bắt đúng chỗ đó
và **chỉ** chỗ đó không.

Sửa một hằng số vật lý trong mã nguồn — 1 lượng vàng từ 37,5 gram thành 37,0:

```
sed -i '' 's/TAEL_IN_GRAMS = 37.5/TAEL_IN_GRAMS = 37.0/' \
    backend/src/main/java/vn/goldcast/market/GoldUnits.java
docker compose up -d --build backend
node scripts/smoke.mjs http://localhost:8090
```

Thời gian dựng lại riêng backend: build **10,0 s** (trong đó
`mvn clean package -DskipTests` 7,1 s), container `healthy` sau **7 giây**.

Kết quả G2:

```
18 phep thu, 1 hong.
Danh sach hong:
  - hang so: taelInGrams = 37,5  (kien thuc ngoai)
ma thoat: 1
```

**Chỗ đáng chú ý là 17 phép thử không đỏ.** Ba phép quy đổi vẫn báo OK:

```
OK   quy doi: tael = gram x taelInGrams
OK   quy doi: gram = usdPerOunce x vndPerUsd / troyOunceInGrams
OK   quy doi: chi = tael / 10  (kien thuc ngoai)
```

Vì hệ thống dùng 37,0 ở mọi nơi nên nó vẫn **tự nhất quán hoàn hảo**. Một bất
biến tự chứa về nguyên tắc không thể bắt được một hằng số sai; nó chỉ hỏi "hệ
thống có mâu thuẫn với chính nó không".

Phép thử duy nhất bắt được là phép duy nhất mã hoá một sự thật từ bên ngoài hệ
thống. Đổi lại, khi nó đỏ thì nguyên nhân có thể là code sai mà cũng có thể là
người viết phép thử sai — nên nhãn `(kien thuc ngoai)` được in thẳng trong log
để người đọc biết điều đó trước khi đi sửa code.

Đây là bằng chứng thực nghiệm cho việc chia 18 phép thử thành hai nhóm, thay vì
chỉ là một lập luận thiết kế.

Hoàn tác: `git restore` file đó rồi dựng lại, mất **8,1 s**.

### Bốn quyết định thiết kế trong `smoke.mjs`

1. **Mã thoát là tín hiệu.** `process.exit(1)` khi có phép thử hỏng — cùng cơ
   chế `mvn test` dùng để báo cho GitHub Actions. Shell thấy mã khác 0 là coi
   như hỏng.
2. **Mỗi nhóm chạy độc lập.** Mỗi endpoint được bọc trong `try` riêng, nên
   `/instruments` chết thì `/market/summary` vẫn chạy. Một smoke test dừng ở lỗi
   đầu tiên chỉ cho thấy một mẩu; cần thấy toàn cảnh rồi mới sửa.
3. **Hai loại bất biến tách bạch ngay trong mã.** Nhãn `(kien thuc ngoai)` là
   một phần của output, không phải chú thích trong code.
4. **Không khẳng định điều chưa biết.** Phép thử cho `/models` ban đầu chỉ
   khẳng định "là mảng không rỗng" và in ra phần tử đầu tiên để quan sát. Sau
   khi thấy output thật (`["NAIVE","DRIFT","SMA","HOLT_DAMPED","AR_DIFF","AUTO"]`)
   mới bổ sung bốn khẳng định nữa: dạng enum chữ hoa gạch dưới, có ít nhất 5 mô
   hình, có `NAIVE` làm mốc so sánh, mọi mô hình có nhãn.

Điểm thứ tư là một kết quả về phương pháp: cách làm "in ra trước, khẳng định
sau" tránh được đúng hai lỗi đã mắc trước đó — đoán quy ước đặt tên từ một DTO,
và đoán rằng một enum sẽ xuất hiện trong đồ thị.

### Ghi chú: cổng trong môi trường phát triển khác môi trường production

`docker compose ps` trên máy cá nhân:

```
goldcast-backend    0.0.0.0:8090->8080/tcp
goldcast-db         0.0.0.0:5433->5432/tcp
goldcast-frontend   0.0.0.0:3000->3000/tcp
```

Cả ba đều publish ra host. Trên máy chủ production thì ngược lại: chỉ Caddy
publish 80 và 443, ba service còn lại không khai `ports` nào. Hai file compose
được giữ độc lập để cấu hình production không kế thừa các cổng phát triển.
Đây là lựa chọn cấu trúc của dự án, không phải kết luận rằng mọi phiên bản
Compose đều không thể xóa cấu hình khi ghép file.


## Bổ sung ngày 17/10/2026 — Pipeline G1/G2/G3

Phạm vi mã: commit `7494a4c` thêm G2 trước deploy cùng release/rollback/recovery;
commit `8013a3d` lấy digest từ metadata Buildx. Phần này được rà soát ngày
18/10/2026. Nguồn mô tả: `Jenkinsfile`, `scripts/g2.sh`, `scripts/release.sh`,
`docker-compose.ci.yml` và bộ test trong `scripts/tests/`.

### Cái gì đã thay đổi trong đối tượng đo

| Hạng mục | Luồng tuần 10–11 | Luồng mới trong mã nguồn |
|---|---|---|
| G1 | Maven, gồm hợp đồng Java–TypeScript | Maven + TypeScript typecheck + test release trong Python container |
| Build | Tag số build và `latest` | Build/push một lần; hai digest lưu trong `release.env` |
| G2 | 18 phép kiểm API sau deploy trên VPS | Môi trường riêng trước deploy: cài mới và nâng cấp DB có dữ liệu; API/dự báo/SSR |
| Deploy | Sửa `THE_TAG` rồi chạy Compose trên VPS | Chỉ `main`; dùng cùng digest đã qua G2 và cấu hình theo release |
| Xác minh production | Trang công khai và smoke sau deploy được gọi là G2 | G3: readiness, image ID, HTTPS và các trang chính |
| Bản tốt/rollback | Cơ chế tag theo pipeline thời điểm đó | Ghi `.last-good-release` sau G3; rollback theo bản trước đã lưu cố định |
| Deploy gián đoạn | Chưa có bằng chứng recovery trong bộ đo cũ | Metadata, khóa, lệnh `status`/`recover`; cần xác nhận deploy đã dừng |

Các stage cũ `Test backend`, `Build backend image` không tương đương stage G1
và build mới. G2 mới chạy hai kịch bản DB và thêm probe; vì vậy không ghép
thời lượng cũ với kết quả mới thành một bảng so sánh tổng pipeline.

### Kết quả kiểm chứng đã ghi nhận, chưa phải benchmark

Nguồn: output terminal của phiên làm việc ngày 17/10/2026 được ghi nhận trong
[nhật ký](nhat-ky.md#cải-tiến-ngày-17102026--g2-trước-deploy-g3-xác-minh-và-rollback).
Đây là các ca local ngày 17/10. Số đo Jenkins ngày 18/10 được ghi riêng ở
phần build #55 bên dưới; chưa có bằng chứng G3/rollback trên VPS.

| Hạng mục | Kết quả | Giới hạn bằng chứng |
|---|---|---|
| Maven backend | 102 test đạt | Máy cá nhân, không phải thời lượng G1 Jenkins |
| Frontend | Typecheck và build image đạt | Mã/phụ thuộc tại lần kiểm đó |
| Release/rollback/recovery/bootstrap | 19 test đạt, gồm Python 3.13 container | Lệnh Docker/VPS được giả lập; không chứng minh rollback production |
| G2 cài mới | Migration đạt; 35 phép kiểm API + 3 trang SSR đạt | Compose cục bộ, DB riêng |
| G2 nâng cấp | Baseline V1 có dữ liệu bốn bảng → migration mới nhất; dữ liệu giữ nguyên; 35 phép kiểm API + 3 trang SSR đạt | Fixture tổng hợp, cùng image ứng viên |

Không cộng các số test khác tầng thành một tổng để so sánh với 18 phép kiểm
G2 cũ. Hai kịch bản G2 chạy lại cùng bộ kiểm, không phải 70 loại bất biến khác
nhau. Test Python kiểm chuyển trạng thái và các lỗi deploy, G3, cleanup,
recovery, bootstrap; không đo thời gian phục hồi production.

Kết quả local gắn với image/mã ở lúc chạy. Build #55 bên dưới xác nhận thêm
G1, build image và G2 cho commit `81185938bbd3`, sau thay đổi digest metadata
và phụ thuộc frontend; chưa xác nhận phần Production. Không coi một lần kiểm
chứng là chứng nhận cho mọi commit tiếp theo.

### Số đo Jenkins ngày 18/10/2026 — Build #55, Production skipped

Nguồn: [build #55 của job goldcast-ci](http://localhost:8081/job/goldcast-ci/55/).
Bản ghi gốc lưu trong repo:
[build.json](measurements/pipeline-new/build-55/build.json),
[graph.json](measurements/pipeline-new/build-55/graph.json),
[console.log](measurements/pipeline-new/build-55/console.log) và
[durations.csv](measurements/pipeline-new/build-55/durations.csv).
`graph.json` lấy từ bản ghi Pipeline Graph View; endpoint `/wfapi/describe`
trả 404 vì Jenkins chưa cài Pipeline: REST API, nên không có `stages.json`.

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

### Dữ liệu còn thiếu để đánh giá cải tiến

| Câu hỏi | Bằng chứng cần thu | Trạng thái trong tài liệu |
|---|---|---|
| Luồng mới chạy được trên Jenkins? | Commit, URL/số build, log stage, artifact `release.env` | #55 xác nhận G1/build/G2; Production skipped; chưa lưu `release.env` trong bộ đo này |
| G2 chặn được trước khi chạm production? | Build G2 cố tình lỗi, Deploy bị bỏ qua, manifest production trước/sau giữ nguyên | Chưa có bằng chứng Jenkins/VPS |
| Production chạy đúng image qua G2? | Digest artifact, image ID đang chạy và kết quả G3 | Chưa ghi nhận trên VPS |
| G3 lỗi có quay về bản cũ? | Mục tiêu `previous-good-release`, log G3 lỗi, log rollback + xác minh, con trỏ bản tốt sau cùng | Mới có test mô phỏng |
| Build bị ngắt có phục hồi được? | Metadata build, `status`, xác nhận đã dừng và output `recover` trên môi trường kiểm thử | Mới có test mô phỏng |
| Chi phí thêm của G2/G3 là bao nhiêu? | Thời lượng từng stage và toàn build, chạy lặp với cùng điều kiện cache | #55: G2 93,214 giây; toàn build 157,211 giây với Production skipped; chưa đo G3 hoặc chạy lặp |

Khi bổ sung số đo, ghi commit và phiên bản phụ thuộc, kiến trúc máy,
workspace sạch/bền, trạng thái cache Maven/BuildKit, trạng thái image phụ đã
kéo hay chưa, thời gian chờ runner, thời lượng stage và toàn build. Đo lặp ít
nhất ba lần trong mỗi điều kiện muốn so; tách chạy nguội khỏi chạy ấm.

Đối chiếu Jenkins và GitHub Actions bằng phần công việc tương đương. Nếu một
bên chỉ chạy Maven còn bên kia chạy cả build, G2, Deploy và G3, tổng thời gian
không phải phép đo tốc độ hai công cụ trên cùng tác vụ.

### Giới hạn vẫn phải ghi trong chương 3

- Baseline nâng cấp hiện là V1 với dữ liệu mẫu; chưa bao phủ mọi phiên bản DB
  trước hoặc phân bố dữ liệu production.
- Rollback chỉ quay lui ứng dụng/cấu hình, không hoàn tác migration. Migration
  phải tương thích với ứng dụng trước và sau.
- Probe trang kiểm SSR qua HTTP/HTTPS, chưa phải kiểm tương tác bằng trình duyệt.
- Khóa không tự hết hạn; recovery cần xác nhận build/deploy đã dừng. Khóa thiếu
  owner ở một số trạng thái phải điều tra thủ công.
- Chưa có số đo lưu trữ hoặc chính sách dọn release/image cũ cho luồng mới.
