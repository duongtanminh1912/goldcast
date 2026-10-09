
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
