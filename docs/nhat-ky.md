
# Nhật ký đồ án DevOps — goldcast

Các mục theo tuần ghi lại trạng thái tại thời điểm thực hiện. Những mô tả như
G2 chạy sau deploy, `THE_TAG`, số stage và thời lượng build ở tuần 10–11 thuộc
pipeline cũ. Các cải tiến ngày 17–18/10/2026 và lần chạy đầy đủ #58 trên main
được ghi riêng ở cuối file; hướng dẫn vận hành hiện hành nằm trong
[tutorial](tutorial.md#b10-g1--build--g2--deploy--g3).

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

Cùng một commit (`20e414b`), chạy lại 4 lần bằng "Re-run all jobs".

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

## Tuần 5 — Pipeline sinh ra artifact

**Mục tiêu:** Pipeline không chỉ kiểm tra mã nguồn mà còn tạo ra Docker image —
thứ đem đi triển khai được. Đây là ranh giới giữa CI và CD.

### Đã làm

- Thêm lệnh `docker` vào image Jenkins bằng multi-stage build từ `docker:27-cli`.
- Gắn `/var/run/docker.sock` để Jenkins điều khiển được Docker daemon của máy chủ.
- Thêm stage `Build backend image` và `Build frontend image`, đặt sau stage kiểm
  thử: không đóng gói thứ chưa qua kiểm thử.
- Mỗi image mang hai tag: số build (`${BUILD_NUMBER}`) và `latest`.
- Phát hiện Jenkins dùng builder cũ, bổ sung plugin buildx để chuyển sang BuildKit.

### Số đo

| Build | Pipeline | Builder | Trạng thái cache | Thời gian |
| --- | --- | --- | --- | --- |
| #5 | test + backend | legacy | - | 2 ph 35 s |
| #6 | test + backend + frontend | legacy | tải mới image nền frontend | 4 ph 05 s |
| #7 | test + backend + frontend | BuildKit | cache BuildKit còn trống | 1 ph 16 s |
| #8 | test + backend + frontend | BuildKit | ấm hoàn toàn | 6,5 s |

Thời gian ở trạng thái ổn định, khi mã nguồn không đổi: **6,5 giây** cho toàn bộ
pipeline gồm 93 unit test và hai lần dựng image.

**Không kết luận được "BuildKit nhanh hơn legacy bao nhiêu lần"** từ bảng này.
Giữa #6 và #7 có hai biến cùng thay đổi: builder, và trạng thái cache. Cache của
legacy builder không dùng chung với BuildKit, nên #7 phải dựng lại từ đầu. Muốn
so sạch thì cần đo legacy ở trạng thái ấm, mà dữ liệu này không có.


### Điều tra tính tái lập của build

Quan sát ban đầu: cùng một commit nhưng mỗi lần build lại cho ra mã image khác
nhau trong `docker images`. Nếu đúng, điều này sẽ phá vỡ khả năng kiểm chứng
"bản đang chạy chính là bản đã test".

Lần lượt loại trừ từng nguyên nhân:

| Cặp so sánh | Khác biệt thật | Nguyên nhân |
| --- | --- | --- |
| Tuần 2 với #5 | có | Máy cá nhân dùng BuildKit, Jenkins dùng legacy builder |
| #5 với #6 | có | Nhãn `node:22-alpine` đã trỏ sang bản mới (log ghi `Downloaded newer image`) |
| #7 với #8 | **không** | Nội dung image giống hệt |

Với cặp #7/#8 — cùng commit, cùng builder, cùng mã băm image nền — đã kiểm bằng
`docker image inspect`:

- Danh sách mã băm các lớp: giống hệt
- Trường `Created`: giống tới nano giây
- Các trường `Config`, `History`, `RootFS`: không có khác biệt
- Chỉ khác ở `LastTagTime` và `Metadata.Ref` — là sổ sách của phiên build, không
  phải nội dung image

Kết luận: **nội dung image tái lập được**, với điều kiện cố định công cụ build và
image nền. Mã `Id` hiển thị trong `docker images` được tính từ lớp bọc mang thông
tin phiên build, nên hai mã khác nhau không có nghĩa là hai image khác nhau.

Hệ quả cho quy trình: artifact vẫn phải được giữ và luân chuyển thay vì build lại
ở từng môi trường — không phải vì Docker bất định, mà vì hai phụ thuộc nằm ngoài
mã nguồn có thể trôi mà người viết code không hay biết: nhãn image nền trỏ sang
bản mới, và môi trường CI có thể dùng builder khác máy cá nhân. Giải pháp triệt

để là ghim mã băm image nền trong Dockerfile, đổi lại là không còn nhận bản vá
bảo mật tự động cho image nền.

### Hạn chế đã nhận diện, chưa xử lý

1. **Image tích tụ.** Mỗi build sinh một image mang số riêng và không tự xoá. Cách
   xử lý: `docker image prune` theo lịch, hoặc registry có chính sách lưu giữ.

2. **Địa chỉ API bị cố định vào image frontend.** `docker build` không truyền
   `NEXT_PUBLIC_API_BASE_URL`, nên image mang giá trị mặc định. Một biến thuộc về
   môi trường lại nằm cứng trong artifact. Sẽ xử lý khi triển khai lên máy chủ.

3. **Pipeline không tự dựng lại được chính nó.** `jenkins/Dockerfile` nằm trong
   repo và đi qua pull request, nhưng việc dựng image và thay container phải làm
   thủ công — Jenkins không thể thay thế chính mình trong lúc đang chạy.

4. **Quyền của Jenkins trên máy chủ.** Gắn `docker.sock` cho phép Jenkins điều
   khiển Docker daemon, tức có quyền tương đương root trên máy. Dùng
   `--group-add 0` thay vì `--user root` theo nguyên tắc đặc quyền tối thiểu,
   nhưng về bản chất mức quyền không khác nhau đáng kể. Chấp nhận được vì Jenkins
   chạy cục bộ trên máy cá nhân; không chấp nhận được trên máy chủ dùng chung.

### Bài học phương pháp

Trong tuần này và các tuần trước, đã bốn lần rút ra kết luận sai vì so sánh nhầm
đại lượng:

1. Tuần 3: lấy "Total duration" của GitHub Actions, trong đó 41 trên 75 giây là
   thời gian chờ cấp máy ảo, không liên quan đến cache.
2. Tuần 4: lấy "Total time" của Maven làm thời gian build Jenkins.
3. Tuần 5: so #6 với #7 để đánh giá builder, trong khi trạng thái cache cũng khác.
4. Tuần 5: so cột `IMAGE ID` để kết luận hai image khác nhau, trong khi cột đó

   không phải mã băm của nội dung image.

Cả bốn lần đều phát hiện được bằng cùng một cách: khi con số không khớp với dự
đoán, mở ra xem đại lượng đó thật sự đo cái gì, thay vì giữ nguyên kết luận.

## Tuần 6 — Cổng hợp đồng API (G1)

### Lỗ hổng không ai gác

Backend trả JSON, frontend khai báo interface TypeScript mô tả JSON đó. Nhưng kiểu
TypeScript bị xoá lúc biên dịch — không có gì đối chiếu chúng với JSON thật lúc chạy.

Nên nếu backend đổi tên một trường, `mvn test` vẫn xanh, `tsc` vẫn xanh, và người dùng
thấy ô giá trống. Hai bên đều tự tin là mình đúng; chỗ hai bên gặp nhau thì không ai gác.

Cổng G1 sinh ra cho đúng chỗ đó.

### Vị trí của cổng, và vì sao

G1 nằm **bên trong `mvn test`**, không phải một stage Jenkins. Lý do là hệ quả trực tiếp
của kiến trúc đã dựng ở tuần 3–4: Jenkins chỉ poll nhánh `main` nên không bao giờ nhìn
thấy pull request. Chỉ GitHub Actions mới chặn được merge, mà GitHub Actions chạy
`mvn test`. Đặt sai chỗ thì cổng chỉ báo động sau khi hỏng đã vào `main`.

Hệ quả phụ: từ nay một PR chỉ sửa frontend vẫn phải chạy test backend và vẫn có thể bị
backend chặn. Đó là sợi dây đầu tiên nối hai nửa của dự án.

### Chứng minh cổng biết chặn

Xanh ngay lần đầu không chứng minh được gì. Đổi `export interface Series` thành `SeriesX`
trong `frontend/src/lib/types.ts`, chạy lại `mvn test`: một test **Java ở backend** đỏ vì
một file **TypeScript ở frontend**.

Log còn in `Nothing to compile — all classes are up to date`, nghĩa là giữa lần đỏ và lần

xanh không một dòng Java nào được biên dịch lại. Cổng đọc `types.ts` lúc **chạy test**,
không phải lúc biên dịch.

### Ba lần thiết kế bị dữ liệu thật sửa

Thiết kế ban đầu chỉ có hai nhánh: record thì đệ quy, kiểu lá thì dừng. Dữ liệu thật sửa
nó ba lần:

1. **Enum.** Khi gỡ mô hình ML, phát hiện `ForecastModelId` phía TypeScript là union type
   chứ không phải interface — một mặt của hợp đồng mà thiết kế không hề phủ.
2. **Kiểu lạ.** Thêm nhánh "gặp kiểu chưa lường thì cổng đỏ". Lần này lỗ hổng được phát
   hiện nhờ may mắn; nhánh này làm lần sau cổng tự báo.
3. **Quy ước tên.** Quy ước "record lồng nhau dùng tên đơn" được rút ra từ `MarketSummaryDto`
   — file DTO duy nhất được đọc kỹ lúc thiết kế. Khi cổng chạy thật trên cả bảy DTO, ba
   trên bốn record lồng nhau không theo quy ước đó. Bảng ngoại lệ từ hai dòng thành bốn.

Bài học chung: một quy tắc rút ra từ một mẫu trên bảy không phải là quy tắc, dù nó đẹp.

### Những thứ cổng tìm ra mà không ai đi tìm

**Tầng DTO đã xoá sạch enum.** `InstrumentDto.kind` là `String`, không phải
`InstrumentKind`; hàm `from()` gọi `.name()` ngay tại biên. Nên hợp đồng enum ↔ union tuy
tồn tại thật trong JSON nhưng **vô hình với phép duyệt kiểu**, phải khai bằng tay. Đó là
cái giá của mẫu DTO: được tách biệt, mất thông tin kiểu ở biên.

**Model Java có chỗ trùng lặp.** `ForecastDto.HorizonAccuracy` và `BacktestDto.HorizonRow`
giống hệt nhau từng trường, chỉ khác tên. TypeScript đã nhận ra và dùng chung một interface.
Phía Java mới là phía dư thừa. Việc gộp để sau, nhưng cổng tìm ra nó mà không ai đi tìm.

### Chống xanh rỗng

Nếu phép quét thư mục DTO trả về rỗng, cả ba test đều xanh — không có gì để kiểm thì không

có gì sai. Đó là kiểu hỏng tệ nhất của một cổng: nó không chết, nó chỉ ngừng làm việc và
vẫn gật đầu.

Thêm test thứ tư chốt số lượng: ít nhất 7 DTO, 19 interface, 2 union. Khi có thay đổi hợp
lệ làm giảm những con số đó, cổng sẽ đỏ và buộc một con người sửa con số bằng tay — tức là
xác nhận việc giảm là có chủ ý. Cùng triết lý với bảng ngoại lệ: chỗ nào máy không quyết
được thì bắt người quyết, và lưu quyết định đó trong mã nguồn.

### Sáu giới hạn, ghi ngay trong file

Cổng là phép kiểm dựa trên **tên**, nên luôn có hai loại sai: báo nhầm và bỏ sót. Không khử
được, chỉ ghi rõ. Sáu giới hạn nằm trong javadoc đầu `ApiContractTest.java`, không nằm rải
rác trong tài liệu riêng — người sửa file sau sẽ đọc được mà không cần hỏi ai.

Ví dụ sống của loại "báo nhầm" gặp ngay trong tuần: lệnh `grep "ridge"` để tìm dấu vết mô
hình Ridge regression lại khớp bốn dòng trong `LinearAlgebra.java`, nơi `ridge` là thủ thuật
cộng λ vào đường chéo ma trận cho khỏi suy biến — hoàn toàn không liên quan.

### Hai bài học về quy trình

**Scope của Maven.** Đặt nhầm test vào `src/main/java` làm build chết vì JUnit khai báo
`<scope>test</scope>`. Nếu Maven cho qua thì JUnit bị đóng gói vào image backend, và tệ hơn,
Surefire sẽ không bao giờ chạy test đó — xanh mà chẳng kiểm gì.

**`git status` bắt được thứ CI không bắt được.** Lệnh `mv` chuyển file trên đĩa nhưng không
động tới index, mà index sống qua cả `git switch`. Suýt commit một file ở `src/main` có
trong Git nhưng không có trên đĩa. Đây là lần thứ hai việc đọc kỹ cái gì đang được đưa vào
cứu được một lỗi — lần đầu là tuần 4, khi `README.md` bị xoá 265 dòng mà CI vẫn xanh.
CI kiểm nội dung code; con người kiểm cái gì đang được đưa vào. Hai câu hỏi khác nhau.

### Gỡ mô hình học máy


Mô hình Ridge và Gradient Boosting đã merge vào `main` ở tuần trước được gỡ bằng
`git revert`, qua PR #10, không viết lại lịch sử. Lý do gỡ là phạm vi: đồ án nghiên cứu quy
trình DevOps, không đánh giá chất lượng dự báo.

Điểm đáng ghi: việc **gỡ** một tính năng đi qua đúng cổng CI như việc **thêm**, không cần
quy trình riêng. Loại thay đổi không quan trọng; đường đi thì giống nhau. Và chính việc gỡ
này phơi ra lỗ hổng enum trong thiết kế cổng.

### Số liệu

| Chỉ số | Giá trị |
|---|---|
| Thời gian chạy cổng | 0,029 giây |
| Số test backend | 93 → 97 |
| Số DTO được đối chiếu | 7 cấp cao, cộng record lồng nhau |
| Interface TypeScript | 19 |
| Union type TypeScript | 2 |
| Dòng trong bảng ngoại lệ tên | 4 |
| Jenkins build | #14 |

Cổng chạy ở hai nơi: GitHub Actions chặn merge, Jenkins chặn việc đóng gói image.

## Tuần 8 — Lên internet thật

### Đã làm

Thuê VPS, dựng máy chủ bằng script, siết SSH, build image cho đúng kiến trúc,
đẩy lên registry, deploy, và xác minh từ bên ngoài. Cuối tuần web chạy thật tại
`http://103.175.248.236`.

Máy: InterData Platinum 1 — 1 vCPU, 2 GB RAM, 20 GB NVMe, Ubuntu 24.04 LTS,
213.400đ/tháng (194.000đ + VAT), trả theo tháng không ràng buộc kỳ hạn. Chọn nhà
cung cấp Việt Nam vì Azure từ chối tài khoản sinh viên và GitHub Student Pack bị
từ chối hai lần — mọi phương án nước ngoài đều đòi thẻ quốc tế.

### Ba lỗi script chỉ lộ ra khi chạy trên máy thật

Hai script `deploy/dung-may-chu.sh` và `deploy/siet-ssh.sh` được viết ở tuần 7,
đọc lại nhiều lần, và đều sai. Không lỗi nào đọc code mà thấy được.

**Một — kiểm sự tồn tại thay vì kiểm trạng thái.** Bước tạo swap dùng
`if [ ! -f /swapfile ]`. Image gốc của InterData có sẵn `/swapfile` rỗng 0 byte,
nên điều kiện sai, khối tạo file bị bỏ qua, và `mkswap` chết với
`swap area needs to be at least 40 KiB`. Sửa thành so sánh kích thước thật với
kích thước cần:

    CAN_BYTE=$(numfmt --from=iec "$SWAP")
    CO_BYTE=$(stat -c %s /swapfile 2>/dev/null || echo 0)
    if [ "$CO_BYTE" -ne "$CAN_BYTE" ]; then rm -f /swapfile; ...

Dấu thời gian là thứ chỉ ra thủ phạm: file ghi `Oct 8 21:42`, đúng lúc nhà cung
cấp khởi tạo máy, trước khi script chạy lần nào.

**Hai — thứ tự đọc file cấu hình.** `siet-ssh.sh` ghi `99-goldcast.conf` vào
`/etc/ssh/sshd_config.d/`. Nhưng sshd lấy **giá trị đầu tiên** cho mỗi từ khoá, và
image có sẵn `50-cloud-init.conf` đặt `PasswordAuthentication yes`. File `99-` đọc
sau nên thua. Đổi tiền tố thành `01-`. Mỉa mai là trong chính script đã có một
đoạn comment giải thích đúng cơ chế đó, dùng để biện minh cho việc đẩy dòng
`Include` lên dòng 1 — nhưng không áp dụng cho tên file của chính nó.

**Ba — in kết quả không phải là kiểm tra.** Cả ba lần đều cùng một gốc: script
chạy một lệnh, in kết quả ra cho người đọc, rồi đi tiếp bất kể kết quả là gì.
Lần đầu phát hiện qua swap, lần hai qua `System clock synchronized: no`, lần ba
qua cấu hình sshd không ăn mà script vẫn báo xong. Bản sửa thay khối in bằng
vòng lặp so khớp và `exit 1` khi sai:

    for CAN in "permitrootlogin no" "passwordauthentication no" ...; do
      sshd -T | grep -qx "$CAN" || LOI=1
    done

Script không tự kiểm thì người phải kiểm tay, và người sẽ quên.

### Kiến trúc: vấn đề không chỉ của tuần này

Máy cá nhân là Apple Silicon (`arm64`), VPS là `x86_64`. Image build mặc định
không chạy được trên máy chủ. Phải dùng `docker buildx build --platform linux/amd64`,
tức là biên dịch qua mô phỏng QEMU.

Điều đáng lo hơn: **Jenkins cũng chạy trên chính máy arm64 đó**, nên kế hoạch
tuần 10 "Jenkins đẩy image, VPS kéo về" dính cùng bức tường. Giữ Jenkins làm
pipeline chính thì phải chấp nhận build chéo mỗi lần merge.

Hai con số build lần đầu ở bảng dưới là số **nguội** — gồm cả thời gian tải base
image bản amd64 mà máy chưa từng có. Chúng không dùng để quyết tuần 10 được.
Con số cần đo ở tuần 10 là: sửa một file nguồn rồi build lại, vì đó mới đúng thứ
Jenkins làm mỗi lần merge.

### Xác minh từ bên ngoài, không từ bên trong

`docker compose ps` cho thấy db, backend, frontend không publish cổng nào. Nhưng
đó là góc nhìn từ bên trong máy chủ, và cái bẫy "Docker ghi iptables vào chain
DOCKER-USER, nằm trước rule của ufw" chính là loại lỗi mà góc nhìn bên trong
không thấy.

Phép kiểm thật là quét cổng từ một máy khác:

| Cổng | Kết quả | Mong đợi |
|---|---|---|
| 22 | mở | SSH |
| 80 | mở | Caddy |
| 443 | đóng | Caddyfile tuần 8 chỉ có khối `:80`, chưa ai nghe 443 |
| 3000 | đóng | frontend, không được phơi |
| 5432 | đóng | Postgres, không được phơi |
| 8080 | đóng | backend, không được phơi |

Ba dòng cuối là bằng chứng thật sự của tuần này.

Phép kiểm đầu tiên cho kết quả sai hoàn toàn — cả sáu cổng đều báo đóng, kể cả
cổng 22 đang mang chính phiên SSH đó. Nguyên nhân: cờ `-G` của `nc` không được
hỗ trợ, lệnh lỗi, và mọi nhánh rơi vào `||`. Một phép kiểm hỏng im lặng nguy hiểm
hơn không kiểm, vì nó tạo ra niềm tin sai.

### Số đo

Trạng thái máy trước và sau khi deploy:

| Chỉ số | Máy sạch | Sau deploy |
|---|---|---|
| RAM dùng | 482 MiB | 819 MiB |
| RAM còn dùng được | 1,5 GiB | 1,1 GiB |
| Swap | 0 B (chưa tạo) | 2,0 GiB, dùng 268 KiB |
| Đĩa | 2,5 GB / 20 GB (14%) | 6,2 GB / 20 GB (33%) |

Từng container, đo bằng `docker stats` trên máy chủ, đặt cạnh số đo cùng cách
trên máy cá nhân ở tuần 7:

| Container | Máy cá nhân (arm64) | VPS (amd64, 1 vCPU) |
|---|---|---|
| backend | 295,9 MiB | 310 MiB |
| frontend | 35,17 MiB | 118,4 MiB |
| db | 35,42 MiB | 51,73 MiB |
| caddy | — | 51,18 MiB |
| **Tổng** | **366,5 MiB** | **531,3 MiB** |

Frontend tăng gấp ba vì lần đo trên máy cá nhân nó đang nằm không, còn trên máy
chủ nó đã render thật ít nhất một lượt. Dự đoán trước khi đo là "khoảng 550 MiB
cho toàn máy" — thực tế 819 MiB, hụt gần 50%. Biên an toàn vẫn đủ, nhưng hẹp hơn
dự đoán.

Build chéo kiến trúc, lần đầu, cache nguội:

| Việc | Thời gian |
|---|---|
| Build + đẩy backend | 4 phút 05 |
| — trong đó kéo base image amd64 | 61,5 giây |
| Build + đẩy frontend | 2 phút 05 |
| — trong đó `npm ci` | 24,4 giây |
| — trong đó `npm run build` | 65,3 giây |

Dự đoán trước khi đo là frontend nặng hơn backend. Sai: backend chậm gấp đôi, và
phần lớn chênh lệch là tải base image `maven:3.9-eclipse-temurin-21` bản amd64,
không phải biên dịch.

Khác:

| Chỉ số | Giá trị |
|---|---|
| Backend từ `up` tới `healthy` | 32 giây |
| Đĩa còn trống | 13 GB |
| Số lần deploy trước khi hết đĩa | ~18, nếu không dọn image cũ |

### Vấn đề gặp phải

**`siet-ssh.sh` mất trắng.** Script viết ở tuần 7 trong một hội thoại, đẩy lên một
nhánh, nhưng **chưa bao giờ commit** — nhánh rỗng. Khi hội thoại đó kết thúc thì
script biến mất, phải viết lại từ đầu. Dấu hiệu nhận ra là `git log` cho thấy
`origin/chore/script-siet-ssh` trỏ cùng commit với `main`. Thứ không commit thì
không tồn tại.

**Đồng hồ không đồng bộ, nhưng không sao.** `timedatectl` báo
`System clock synchronized: no`. Truy ra: timesyncd phân giải `ntp.ubuntu.com`
thành địa chỉ IPv6, mà máy không có IPv6 — `Packet count: 0`. Ghim sang IPv4 cũng
không khá hơn, nhà cung cấp chặn UDP 123. Nhưng `date -u` trên máy chủ khớp với
máy cá nhân tới từng phút: hypervisor giữ đồng hồ cho máy khách. TLS tuần 9 cần
đồng hồ đúng, không cần timesyncd — nên bỏ qua. Bài học: hỏi "cái này có thật sự
hỏng không" trước khi đi sửa.

**Package GHCR mặc định private** kể cả khi repo công khai. Phải vào đổi visibility
cho từng package, nếu không máy chủ kéo image bị `unauthorized`.

**Dán lệnh nhầm cửa sổ** nhiều lần, giữa terminal máy cá nhân và phiên SSH. Một
lần suýt nghiêm trọng: `ssh-keygen` hỏi tên file, và dòng dán kế tiếp
(`ssh-copy-id root@...`) bị nhận làm tên file — sinh ra một **private key nằm
trong thư mục của repo công khai**. Xoá kịp trước khi `git add`. Cách tránh:
dùng `-f` để lệnh không còn câu hỏi nào mà dán nhầm vào.

### Bài học

**Kiểm tra idempotent phải hỏi "trạng thái đã đúng chưa", không hỏi "dấu vết có
tồn tại không".** Sự tồn tại của một file không nói gì về việc nó có đúng không.

**In kết quả ra màn hình không phải là kiểm tra.** Script phải tự so khớp và tự
dừng. Người đọc log sẽ bỏ sót, và trong CI thì không có ai đọc cả.

**Xác minh phải đến từ bên ngoài hệ thống đang xác minh.** `docker compose ps`
không chứng minh được cổng có đóng hay không; một lệnh `nc` từ máy khác thì có.

**Một phép kiểm hỏng tệ hơn không kiểm**, vì nó tạo ra niềm tin sai. Cả sáu cổng
báo đóng trong khi SSH đang chạy qua cổng 22 là dấu hiệu phải nghi ngờ công cụ,
không nghi ngờ kết quả.

## Tuần 9 — Tên miền và HTTPS

### Đã làm

Mua tên miền, trỏ DNS về máy chủ, đổi một dòng trong Caddyfile. Cuối tuần web
chạy tại `https://goldcast.click` với chứng chỉ Let's Encrypt, và `http://`
tự chuyển sang `https://`.

Phần kỹ thuật đúng như dự tính: ngắn. Thời gian thực tế tiêu vào việc mua tên
miền và vào một lỗi khiến cấu hình mới không được nạp.

### Tên miền: định mua miễn phí, kết cục trả tiền

VNNIC có chương trình "Gương mặt số" cấp miễn phí tên miền `.id.vn` hai năm cho
công dân Việt Nam 18–23 tuổi. Đủ điều kiện, eKYC bằng CCCD, đặt hàng
`goldcast.id.vn` thành công — hoá đơn 1.590.000đ giảm còn 0đ, trạng thái "Đã
thanh toán", mã đơn 10092616818.

Nhưng tên miền không hiện trong trang quản lý. Panel báo "Không tìm thấy dữ
liệu!". Kiểm bằng `dig +short NS goldcast.id.vn` cũng không ra gì.

Nguyên nhân phát hiện hôm sau: **đăng nhập nhầm tài khoản.** Lúc đặt hàng là
một tài khoản, lúc mở trang quản lý là tài khoản khác (`guonatd546` so với
`tminh1912`). Tên miền không mất, nó nằm ở tài khoản kia. Mất gần một tiếng đi
tìm một thứ không hỏng.

Quyết định: mua `goldcast.click` 19.000đ/năm để đi tiếp, không chờ. Suất
`.id.vn` miễn phí vẫn còn nguyên ở tài khoản cũ, lấy lại lúc nào cũng được.

Một thứ suýt mua mà bỏ: **DNSSEC 39.000đ**. Let's Encrypt không đòi DNSSEC, và
cấu hình sai thì tên miền ngừng phân giải hoàn toàn. Thêm một thứ có thể hỏng
mà không đổi lại được gì.

### DNS: cảnh báo của nhà cung cấp so với thực tế

Trang cấu hình DNS của Tenten ghi rõ: *"Thiết lập bản ghi DNS sẽ có hiệu lực
nhanh nhất trong vòng 3 giờ. Chậm nhất 24 giờ."*

Thực tế: **khoảng 10 phút**. Thêm hai bản ghi A (`@` và `www`) trỏ về
`103.175.248.236`, chờ mười phút là `dig` trả về đúng.

Ghi lại vì nó là một dạng số liệu: con số nhà cung cấp công bố là biên an toàn
của họ, không phải thời gian thật. Nhưng không được *dựa* vào thực tế nhanh —
Let's Encrypt giới hạn 5 lượt xin chứng chỉ thất bại mỗi giờ cho một tên miền,
nên nguyên tắc vẫn là `dig` xanh rồi mới đụng tới Caddy.

### Lỗi: sửa file bind mount không làm container nạp lại

Caddyfile đổi, commit, merge, `git pull` trên máy chủ, rồi
`docker compose up -d caddy`. Lệnh chạy xong, không báo lỗi. Nhưng log Caddy
không có một dòng nào về chứng chỉ.

Đọc dấu thời gian trong log mới thấy: dòng `serving initial configuration` cách
dòng cuối **gần 17 tiếng**. Đó vẫn là tiến trình khởi động từ hôm trước.

Lý do: **Caddyfile là bind mount, không phải một phần cấu hình container.**
Compose so sánh image và cấu hình service để quyết định có tạo lại container
hay không. File nằm trong volume đổi nội dung thì compose không thấy gì khác,
nên để nguyên. Caddy tiếp tục chạy với cấu hình cũ đã nạp sẵn trong bộ nhớ.

`docker compose restart caddy` giải quyết xong trong một lệnh.

Loại lỗi này khó thấy nhất: **lệnh thành công, không có thông báo nào, và không
làm gì cả.** Nếu không đọc dấu thời gian trong log mà chỉ nhìn "không có lỗi"
thì sẽ đi tìm nguyên nhân ở DNS, ở tường lửa, ở Let's Encrypt — tức là ở mọi
chỗ trừ chỗ hỏng.

### Chứng chỉ

Toàn bộ cấu hình TLS trong dự án này:

    goldcast.click, www.goldcast.click {
        encode gzip
        reverse_proxy frontend:3000
    }

Không có đường dẫn tới file `.crt`, không có `ssl_certificate`, không có cron
gia hạn, không có `certbot`. Caddy đọc tên miền ở dòng đầu, tự xin chứng chỉ
qua ACME, tự gia hạn, và tự dựng chuyển hướng HTTP sang HTTPS.

Đây là điểm đáng đặt cạnh Nginx trong chương so sánh công cụ: với Nginx, cùng
việc này cần cài certbot, viết block server cho HTTP-01 challenge, trỏ đường dẫn
chứng chỉ, và đặt cron gia hạn — bốn thứ phải cấu hình đúng và phải nhớ kiểm
lại sau 90 ngày.

### Số đo

| Chỉ số | Giá trị |
|---|---|
| DNS lan (Tenten công bố) | nhanh nhất 3 giờ, chậm nhất 24 giờ |
| DNS lan (đo thực tế) | ~10 phút |
| Từ lệnh `restart` tới hai chứng chỉ | **2,5 giây** |
| Số chứng chỉ | 2 (`goldcast.click`, `www.goldcast.click`) |
| Dòng cấu hình TLS viết tay | **0** |
| Chi phí tên miền | 19.000đ/năm |

Phản hồi HTTP sau khi xong:

| Yêu cầu | Kết quả |
|---|---|
| `https://goldcast.click` | `HTTP/2 200`, `via: 1.1 Caddy` |
| `http://goldcast.click` | `HTTP/1.1 308 Permanent Redirect` |
| `alt-svc` | `h3=":443"` — Caddy bật HTTP/3 mà không ai yêu cầu |

Dự đoán trước khi chạy là mã `301`. Thực tế `308`, và khác biệt có thật: `301`
cho phép trình duyệt đổi `POST` thành `GET` khi chuyển hướng, `308` bắt buộc
giữ nguyên method. Với một trang chỉ có `GET` thì không khác gì, nhưng Caddy
chọn mặc định đúng hơn.

### Bot tìm ra máy chủ

Trong log Caddy có hàng loạt dòng `warn` từ `infrawatch/1.0` và
`GenomeCrawlerd/1.0` (Nokia), gõ vào `host: ""` và `192.0.2.1` — tức là quét
theo dải IP chứ không theo tên miền.

Máy chủ lên IP công khai lúc 20:26 ngày 9/10; dòng bot đầu tiên trong log là
vài giờ sau đó. Không phải tấn công, chỉ là nền nhiễu của internet. Nhưng nó
là lý do cụ thể cho những việc đã làm ở tuần 8: tắt đăng nhập root, tắt mật
khẩu, và không publish cổng nào ngoài 80/443.

### Bài học

**Kiểm xem mình đang đăng nhập bằng tài khoản nào** trước khi kết luận một
dịch vụ đã trả tiền bị hỏng. Một tiếng đi tìm lỗi không tồn tại.

**Lệnh chạy thành công không có nghĩa là nó đã làm gì.** `up -d` trả về 0,
không cảnh báo, và không nạp lại cấu hình. Bằng chứng duy nhất là dấu thời gian
trong log. Cùng họ với ba lỗi tuần 8: phải kiểm *kết quả*, không kiểm *lệnh có
chạy không*.

**Con số nhà cung cấp công bố là biên an toàn, không phải số đo.** 3–24 giờ so
với 10 phút thực tế. Dùng để lên kế hoạch thì được, dùng để kết luận thì không.

## Tuần 10 — Jenkins tự deploy

### Đã làm

Chuyển mấy lệnh làm tay ở tuần 8 vào `Jenkinsfile`: build chéo kiến trúc, đẩy
GHCR, SSH sang máy chủ, dựng lại stack. Cuối tuần một commit vào `main` tự đi
hết đường tới production mà không ai gõ lệnh nào.

Khoá SSH riêng cho Jenkins (`ed25519`, không passphrase), hai credential trong
Jenkins (`vps-deploy` và `ghcr`), và một token GHCR riêng — không dùng lại khoá
hay token cá nhân.

### Bốn lần đo, ba lần đo nhầm

Câu hỏi cần trả lời rất gọn: **Jenkins mất bao lâu cho mỗi lần merge?** Mất bốn
lần đo mới ra, và ba lần đầu đều cho ra một con số hợp lệ cho một câu hỏi không
ai hỏi.

| Lần | Thực tế đo được | backend | frontend |
|---|---|---|---|
| 1 | Tải base image amd64 lần đầu | 4:05 | 2:05 |
| 2 | Builder buildx mới, cache rỗng | 3:57 | 1:50 |
| 3 | Không có gì thay đổi | 0:08 | 0:07 |
| 4 | **Có sửa mã nguồn, cache ấm** | **0:46** | **1:19** |

**Lần 2** chậm vì lần 1 đã tạo một builder `buildx` mới (`docker buildx create
--name multiarch`). Builder mới có kho cache riêng, và nó rỗng — toàn bộ base
image tải về ở lần 1 nằm trong builder cũ, không dùng lại được. Dấu vết trong
log: `booting buildkit`, `pulling image moby/buildkit`.

**Lần 3** ra 8 giây vì mọi layer đều `CACHED`. Lệnh tạo thay đổi là
`echo "" >> file.java` rồi `git checkout` hoàn nguyên. Lần sau chạy lại đúng
lệnh đó cho ra **cùng một nội dung file** — cùng hash, cùng cache. Thay đổi
phải *mới*, không chỉ cần *có*. Lần 4 dùng `echo "// $(date +%s)" >>` nên nội
dung không bao giờ trùng.

Mỗi lần đo đều chạy trót lọt và in ra một con số. Không có lỗi nào để mà sửa.
Đó là lý do loại sai này khó bắt hơn hẳn một lệnh chạy hỏng.

### Chi phí thật của build chéo kiến trúc

Máy cá nhân `arm64`, máy chủ `x86_64`, nên mọi image phải build qua mô phỏng
QEMU. Phạt đo được:

| Bước | Native | Dưới QEMU | Tỉ lệ |
|---|---|---|---|
| `mvn clean package` | 5,4s | 22,3s | **4,1×** |
| `npm run build` | — | 65,8s | — |

`npm run build` được đo ba lần độc lập: 65,3s trên Mac, 65,8s trên Mac lần
khác, 67,5s trong Jenkins. Chênh dưới 2 giây — đủ ổn định để trích dẫn.

Và một kết quả không ngờ: **thời gian build trong Jenkins khớp với trên máy
cá nhân**.

| | Trên Mac | Trong Jenkins |
|---|---|---|
| `mvn clean package` | 22,3s | 22,7s |
| `npm run build` | 65,8s | 67,5s |

Vì Jenkins chạy trong container có mount `/var/run/docker.sock`, nên nó dùng
**chung một Docker daemon** với máy host — chung cả kho image lẫn cache. Jenkins
không có môi trường build riêng; nó chỉ là một client khác của cùng daemon đó.

Điều này đáng đặt cạnh phần so sánh với GitHub Actions ở chương 3: runner của
GitHub sạch mỗi lần chạy, nên không bao giờ có 46 giây; Jenkins ở đây thừa
hưởng cache của máy cá nhân, nên có. Cùng một `Jenkinsfile`, hai con số khác
hẳn nhau, và khác biệt không nằm trong file.

### Ba lần chạy, hai lần đỏ

**#32 đỏ** — credentials chưa tạo. Lỗi của người vận hành, không phải của mã.

**#33 đỏ** — `No such DSL method 'sshagent'`. Plugin SSH Agent không nằm trong
bộ plugin mặc định của Jenkins. Nhưng thông báo lỗi liệt kê luôn mọi bước đang
có, và trong đó có `withCredentials` với kiểu `sshUserPrivateKey` — làm đúng
việc cần: ghi khoá ra file tạm, xoá sau khi khối lệnh kết thúc. Đổi sang dùng
cái đó, không cài plugin, không khởi động lại Jenkins.

Đáng ghi vì nó là một lựa chọn kiến trúc nhỏ: **thông báo lỗi của Jenkins tự
liệt kê phương án thay thế**, nếu chịu đọc hết thay vì chỉ đọc dòng đầu.

**#34 xanh**, 1 phút 30. Nhưng commit đó chỉ sửa `Jenkinsfile`, mà file này
không nằm trong build context của `backend/` hay `frontend/` — nên cả hai image
đều CACHED. 1:30 là **pipeline đầy đủ khi mã nguồn không đổi**. Pipeline có sửa
code thật ước chừng 3 phút 10. Mốc trong kế hoạch là dưới 10 phút.

### Deploy theo số build, không theo `latest` (cơ chế tuần 10)

Cơ chế dưới đây được dùng ở tuần 10–11. Từ cải tiến ngày 17/10, pipeline dùng
manifest digest và `.last-good-release`; không dùng cách sửa `THE_TAG` dưới đây
để thao tác với release mới.

`.env` trên máy chủ được pipeline ghi lại mỗi lần:

    THE_TAG=34

Compose đọc `${THE_TAG:-latest}`, nên container luôn chạy đúng image của một
build cụ thể. Hai hệ quả:

**Trả lời được câu "đang chạy bản nào"** bằng `grep THE_TAG .env`. Deploy
`latest` thì không ai biết, vì `latest` là một cái tên di động.

**Rollback là một dòng**: sửa `THE_TAG` về số cũ, `docker compose up -d`. Image
cũ vẫn còn trên GHCR.

Pipeline cũng gắn cả hai tag `:34` và `:latest` cho mỗi image — số build để
deploy và truy vết, `latest` để tiện kéo tay khi cần.

### Bảo mật

**Token không lộ trong log.** Lệnh đăng nhập viết bằng nháy đơn:

    sh 'echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin'

Nháy đơn nên Groovy không nội suy; shell mới mở biến, và Jenkins che được. Log
in ra `+ echo ****`. Dùng nháy kép thì token bị nhúng thẳng vào chuỗi lệnh
trước khi Jenkins nhìn thấy, và nó sẽ hiện nguyên trong console.

**`docker logout` đặt trong `post { always }`** nên chạy cả khi pipeline đỏ.

**Hạn chế chưa xử lý:** khoá deploy của Jenkins không có passphrase và vào được
production với `sudo` không mật khẩu. Ai lấy được file khoá thì có máy chủ.
Cách siết đúng là thêm `restrict,command="..."` vào `authorized_keys` để khoá
chỉ chạy được đúng một lệnh. Chọn không làm ở tuần này vì nó thêm một chỗ có
thể hỏng vào đúng lúc đang dựng thứ mới — nhưng ghi lại ở đây, cùng chỗ với sáu
giới hạn của G1.

### Số đo

| Chỉ số | Giá trị |
|---|---|
| Pipeline, mã nguồn không đổi | 1 phút 30 |
| Pipeline, có sửa mã nguồn (ước từ số đo) | ~3 phút 10 |
| Mốc trong kế hoạch | dưới 10 phút |
| Phạt QEMU với Maven | 4,1× |
| Số stage | 5 |
| Số lần chạy để pipeline xanh | 3 |

### Bài học

**Một phép đo chạy trót lọt vẫn có thể đo nhầm thứ.** Ba lần liên tiếp, mỗi lần
ra một con số hợp lệ, không lần nào trả lời đúng câu hỏi. Cách bắt được là viết
câu hỏi ra trước — *"Jenkins mất bao lâu mỗi lần merge?"* — rồi hỏi con số vừa
có đúng là câu trả lời cho nó không.

**Thay đổi dùng để thử phải là thay đổi mới, không chỉ là có thay đổi.** Hai
lần chạy cùng một lệnh sinh ra cùng một nội dung, và cache không phân biệt được
lần một với lần hai.

**Đọc hết thông báo lỗi.** Jenkins liệt kê toàn bộ bước đang có ngay trong lỗi
`sshagent` không tồn tại, và phương án thay thế nằm trong danh sách đó.

## Tuần 11 — Cổng G2, tutorial, và bài kiểm phá đi dựng lại

### Đã làm

**Gắn cổng G2 vào pipeline cũ.** `scripts/smoke.mjs` viết từ tuần 7 và được
nối vào Jenkins trong tuần này. Khi đó nó chạy sau bước deploy, bên trong
mạng nội bộ của compose trên máy chủ, gọi backend qua `http://backend:8080`. Thêm một phép kiểm
trang công khai bằng `curl` tới `https://goldcast.click`.

Các lỗi #32–#33 đã ghi ở tuần 10 được nhắc lại dưới đây làm bối cảnh, không
phải hai lần chạy mới của tuần 11:

- Build #32 đỏ vì credentials `ghcr` và `vps-deploy` chưa được tạo.
- Build #33 đỏ với `No such DSL method 'sshagent'`. Plugin SSH Agent không nằm
  trong bộ gợi ý mặc định của Jenkins. Thay vì cài thêm plugin, dùng
  `withCredentials([sshUserPrivateKey(...)])` — chính thông báo lỗi đã liệt kê
  symbol này trong danh sách khả dụng.

**Viết tutorial** `docs/tutorial.md`, 455 dòng, chia hai phần: phần A chạy được
trên máy bất kỳ không tốn tiền, phần B cần máy chủ thuê và tên miền. Mỗi bước
kết thúc bằng một phép kiểm.

**Phá máy chủ đi dựng lại từ số không.** Đây là phần chính của tuần.

### Vì sao phải phá đi dựng lại

Máy chủ chạy được không chứng minh tutorial đủ dùng. Trong ba ngày từ tuần 8
tới tuần 10 có nhiều thứ được sửa bằng tay ngoài script: swapfile tạo lại thủ
công, `.env` gõ tay, Caddy phải `restart` vì `up -d` không ăn, bản ghi DNS
chỉnh trên web. Một số đã đưa vào script, một số có thể chỉ còn trong lịch sử
shell. Không có cách nào phân biệt từ bên ngoài.

Bài kiểm này trả lời đúng một câu: **repo có dựng lại được máy chủ không, hay
máy chủ đang chạy chỉ vì nó tình cờ tồn tại.**

Chi phí thấp bất thường, và là hệ quả của một quyết định từ tuần 2: dữ liệu giá
vàng tự sinh, `APP_INGEST_SEED_ON_EMPTY` bật, database tự dựng lại. Không có dữ
liệu người dùng nào để mất. Nếu có, bài kiểm này đã không dám làm.

### Số đo

Cài lại hệ điều hành qua Virtualizor, cùng image `ubuntu-24.04-x86_64`, cùng
IP, cùng gói Platinum 1.

| Mốc | Giờ |
|---|---|
| Chạm máy mới lần đầu | 21:25 |
| Khoá SSH xong, mã nguồn về máy | 21:30 |
| `dung-may-chu.sh` chạy xong | 21:31 |
| Siết SSH xong, `deploy` vào được | 21:41 |
| `.env` production sinh xong | 21:42 |
| Bốn container chạy | 21:48 |
| **Web sống, HTTPS, cổng đúng** | **21:52** |
| Pipeline xanh hết, `THE_TAG=39` | 21:59 |

**27 phút** từ lúc chạm máy tới lúc web sống. Dự đoán trước khi bắt đầu là 18
phút — lệch 50%.

Trạng thái máy cũ ngay trước khi xoá, lấy từ bảng Virtualizor:

| | |
|---|---|
| Đĩa | 6,41 / 20 GB (32%) |
| Uptime | 2 ngày 23 giờ |
| Băng thông 3 ngày | 1,14 GB (827 MB vào, 344 MB ra) |

Chiều **vào** chiếm 827 MB trong ba ngày, phù hợp với việc kéo image từ GHCR.
Băng thông không đo dung lượng image còn lưu trên đĩa. Việc giữ và dọn image
cần theo dõi riêng; pipeline mới chưa có chính sách dọn release/image cũ và
phải giữ được các digest cần cho rollback.

Quét cổng từ máy khác sau khi dựng xong:

```
22 MO   80 MO   443 MO   3000 dong   5432 dong   8080 dong
```

Build #39 (xanh), thời gian từng phần:

| Stage | Thời gian |
|---|---|
| Test backend | 1,5 s — 102 phép thử, 0 lỗi |
| Build + đẩy backend | ~15 s |
| Build + đẩy frontend | ~14 s |
| Deploy | vài giây |
| G2 sau deploy (script tại thời điểm #39) | 18 phép thử |

**Con số build này không dùng để so sánh được.** Jenkins chạy trên máy Mac,
không bị đụng tới khi xoá máy chủ, nên toàn bộ cache BuildKit còn nguyên — mọi
layer đều `CACHED`, chỉ còn thời gian đẩy lên registry. Một lần build nguội sẽ
khác hẳn. Ghi lại để không trích nhầm vào chương 3.

### Nhận xét

**Ba chỗ vấp được liệt kê trước khi bắt đầu, cả ba đều đúng:**

1. Host key đổi sau khi cài lại — xảy ra ngay lệnh SSH đầu tiên.
2. `/swapfile` rỗng 0 byte của image gốc InterData — **không** xảy ra. Bản vá
   tuần 8 (kiểm kích thước thay vì kiểm sự tồn tại) giữ được. Bằng chứng gián
   tiếp nhưng đủ chắc: lỗi cũ là lỗi chết hẳn ở `mkswap`, và nó không xảy ra
   trên đúng image đó.
3. `known_hosts` cũ trong container Jenkins — xảy ra, làm đỏ build #38.

Đoán trúng cả ba mà vẫn mất 6 phút cho chỗ thứ ba, vì câu lệnh khắc phục bị
sai. **Biết trước một lỗi và sửa được nó là hai việc khác nhau.** Thứ cuối cùng
bắt được lỗi vẫn là phép kiểm, không phải sự tiên liệu.

**`|| true` che mất chính thông báo cần đọc.** Lệnh xoá host key ban đầu viết là
`docker exec jenkins sh -c 'ssh-keygen -R <ip> || true'`. Nó thất bại vì thiếu
`-f` nên không tìm thấy file, nhưng `|| true` nuốt mã lỗi, nên nhìn bên ngoài
như đã chạy xong. Thêm một lớp bảo vệ vào chỗ không cần, và lớp đó giấu đi sự
thật. Bản sửa dùng đường dẫn tuyệt đối và không có `|| true`, rồi kiểm lại bằng
cách `grep` chính file đó chứ không tin vào lệnh vừa chạy.

**Một phép kiểm có thể xanh mà không chứng minh điều nó tưởng.** Tutorial kiểm
khoá Jenkins bằng `ssh -i ~/.ssh/jenkins_deploy deploy@<ip> 'docker ps'`. Thiếu
`IdentitiesOnly=yes`, SSH sẽ lần lượt thử mọi khoá trong `~/.ssh` và rất có thể
vào được bằng khoá khác — phép kiểm đạt, kết luận sai. Đây là biến thể của điều
đã học ở tuần 10 khi đo thời gian build ba lần đều ra số hợp lệ cho câu hỏi
không ai hỏi.

**Suy luận sai từ một bằng chứng thật.** Giữa chừng tôi kết luận script chưa
chạy vì đồng hồ trong thanh tmux còn hiện UTC trong khi script có bước đổi múi
giờ. Bằng chứng đúng, suy luận hỏng: tiến trình tmux khởi động trước khi múi
giờ đổi, và một tiến trình đang chạy giữ nguyên TZ cũ. Mất vài phút đi nhầm
hướng.

**tmux không đáng cho việc này.** Dùng tmux để script sống sót khi rớt mạng là
đúng về nguyên tắc, nhưng một cú cuộn chuột làm tmux nhảy vào chế độ xem lại
lịch sử và nuốt hết phím gõ — mất 5 phút không hiểu vì sao màn hình đứng yên.
Bỏ tmux, chạy thẳng `ssh may-chu 'bash script'` từ máy cá nhân, output chảy về
một cửa sổ duy nhất. Script đã idempotent nên rớt mạng chỉ cần chạy lại.

**Bọc lệnh từ xa thành `ssh dich 'lenh'` loại bỏ hẳn một lớp lỗi.** Từ tuần 8
tới nay đã dán nhầm cửa sổ khoảng sáu lần. Khi lệnh tự mang theo đích đến thì
không còn cửa sổ nào để nhầm. Chỉ bước siết SSH là bắt buộc giữ hai cửa sổ, vì
cần một phiên root mở sẵn làm đường lui.

### Vấn đề gặp phải

Mười một lỗ hổng của tutorial, thu được bằng cách làm theo nó trên máy trắng:

| Mục | Thiếu gì |
|---|---|
| Toàn file | 24 chỗ dùng `<IP>` kiểu ngoặc nhọn — với shell đó là cú pháp chuyển hướng, dán vào là `zsh: parse error` |
| Bảng đầu | Dùng `<TEN_GITHUB>` ở B6 nhưng không có trong bảng chú giải |
| B2 | Không có bước `ssh-keygen -R` sau khi cài lại máy |
| B2 | Mặc định máy chưa có khoá SSH; máy đã có khoá thì bước `ssh-keygen` vừa thừa vừa nguy hiểm, vì nó hỏi ghi đè và dòng dán tiếp theo thành câu trả lời |
| B2 | `ssh-copy-id` thiếu `-i`, sẽ đẩy mọi khoá công khai tìm được lên tài khoản root, gồm cả khoá chỉ dành cho `deploy` |
| B3 | Không ghi log lần chạy script |
| B5 | Viết các lệnh dưới dạng chạy bằng root, trong khi B4 ngay trước đó vừa tắt đăng nhập root |
| B5 | Heredoc tạo `.env` kẹt khi dán vào terminal; thay bằng `printf` một dòng |
| B7 | Thiếu phép quét cổng từ ngoài sau khi stack lên |
| B9 | Thiếu hẳn bước xoá `known_hosts` trong container Jenkins |
| B9 | Phép kiểm khoá Jenkins thiếu `IdentitiesOnly=yes` nên có thể đạt giả |

Chỗ cuối là loại nguy hiểm nhất trong bảng: một phép kiểm chạy đúng, báo xanh,
mà không chứng minh điều nó tưởng mình đang chứng minh.

Còn tồn: `timedatectl` vẫn báo `System clock synchronized: no` trên máy mới,
giống hệt tuần 8. Giờ hiện tại vẫn đúng nên chưa ảnh hưởng, nhưng nếu đồng hồ
trôi thì Let's Encrypt sẽ từ chối chứng chỉ. Chưa xử lý.

Chứng chỉ Let's Encrypt cho `goldcast.click` phải cấp lại vì chứng chỉ cũ nằm
trên đĩa máy cũ. Đây là lần cấp thứ hai trong tuần; giới hạn trùng bộ tên miền
là khoảng 5 lần mỗi 7 ngày, nên không thể lặp lại bài kiểm này nhiều lần trong
cùng một tuần.


## Cải tiến ngày 17/10/2026 — G2 trước deploy, G3 xác minh và rollback

Mục này bổ sung sau tuần 11, được đối chiếu lại ngày 18/10/2026. Không thay thế
hay đo lại số liệu build #34/#39. Mã triển khai nằm trong commit `7494a4c`;
commit `8013a3d` sau đó chuyển cách lấy digest sang metadata của Buildx.

### Thay đổi đã thực hiện

Luồng mới trong `Jenkinsfile`:

```text
G1 kiểm code và test release → build/push image một lần → G2 môi trường riêng
→ Deploy production (chỉ main) → G3 xác minh → ghi nhận bản tốt hoặc rollback
```

- **G1:** giữ `mvn test`, bổ sung TypeScript typecheck và bộ test
  release/rollback/recovery/bootstrap trong Python 3.13 container. Cổng hợp
  đồng Java–TypeScript ở tuần 6 vẫn là một phần của test Maven.
- **Build:** tạo image `linux/amd64`, lấy `containerimage.digest` từ metadata
  Buildx và lưu hai tham chiếu image vào artifact `release.env`. G2 và Deploy
  đọc cùng manifest; production không build lại hay chọn lại qua `latest`.
- **G2:** dùng `docker-compose.ci.yml`, project riêng cho từng build, không
  publish cổng và không dùng DB/volume production. Kiểm cài mới và nâng cấp
  từ V1 với dữ liệu mẫu trong cả bốn bảng nghiệp vụ. Sau mỗi ca, kiểm lịch sử
  Flyway, nội dung API, công thức quy đổi, dự báo NAIVE và ba trang SSR.
- **G3:** đợi readiness, đối chiếu image ID container với digest của release,
  kiểm các trang HTTPS qua Caddy. Chỉ sau khi đạt mới cập nhật nguyên tử
  `.last-good-release`.
- **Rollback/recovery:** lưu cố định `previous-good-release` trước khi deploy.
  Khóa trên VPS có thông tin release, kèm thời điểm, URL build và trạng thái
  trong thư mục release. Lệnh `status` phục vụ kiểm tra; `recover` yêu cầu xác
  nhận build/deploy đã dừng. Không tự xóa khóa theo tuổi.

Khi chuyển production cũ sang cơ chế này, `bootstrap-release.sh` kiểm project
Compose và volume DB, chụp digest đang chạy cùng Caddyfile từ container rồi
xác minh trước khi ghi baseline. Đây là bước cần thực hiện trên VPS, chưa được
coi là đã hoàn thành chỉ vì script có trong repo.

### Kiểm chứng cục bộ đã có

Nguồn: output terminal của phiên kiểm thử ngày 17/10/2026, đã ghi nhận trong
phiên làm việc; chưa có số build Jenkins cho các ca này.

| Hạng mục | Kết quả đã quan sát | Phạm vi |
|---|---|---|
| Backend | 102 test, 0 lỗi | Maven trên máy cá nhân |
| Frontend | typecheck và build image thành công | Bộ mã/phụ thuộc tại lần build đó |
| Release/rollback/recovery/bootstrap | 19 test đạt, gồm chạy trong `python:3.13-alpine` | Docker và VPS được giả lập bằng lệnh trong test |
| G2 cài mới | Flyway đạt; 35 phép kiểm API và 3 trang SSR đạt | Compose cục bộ, image `linux/amd64` đã build tại máy |
| G2 nâng cấp có dữ liệu | V1 → migration mới nhất đạt, dữ liệu bốn bảng giữ nguyên; 35 phép kiểm API và 3 trang SSR đạt | Cùng image ứng viên, DB mẫu riêng |
| Dọn môi trường G2 | Container, mạng và dữ liệu tạm được dọn | Hai ca cục bộ trên |

Không cộng 102 test Java, 19 test mô phỏng và 35 phép kiểm API thành một chỉ
số chất lượng chung: chúng kiểm các tầng khác nhau. Con số 18 ở tuần 7/#39
vẫn là kết quả script cũ; 35 là script đã thêm kiểm dự báo.

### Lỗi tìm ra khi kiểm thử và cách sửa

1. **Rollback có thể chọn nhầm bản vừa được ghi nhận.** Bản trước được lưu
   riêng trước deploy. Sau khi G3 đã ghi nhận thành công, lỗi dọn khóa chỉ
   cảnh báo; một lời gọi rollback muộn không quay lui bản đã được ghi nhận.
2. **Test rollback chưa nằm trong G1.** Đã nối bộ test Python vào G1 để các
   thay đổi sau phải đi qua cùng cơ chế kiểm tra.
3. **Ca nâng cấp vẫn giữ target V1.** Biến môi trường đặt trước lời gọi hàm
   shell còn hiệu lực ở bước sau, khiến kiểm lịch sử Flyway báo lỗi. Đã đưa
   bước V1 vào subshell, đặt target mặc định rõ ràng và kiểm đúng baseline
   trước khi nạp fixture. Lần chạy lại hoàn tất cả nâng cấp và smoke test.
4. **Build bị ngắt có thể để lại khóa.** Đã bổ sung metadata, `status`,
   `recover` và test lỗi xác minh/khóa thuộc release khác; không đoán rằng
   khóa lâu ngày đồng nghĩa với có thể xóa.

### Chưa có bằng chứng và giới hạn

- Chưa có log Jenkins chạy trọn luồng mới, G3 trên VPS hay rollback/recovery
  thực tế trên VPS trong phiên kiểm chứng này. Test mô phỏng không thay thế
  các bằng chứng đó.
- Chưa đo thời lượng từng stage hoặc toàn pipeline mới. Không dùng 7,8 s,
  19 s, 1 phút 30 hay ước lượng ~3 phút 10 của pipeline cũ cho luồng mới.
- Ca nâng cấp mới bao phủ baseline V1 và fixture hiện có; chưa bao phủ mọi
  phiên bản cũ/hình dạng dữ liệu. Rollback ứng dụng không hoàn tác migration DB.
- Các test trang kiểm nội dung SSR, chưa kiểm tương tác JavaScript trong
  trình duyệt. Chưa có cơ chế giám sát dài hạn hay dọn release/image cũ.
- Kết quả local ngày 17/10 gắn với mã và image khi chạy, không tự xác nhận các
  thay đổi sau đó, gồm thay đổi lấy digest và cập nhật phụ thuộc frontend.

Kế hoạch đo và cách đưa bằng chứng mới vào báo cáo được ghi trong
[số liệu chương 3](so-lieu-chuong-3.md#bổ-sung-ngày-17102026--pipeline-g1g2g3).

## Đối chiếu ngày 18/10/2026 — bằng chứng pipeline và hướng dẫn vận hành

Build Jenkins **#55**, commit `81185938bbd3c99db8727f8e53fbe428a5760627`,
chạy nhánh `ci/pre-deploy-gate` và kết thúc `SUCCESS`: G1 36,018 s, build/push
image 22,531 s, G2 93,214 s, tổng 157,211 s. Production `SKIPPED` đúng điều kiện
nhánh. Đây là một mẫu có cache, không phải thời gian toàn luồng Deploy/G3 hay
số trung bình đại diện. Bằng chứng lưu tại
[`measurements/pipeline-new/build-55/`](measurements/pipeline-new/build-55/);
phân tích trong [số liệu chương 3](so-lieu-chuong-3.md).

Các mục “chưa đo” ở phần kiểm chứng ngày 17/10 mô tả thời điểm đó. Build #55
bổ sung bằng chứng Jenkins cho G1/build/G2; vẫn chưa xác nhận bootstrap,
Deploy/G3 hoặc rollback/recovery thật trên VPS của luồng mới.

Đợt đối chiếu repo với mã hiện tại sửa các sai lệch sau:

- Production Compose nhận `APP_INGEST_ENABLED`, `APP_INGEST_SEED_ON_EMPTY`
  và `APP_INGEST_CRON` từ môi trường; seed production mặc định `false`.
  Hai biến image bắt buộc lấy từ manifest, bỏ fallback `THE_TAG`/`latest`.
  `.env.example` dành cho dev vẫn bật seed và bỏ biến public API không được dùng.
- `make seed` gửi `X-Admin-Token` từ container backend; thiếu token hoặc HTTP
  lỗi phải trả mã lỗi. Ba test lệnh ingest bổ sung vào bộ test Python mà G1
  đã chạy bằng unittest discovery; 19 test release cũ giữ nguyên.
- README dùng đúng số liệu #55 và phân biệt cấu hình trong repo với trạng
  thái VPS đã kiểm chứng. Mô tả dữ liệu khớp ingest: provider thật, seed chỉ
  cho chuỗi rỗng, lịch sử mô phỏng không tự bị xoá khi nguồn thật phục hồi.
- Tutorial bổ sung Compose Jenkins còn thiếu; chạy test bằng container,
  phân biệt ba service dev với bốn service production. Nhánh kiểm thử đạt
  G2 trước khi chuẩn bị VPS, không hứa Production xanh ở phần A.
- Khởi tạo/khôi phục VPS dùng manifest digest và cấu hình của đúng commit,
  giữ DB backup khi cần lịch sử cũ, kiểm bootstrap trước khi nối lại Jenkins.
  Không lấy `latest` hay `THE_TAG` để xác định bản đang chạy.
- Bỏ dòng tuyên bố giấy phép MIT trong README vì repo chưa có file giấy phép;
  không tự bổ sung một giấy phép mới.
- GitHub: đóng #23/#42 vì thay đổi ignore đã có trong `main`, đóng #44 vì
  cùng commit với #46; giữ #46 cho phần tích hợp nguồn dữ liệu còn đang mở.

Kiểm tra local: 102 test Java, 22 test Python và TypeScript typecheck đạt;
các ca render Compose production kiểm mặc định và override bật/tắt
ingest/seed, cron và cổng publish đều đạt. Đây
là thay đổi mã/tài liệu; không thay cấu hình Jenkins hay triển khai lên VPS.

## Sửa tiếp ngày 18/10/2026 — định danh công khai và snapshot rollback

Sau PR #52, rà soát tìm thêm các trường hợp mà kiểm tra có thể xanh nhưng chưa
chứng minh đủ điều cần kiểm. Đã sửa theo thứ tự:

1. **Phiên bản qua HTTPS:** manifest có `RELEASE_ID`, truyền cùng giá trị tới
   frontend/backend. `/api/release` của frontend gọi `/api/v1/release` ở backend;
   probe đòi cả hai định danh khớp manifest trước khi kiểm ba trang. Test bắt
   bản cũ, backend sai bản, endpoint thiếu và trang lỗi dù HTTP vẫn trả 200.
   Image được build một lần; định danh được truyền khi chạy container.
2. **Baseline từ runtime:** bootstrap chụp thông tin Docker đang chạy thay vì
   sao chép Compose mới. Giữ image digest cả bốn service, env, lệnh, healthcheck,
   volume và Caddyfile. File snapshot và `runtime.env` quyền 600 vì có thể chứa
   bí mật. Deploy mới giữ `.env` riêng để rollback không đọc nhầm cấu hình mới.
3. **Chuyển baseline cũ:** `bootstrap-release.sh --refresh` chỉ thay con trỏ sau
   xác minh và không restart ứng dụng. Nếu thất bại vẫn giữ bản tốt cũ. Baseline
   chưa có endpoint phiên bản chỉ được adopt khi DNS HTTPS trỏ trực tiếp vào
   VPS và frontend gọi backend nội bộ; release mới không được dùng chế độ này.
   Deploy từ chối baseline đời cũ trước khi thay production để yêu cầu refresh.
4. **Chặn merge bằng toàn bộ G1:** giữ tên required check `backend-test`, bổ
   sung test Python/Node và TypeScript bên cạnh Java. Jenkins chạy cùng nhóm
   test. Không đổi điều kiện bảo vệ nhánh để bỏ qua kiểm tra.
5. **CORS và ingest:** preflight hợp lệ tới MVC kiểm origin, POST vẫn cần token.
   Khóa ingest nằm trong service và giữ qua commit/rollback transaction, nên
   cả scheduler lẫn admin dùng chung. Yêu cầu chạy chồng trả HTTP 409.
6. Dọn rule ignore trùng và thụt dòng Jenkinsfile; gói source bằng tar từ macOS
   tắt AppleDouble metadata để test hợp đồng không quét nhầm file `._*.java`.

Kiểm tra local: **114 test Java trên JDK 21 trong Docker**, **19 test Node**,
**29 test Python** và TypeScript typecheck đạt. G2 trên hai image `linux/amd64`
vừa build hoàn tất cả cài mới và nâng cấp V1 có dữ liệu: mỗi ca đạt 35 phép
kiểm API, đúng định danh frontend/backend và ba trang SSR. Container, mạng và
volume G2 được dọn sau kiểm tra. Log local ở
`/private/tmp/goldcast-verification.zpDz1P/`, chưa lưu thành mẫu đo Jenkins.

Test cấu hình bằng container thật xác nhận `$` trong bí mật được giữ nguyên.
Các test probe/bootstrap riêng dùng Docker và HTTP giả lập; G2 local dùng
container thật. Các kết quả này không phải bằng chứng G3 hoặc rollback thật
trên VPS. Số liệu build
#55 và các mốc lịch sử giữ nguyên; lần sửa này chưa tạo một mẫu thời lượng
pipeline Jenkins mới.

## Vận hành ngày 18/10/2026 — Jenkins main, bootstrap và rollback thật

Sau khi PR #53 được merge, kiểm tra thấy job Jenkins vẫn checkout
`ci/pre-deploy-gate`; build mới nhất khi bắt đầu là #57, chưa phải main mới.
VPS còn chạy image `:49`, chưa có baseline của cơ chế release mới.

Đã sao lưu PostgreSQL, truyền các script từ main `a58f2cc` vào thư mục bảo trì
riêng và chạy bootstrap. Baseline
`/opt/goldcast/releases/bootstrap-20261018203238` vượt kiểm image/readiness và
ba trang HTTPS, giữ cấu hình thực tế và không restart production. Sau đó đổi
job sang `*/main`, giữ Poll SCM và chủ động chạy **build #58**.

#58 đạt 114 test Java, 29 test Python, 19 test Node và typecheck. Build/push
image một lần, cả hai ca G2 đạt; Production thực thi, Deploy/G3 thành công.
Thời lượng toàn build **371,948 giây**; Deploy **46,920 giây**, G3 **3,591 giây**.
Định danh frontend/backend công khai và image ID VPS khớp manifest đã qua G2.
Con trỏ bản tốt được cập nhật sau G3, không còn khóa deploy.

Sau #58, chạy một phép thử rollback thủ công: giữ digest, đổi định danh
frontend trong cấu hình drill để endpoint phiên bản trả 503. G3 từ chối,
con trỏ bản tốt vẫn giữ #58; rollback khôi phục frontend/Caddy rồi xác minh lại
HTTPS, định danh và ba trang. Backend/database không bị restart. Phép thử
chứng minh khôi phục cấu hình runtime trên VPS thật; chưa phải ca Jenkins tự
gọi rollback, quay về digest khác hoặc phục hồi database.

Bằng chứng: [bộ đo #58](measurements/pipeline-new/build-58/console.log),
[manifest](measurements/pipeline-new/build-58/release.env) và
[biên bản/log VPS](measurements/pipeline-new/vps-2026-10-18/README.md).
Các số #55 và nhận định giới hạn ở các mục trước là trạng thái lịch sử;
không sửa chúng thành số đo của #58.

Đồng thời siết `release.sh`: kiểm duy nhất ba khóa cho phép, định dạng định
danh, đúng repository từng service và digest SHA-256 đủ 64 ký tự hex thường;
từ chối khóa trùng hoặc khóa lạ trước Docker/khóa deploy. Kiểm cả baseline,
verify và mục tiêu rollback; legacy chỉ được thiếu định danh khi có marker.
Kiểm tra hồi quy: 36 test Python trên host và Alpine, 20 test Node. Số test
này thuộc lần sửa manifest, không thay các số 29/19 trong log Jenkins #58.

Theo yêu cầu, bỏ bước xử lý cảnh báo dependency Tailwind; không thay đổi
`package.json` hoặc lockfile trong lần này.

## Cải tiến vận hành ngày 21/10/2026

Thực hiện lần lượt các mục đã rà soát: ghim PostgreSQL/Caddy theo digest của
release #60; healthcheck định danh frontend và retry probe; cảnh báo dữ liệu
cũ theo lịch giao dịch, trạng thái ingest chỉ cập nhật sau commit; giới hạn
pipeline/SSH/lệnh remote, xoay vòng log và retention có bảo vệ bản rollback;
script backup ngoài VPS có restore test; job Jenkins diễn tập rollback riêng.
Chi tiết và giới hạn: [vận hành production](van-hanh-production.md).

Kiểm tra local: 119 test Java trên JDK 21, 45 test Python, 22 test Node và
TypeScript typecheck đạt. Cả ba Jenkinsfile được API Jenkins xác nhận cú pháp.
Java trong sandbox ban đầu lỗi agent attach của Mockito; chạy lại trên cùng
JDK 21 ngoài sandbox đạt, không thay đổi test để bỏ qua lỗi.

Script backup mới được chạy trên release #60: checksum, manifest, định danh
công khai, hai manifest GHCR đạt; dump phục hồi trong PostgreSQL 16.15 tạm,
không có mạng/cổng, có 5 instrument và 5.317 price_point, 0 forecast_run/point,
2 migration thành công, 0 thất bại. Dữ liệu/cấu hình bí mật lưu ngoài repo.
Đây là backup thủ công bằng script, chưa tự chứng minh lịch backup đã chạy.

Diễn tập Docker local dùng baseline #58 và candidate #60 có digest khác:
G3 từ chối định danh sai; harness local gọi rollback; image, định danh, ba
trang, 35 phép kiểm API, con trỏ và khóa đều được xác minh. Không gọi đó là
Jenkins tự rollback. Production vẫn giữ #60 trong các phép thử này.
