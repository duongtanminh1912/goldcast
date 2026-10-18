# Tutorial: dựng quy trình DevOps cho GoldCast

Tài liệu này hướng dẫn dựng lại toàn bộ quy trình từ con số không: chạy ứng
dụng, chạy test, dựng CI, đưa lên internet, và để mỗi commit tự đi tới
production khi commit vào `main` và vượt các cổng kiểm tra.

## Dành cho ai

Người biết lập trình và dùng được dòng lệnh, nhưng chưa từng dựng CI/CD.
Không cần biết Jenkins, Docker Compose hay Caddy từ trước.

## Hai phần, và vì sao chia

**Phần A chạy được trên máy bạn, không tốn đồng nào.** Ai cũng làm theo được.

**Phần B cần một máy chủ thuê và một tên miền.** Chi phí trong nhật ký là số
liệu lịch sử, cần kiểm lại khi thuê. Phần A chạy G1/build/G2 trên nhánh riêng,
không cần VPS; phần B bổ sung Deploy/G3 và baseline production.

Mỗi bước đều kết thúc bằng một **phép kiểm**. Đừng đi tiếp khi phép kiểm chưa
đạt — mọi lỗi tốn nhiều thời gian nhất trong đồ án này đều đến từ việc bỏ qua
một phép kiểm và phát hiện ra ba bước sau đó.

## Giá trị cụ thể của đồ án này

Thay bằng giá trị của bạn khi làm theo.

Phần B dùng năm biến shell. Gán một lần ở đầu mỗi phiên terminal (xem đầu Phần B).

| Biến | Đồ án này dùng |
|---|---|
| `REPO` | `duongtanminh1912/goldcast` |
| `IP` | `103.175.248.236` |
| `TEN_MIEN` | `goldcast.click` |
| `REGISTRY` | `ghcr.io/duongtanminh1912` |
| `TEN_GITHUB` | `duongtanminh1912` |

---

# Phần A — chạy trên máy bạn

## A1. Chuẩn bị

Cần Docker Desktop (hoặc Docker Engine) và Git. Không cần cài Java, Node hay
PostgreSQL — tất cả chạy trong container.

```bash
git clone https://github.com/duongtanminh1912/goldcast.git
cd goldcast
cp .env.example .env
```

**Kiểm:** `docker version` phải có cả **Client và Server**;
`docker compose version` hiển thị phiên bản. Chỉ có Client chưa chứng minh
Docker daemon đang chạy.

## A2. Chạy toàn bộ ứng dụng

```bash
docker compose up -d
docker compose ps
```

Lần đầu mất vài phút vì phải build cả backend lẫn frontend.

**Kiểm:** ba service trong `ps`: db, backend, frontend; backend và db ở trạng thái `healthy`. Mở
`http://localhost:3000` thấy giao diện có dữ liệu.

Ingest thử lấy dữ liệu từ provider; cấu hình dev cho phép seed mô phỏng khi
chuỗi rỗng. Kiểm nhãn nguồn trên giao diện. Tắt seed không xoá dữ liệu mô phỏng
đã tồn tại. Đồ án tập trung vào quy trình DevOps.

## A3. Chạy test, và xem cổng G1 chặn

```bash
# Chạy từ thư mục gốc repo; không cần Maven/JDK trên máy cá nhân.
backend_test() {
  COPYFILE_DISABLE=1 tar --exclude=backend/target --exclude=frontend/node_modules --exclude=frontend/.next -cf - backend frontend |
    docker run --rm -i maven:3.9-eclipse-temurin-21 sh -ec '
      mkdir /src; cd /src; tar -xf -
      mvn -B -f backend/pom.xml "$@"
    ' sh "$@"
}
backend_test test
```

**Kiểm:** `BUILD SUCCESS`, không có failure. Mốc đã lưu là 102 test Java;
số test có thể tăng ở các commit sau. Hàm trên chỉ tồn tại trong phiên terminal
hiện tại và cần chạy ở thư mục gốc repo.

Trong số đó có `ApiContractTest` — cổng chất lượng thứ nhất. Nó đối chiếu các
DTO Java với các kiểu TypeScript ở frontend, và đỏ khi hai bên lệch nhau.

Thử phá nó để thấy nó thật sự gác:

```bash
# doi ten mot truong trong types.ts
sed -i '' 's/  series:/  seriesX:/' frontend/src/lib/types.ts
backend_test test -Dtest=ApiContractTest
```

Lệnh `sed -i ''` dành cho macOS; trên Linux dùng `sed -i`.

**Kiểm:** test đỏ, và thông báo chỉ đúng tên trường bị lệch. Hoàn nguyên:

```bash
git restore frontend/src/lib/types.ts
```

Bước này quan trọng hơn vẻ ngoài của nó: **một cổng chưa từng được chứng minh
là biết chặn thì chưa phải là cổng.**

## A4. Dựng Jenkins

```bash
# Lấy GID của socket bên trong Docker, dùng được cả Docker Desktop và Linux.
export DOCKER_GID=$(docker run --rm -v /var/run/docker.sock:/var/run/docker.sock docker:27-cli stat -c %g /var/run/docker.sock)
docker compose -f jenkins/compose.yml up -d --build
```

Mở `http://localhost:8081`. Lấy mật khẩu khởi tạo:

```bash
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Chọn "Install suggested plugins", tạo tài khoản quản trị.

**Kiểm:** đăng nhập được vào trang chủ Jenkins.

File `jenkins/compose.yml` là cấu hình cài mới: lưu Jenkins home trong volume
và chỉ mở UI tại `127.0.0.1:8081`. Nếu đã có Jenkins, tiếp tục dùng instance đó;
không chạy thêm container trùng tên/port hoặc thay volume đang chứa credentials.
Khi dùng file Compose này ở terminal mới, gán lại `DOCKER_GID` trước.

## A5. Pipeline đầu tiên

Trước khi chạy Jenkinsfile đầy đủ:

- Nếu fork repo, sửa `REG` trong `Jenkinsfile` thành namespace GHCR của bạn.
  Nếu đổi domain, sửa cả `deploy/Caddyfile` và URL trong hàm `verify` của
  `scripts/release.sh`. Commit cấu hình trước khi build.
- Tại Manage Jenkins → Credentials → System → Global, thêm credential
  **Username with password**, ID `ghcr`: tên GitHub và token có quyền
  `write:packages`. Chưa cần credential VPS cho nhánh kiểm thử.
- Repo phải có nhánh `ci/pre-deploy-gate` trên remote. Nếu repo của bạn chưa có,
  tạo nhánh này từ `main` và push trước.

New Item → đặt tên `goldcast-ci` → **Pipeline** → OK.

Trong phần Pipeline: chọn **Pipeline script from SCM**, SCM là **Git**, URL là
repo của bạn, **Branches to build là `*/ci/pre-deploy-gate`**, Script Path là
`Jenkinsfile`. Giữ nhánh này trong phần A để Production được bỏ qua.

Trong Build Triggers, tick **Poll SCM** và điền `H/5 * * * *` — Jenkins sẽ hỏi
Git mỗi 5 phút xem có commit mới không.

Bấm **Build Now**.

**Kiểm:** G1, Registry login, Build images once và G2 đều đạt; **Production
`SKIPPED`**. Có artifact `release.env` chứa hai digest và `RELEASE_ID`.
G1 gồm test Java, typecheck và bộ test vận hành Python/Node; chỉ thấy test Java đạt chưa đủ kết luận
toàn bộ pipeline xanh.

Tới đây bạn đã có ứng dụng, CI và hai cổng G1/G2 trước deploy. Production
được bỏ qua có chủ đích cho nhánh kiểm thử. Phần A hết.

---

# Phần B — đưa lên internet

Phần B dùng năm biến shell. **Gán lại ở đầu mỗi phiên terminal mới**, kể cả sau
khi `ssh` sang máy chủ — biến shell không đi theo kết nối:

```bash
IP=103.175.248.236
TEN_MIEN=goldcast.click
TEN_GITHUB=duongtanminh1912
REPO=duongtanminh1912/goldcast
REGISTRY=ghcr.io/duongtanminh1912
```

Thay năm giá trị bằng của bạn. `REG` và `VPS` trong Jenkinsfile cũng phải khớp;
nếu đổi domain, sửa cả Caddyfile và URL kiểm HTTPS trong `scripts/release.sh`.
Nếu đổi tài khoản/registry, sửa hai repository cho phép trong `validate_manifest`
của `scripts/release.sh` để khớp image backend/frontend do Jenkins build.

## B1. Thuê máy chủ

Cấu hình đã chạy ở lần đo cũ: **1 vCPU, 2 GB RAM, 20 GB đĩa**; stack khi đó
dùng khoảng 531 MiB. Chưa đo lại tài nguyên cho mọi commit của luồng mới.

Yêu cầu bắt buộc khi chọn nhà cung cấp:

- **Ảo hoá KVM**, không phải OpenVZ — Docker cần KVM
- **IPv4 public riêng**, không phải NAT
- **Ubuntu 24.04 LTS** trong danh sách hệ điều hành

Đồ án này dùng InterData Platinum 1, 213.400đ/tháng gồm VAT, trả theo tháng.

Khi đặt hàng, chọn image **`ubuntu-24.04-x86_64`** — không phải bản `desktop`
(có giao diện đồ hoạ, phí RAM) và không phải các bản cài sẵn phần mềm
(`-n8n`, `-ezyplatform`...) vì chúng thường đã chiếm cổng 80 và 443.

**Kiểm:** SSH vào được bằng mật khẩu root nhà cung cấp cấp.

## B2. Đẩy khoá SSH lên trước khi làm gì khác

**Nếu máy chủ vừa được cài lại**, nó sinh host key mới và SSH sẽ từ chối kết nối
với `REMOTE HOST IDENTIFICATION HAS CHANGED`. Xoá bản ghi cũ trước:

```bash
ssh-keygen -R $IP
```

Xem máy đã có khoá chưa:

```bash
ls -l ~/.ssh/id_ed25519
```

**Chưa có** thì tạo. **Đã có thì bỏ qua lệnh dưới** — chạy lại nó sẽ hỏi ghi đè,
và dòng bạn dán tiếp theo trở thành câu trả lời cho câu hỏi đó, dẫn tới một
khoá riêng mang tên rất lạ nằm trong thư mục làm việc:

```bash
ssh-keygen -t ed25519 -C "goldcast" -f ~/.ssh/id_ed25519
```

Đẩy khoá công khai lên:

```bash
ssh-copy-id -i ~/.ssh/id_ed25519.pub root@$IP
```

`-i` là bắt buộc. Thiếu nó, `ssh-copy-id` đẩy **mọi** khoá công khai tìm thấy
trong `~/.ssh` lên tài khoản root, gồm cả khoá riêng dành cho Jenkins mà bạn sẽ
tạo ở B9 — khoá đó chỉ được phép vào bằng tài khoản `deploy`.

**Kiểm — đừng bỏ qua:**

```bash
ssh -o PasswordAuthentication=no root@$IP
```

Vào được mà không hỏi mật khẩu máy chủ. Nếu bước này chưa đạt mà vẫn đi tiếp
tới B4, bạn sẽ tự khoá mình ra khỏi máy.

## B3. Dựng máy chủ bằng script

```bash
apt update && apt install -y git tmux
tmux new -s setup
git clone https://github.com/$REPO.git
cd goldcast
cat deploy/dung-may-chu.sh
```

Đọc script trước khi chạy. Rồi:

```bash
bash deploy/dung-may-chu.sh 2>&1 | tee /root/dung-may-chu.log
```

`tee` giữ lại toàn bộ output vào file, để đọc lại sáu dòng kiểm cuối khi màn
hình đã trôi.

Chạy trong `tmux` để mất mạng giữa chừng script vẫn tiếp tục. **Đừng cuộn chuột
trong tmux khi script đang chạy** — nó nhảy vào chế độ xem lại lịch sử, màn hình
đứng yên và mọi phím gõ bị nuốt, rất dễ tưởng là máy treo. Bấm `q` để thoát ra.

Không muốn dùng tmux thì chạy thẳng từ máy cá nhân, output vẫn chảy về:

```bash
ssh root@$IP 'bash /root/goldcast/deploy/dung-may-chu.sh 2>&1 | tee /root/dung-may-chu.log'
```

Rớt mạng thì script chết, nhưng nó idempotent nên chạy lại y nguyên là xong.

Script làm sáu việc: đặt múi giờ, tạo swap 2 GB, cài Docker, tạo user `deploy`,
mở ufw cho 22/80/443, và in lại kết quả để kiểm.

**Kiểm:** sáu dòng cuối phải cho thấy Docker có phiên bản, swap `2.0Gi`, user
`deploy` có nhóm `docker`, và ufw chỉ mở 22/80/443.

Cấp sudo cho `deploy` để sau này quản trị được:

```bash
usermod -aG sudo deploy
echo 'deploy ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/deploy
chmod 440 /etc/sudoers.d/deploy
visudo -c
```

**Kiểm:** ba dòng `parsed OK`.

## B4. Siết SSH — bước nguy hiểm nhất

Trước khi chạy, **chứng minh user `deploy` vào được**, từ máy cá nhân:

```bash
ssh deploy@$IP 'sudo -n id && echo SUDO_OK'
```

Phải ra `uid=0(root)` kèm `SUDO_OK`. Chưa ra thì **dừng lại**.

Đạt rồi thì trên máy chủ, **giữ nguyên phiên SSH đang mở**:

```bash
TOI_DA_KIEM_KHOA=yes bash deploy/siet-ssh.sh
```

Script tắt đăng nhập root và tắt mật khẩu, rồi tự kiểm bốn giá trị sshd và
dừng với lỗi nếu sai.

**Kiểm:** bốn dòng `ok`. Rồi từ **cửa sổ khác** trên máy cá nhân:

```bash
ssh deploy@$IP 'echo VAO_DUOC'
```

Ra `VAO_DUOC` thì mới đóng phiên root. Không ra thì phiên root vẫn còn, hoàn
tác bằng `rm /etc/ssh/sshd_config.d/01-goldcast.conf && systemctl reload ssh`.

## B5. Chuẩn bị chỗ cho ứng dụng

Sau khi khoá SSH, `/root` không với tới được nữa. Mã nguồn phải nằm ở chỗ
`deploy` sở hữu:

B4 vừa tắt đăng nhập root, nên từ đây mọi thứ đi qua `deploy` và `sudo`. Chạy
từ máy cá nhân:

```bash
ssh deploy@$IP
sudo mkdir -p /opt/goldcast
sudo chown deploy:deploy /opt/goldcast
git clone https://github.com/$REPO.git /opt/goldcast
```

Clone bằng `deploy` chứ không bằng root, để không sinh ra file thuộc sở hữu root
trong thư mục mà `deploy` phải ghi được.

Tạo `.env` production. Bí mật sinh thẳng vào file, không qua màn hình:

```bash
cd /opt/goldcast
umask 077
printf 'POSTGRES_DB=goldcast\nPOSTGRES_USER=goldcast\nPOSTGRES_PASSWORD=%s\nAPP_ADMIN_TOKEN=%s\nAPP_CORS_ALLOWED_ORIGINS=https://%s\nAPP_INGEST_ENABLED=true\nAPP_INGEST_SEED_ON_EMPTY=false\n' \
  "$(openssl rand -hex 24)" "$(openssl rand -hex 32)" "$TEN_MIEN" > .env
chmod 600 .env
```

Dùng `printf` chứ không dùng heredoc: dán một khối heredoc vào terminal rất hay
kẹt ở dấu nhắc `heredoc>` và phải Ctrl+C bỏ dở.

`umask 077` đặt trước khi tạo để file kín ngay từ lúc sinh ra, không hở một nhịp
rồi mới `chmod`.

**Kiểm:** `ls -l .env` ra `-rw-------`, và `grep -c . .env` ra `7`. Đừng `cat`
file đó ra. Nếu dựng bản demo cần dữ liệu ngay khi nguồn ngoài không truy cập
được, chủ động đổi `APP_INGEST_SEED_ON_EMPTY=true` trước khi chạy stack; giao
diện sẽ gắn nhãn mô phỏng. Với seed tắt, cần provider nạp đủ lịch sử để kiểm
các trang dự báo. Không ghi đè `.env` nếu đang dùng một DB volume đã tồn tại.

## B6. Chọn artifact đã qua G2

Jenkins đã build cho `linux/amd64`, push và kiểm image ở phần A. Không build
lại bằng tay cho production. Chọn build có G1/G2 thành công; tải artifact
`release.env` về thư mục gốc repo local (file này được Git bỏ qua). Ghi lại
**commit đầy đủ của đúng build đó**, không lấy HEAD hiện tại để đoán.

Từ thư mục gốc repo trên máy cá nhân:

```bash
RELEASE_COMMIT='SHA_CUA_BUILD_DA_QUA_G2'
RELEASE_DIR="/opt/goldcast/releases/initial-$RELEASE_COMMIT"
grep -Ec '^(BACKEND|FRONTEND)_IMAGE=[^[:space:]]+@sha256:[a-f0-9]{64}$' release.env
grep '^RELEASE_ID=' release.env
ssh deploy@$IP "mkdir -p '$RELEASE_DIR'"
git archive "$RELEASE_COMMIT" docker-compose.prod.yml deploy/Caddyfile scripts/release.sh scripts/check-pages.mjs |
  ssh deploy@$IP "tar -xf - -C '$RELEASE_DIR'"
scp release.env "deploy@$IP:$RELEASE_DIR/release.env"
```

**Kiểm:** phép đếm image phải ra `2`, hai image dùng `@sha256:`, không phải tag;
có `RELEASE_ID` của đúng build đã qua G2.
Commit đã chọn phải chứa domain/Caddyfile đúng cho môi trường của bạn.
VPS phải kéo được các package GHCR: đặt Public hoặc đăng nhập registry trên
VPS bằng token có quyền đọc. Không thay digest bằng `latest` khi gặp lỗi kéo.

## B7. Chuẩn bị tên miền trước khi chạy Caddy

Mua tên miền, rồi thêm hai bản ghi A trỏ về IP máy chủ của bạn:

| Loại | Tên | Giá trị | TTL |
|---|---|---|---|
| A | `@` | IP máy chủ | 300 |
| A | `www` | IP máy chủ | 300 |

**Kiểm, và chờ cho tới khi đạt:**

```bash
dig +short $TEN_MIEN
dig +short www.$TEN_MIEN
```

Cả hai phải ra đúng IP máy chủ. Chờ DNS đúng trước khi chạy Caddy để tránh
lỗi cấp chứng chỉ. Caddyfile nằm trong release đã chuyển ở B6; sửa file ở
checkout `/opt/goldcast/deploy` không thay đổi release đang chạy.

## B8. Chạy đúng digest và ghi baseline lần đầu

Từ máy cá nhân, vẫn giữ biến `RELEASE_DIR` của B6:

```bash
ssh deploy@$IP "docker compose --env-file /opt/goldcast/.env --env-file '$RELEASE_DIR/release.env' -p goldcast -f '$RELEASE_DIR/docker-compose.prod.yml' up -d --wait --wait-timeout 300"
ssh deploy@$IP "docker compose --env-file /opt/goldcast/.env --env-file '$RELEASE_DIR/release.env' -p goldcast -f '$RELEASE_DIR/docker-compose.prod.yml' ps"
```

Dùng project `goldcast` để tạo đúng volume `goldcast_goldcast-pgdata`. Bốn
service production chạy; backend và db healthy. Chỉ Caddy publish 80/443.

Kiểm HTTPS và chuyển hướng từ máy cá nhân:

```bash
curl -I https://$TEN_MIEN
curl -I http://$TEN_MIEN
```

Cái đầu trả 200, cái sau 308 sang HTTPS. Caddy tự xin chứng chỉ. Rồi quét cổng
từ bên ngoài VPS:

```bash
for p in 22 80 443 3000 5432 8080; do
  nc -z -w 2 $IP "$p" >/dev/null 2>&1 && echo "$p MO" || echo "$p dong"
done
```

Chỉ 22, 80, 443 mở. Cổng Docker publish có thể vượt qua luật ufw nên không
chỉ dựa vào `ufw status`. Sau khi kiểm tra đạt, ghi baseline bằng chính script
và manifest của release:

```bash
ssh deploy@$IP "sh '$RELEASE_DIR/scripts/release.sh' bootstrap '$RELEASE_DIR'"
ssh deploy@$IP 'cat /opt/goldcast/.last-good-release'
```

Bootstrap xác minh readiness, image ID và các trang HTTPS trước khi ghi con
trỏ. Nếu lỗi thì sửa và chạy lại, không tự tạo `.last-good-release`. Đây là
bước triển khai đầu tiên; từ lần sau dùng pipeline ở B9/B10.

## B9. Jenkins tự deploy

Tạo khoá riêng cho Jenkins — **không dùng khoá cá nhân**, vì Jenkins chạy tự
động nên khoá không được có passphrase:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/jenkins_deploy -N "" -C "jenkins"
ssh-copy-id -i ~/.ssh/jenkins_deploy.pub deploy@$IP
```

**Kiểm:**

```bash
ssh -i ~/.ssh/jenkins_deploy -o IdentitiesOnly=yes deploy@$IP 'docker ps && echo OK'
```

`IdentitiesOnly=yes` là mấu chốt. Thiếu nó, SSH thử lần lượt mọi khoá trong
`~/.ssh` và rất có thể vào được bằng khoá cá nhân — phép kiểm báo đạt mà không
chứng minh được điều cần chứng minh.

Trong Jenkins, Manage Jenkins → Credentials → System → Global, thêm hai mục:

| Kind | ID | Nội dung |
|---|---|---|
| SSH Username with private key | `vps-deploy` | username `deploy`, khoá `~/.ssh/jenkins_deploy` |
| Username with password | `ghcr` | tên GitHub, token `write:packages` |

Lấy khoá vào clipboard mà không in ra màn hình: `pbcopy < ~/.ssh/jenkins_deploy`
(macOS) hoặc `xclip -sel clip < ~/.ssh/jenkins_deploy` (Linux).

Trong job Jenkins, đổi **Branches to build** sang `*/main` khi đã có baseline
và credential VPS, rồi cấu hình Poll SCM như A5. Nếu thiếu baseline hoặc chưa
sẵn sàng deploy, giữ nhánh kiểm thử. Các điểm cần chú ý trong Jenkinsfile:

**Đăng nhập registry phải dùng nháy đơn:**

```groovy
sh 'echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin'
```

Nháy đơn nên Groovy không nội suy; shell mới mở biến, và Jenkins che được thành
`****`. Dùng nháy kép thì token bị nhúng vào chuỗi lệnh trước khi Jenkins nhìn
thấy, và nó hiện nguyên trong log.

**Deploy đúng image đã kiểm thử:** mỗi build tạo tag `<commit>-<job>-<build>`, rồi
lưu hai digest và `RELEASE_ID` vào artifact `release.env`. G2 và production dùng cùng digest;
không build lại, không dùng `latest`. Cấu hình từng release được giữ trong
`/opt/goldcast/releases/<commit>-<job>-<build>/`.

Trước khi lấy khóa hoặc chạy Docker, `release.sh deploy` kiểm manifest:
mỗi khóa `RELEASE_ID`, `BACKEND_IMAGE`, `FRONTEND_IMAGE` xuất hiện đúng một lần;
không nhận khóa khác. Image phải thuộc đúng repository của từng service và có
digest `sha256` gồm 64 ký tự hex thường; không nhận tag, nội suy hoặc giá trị
có dấu nháy. Cho phép dòng trống và comment riêng. Baseline và rollback cũng
được kiểm trước khi chạy Compose; chỉ baseline đã có marker legacy mới được
thiếu `RELEASE_ID`. Manifest lỗi bị từ chối trước `pull`/`up` và không được
ghi nhận thành bản tốt.

**Chuẩn bị một lần khi chuyển từ pipeline cũ:** trên VPS, cập nhật checkout
chứa các script mới rồi chạy `sh scripts/bootstrap-release.sh` tại
`/opt/goldcast`. Script chụp digest, môi trường, lệnh, healthcheck, volume và
cấu hình Caddy của container đang chạy; không lấy Compose mới để giả định cấu
hình cũ. Kiểm project `goldcast`, volume `goldcast_goldcast-pgdata`, readiness
và HTTPS trước khi ghi `.last-good-release`. Không restart production.

Nếu đã có baseline tạo trước cơ chế định danh/snapshot này, khi production
đang khỏe và không có deploy đang chạy, dùng:

```bash
cd /opt/goldcast
sh scripts/bootstrap-release.sh --refresh
```

Refresh chỉ thay con trỏ sau xác minh; thất bại giữ bản tốt cũ. Các snapshot
và `runtime.env` có thể chứa token/password, được ghi quyền 600. Image cũ chưa
có `/api/release` chỉ được adopt bằng chế độ `legacy-local-origin`: DNS HTTPS
phải trỏ trực tiếp vào IP thuộc VPS đó, frontend phải gọi backend nội bộ.
Chế độ này không hỗ trợ CDN/NAT; release mới luôn phải khớp định danh công khai.
Pipeline từ chối deploy khi baseline cũ chưa được refresh an toàn.
Sau khi chuyển sang release theo digest, thao tác Compose thủ công cũng phải dùng
`--env-file <release>/runtime.env --env-file <release>/release.env -p goldcast
-f <release>/docker-compose.prod.yml`; không dùng riêng `THE_TAG` cũ để cập nhật.

VPS phải kéo được image GHCR, và có Docker Compose
hỗ trợ `up --wait`. Jenkins cũng cần Compose hỗ trợ `--wait` và Buildx.

**Nếu máy chủ từng được cài lại**, container Jenkins vẫn nhớ host key cũ và
stage Deploy sẽ đỏ với `Host key verification failed`. Jenkinsfile dùng
`StrictHostKeyChecking=accept-new`, tên này dễ gây hiểu nhầm: nó chỉ tự chấp
nhận host **mới tinh**, còn host đã biết mà đổi khoá thì vẫn chặn. Xoá bản ghi
cũ, dùng đường dẫn tuyệt đối vì `docker exec` không chắc đặt `HOME`:

```bash
docker exec jenkins ssh-keygen -f /var/jenkins_home/.ssh/known_hosts -R $IP
```

Rồi kiểm bằng chính file đó, đừng tin vào lệnh vừa chạy:

```bash
docker exec jenkins ssh-keygen -F "$IP" -f /var/jenkins_home/.ssh/known_hosts
```

Không còn bản ghi thì lệnh không in host và trả mã 1.

**Kiểm:** đưa một thay đổi đã review vào `main` qua PR, **không bấm gì trong Jenkins**.
Trong 5 phút phải có build mới, và trang build ghi **"Started by an SCM change"**
chứ không phải "Started by user". Rồi:

```bash
ssh deploy@$IP 'cat /opt/goldcast/.last-good-release'
```

Đường dẫn phải chứa commit và số build vừa chạy; file `release.env` trong đó
phải khớp artifact Jenkins. Nhánh khác `main` vẫn chạy G1/build/G2 nhưng không deploy.

## B10. G1 → build → G2 → deploy → G3

- **G1:** chạy test Java (gồm hợp đồng API và công thức), TypeScript typecheck
  và bộ test release/rollback/recovery bằng `python:3.13-alpine`, test probe và
  snapshot bằng Node 22. Container test không được gắn Docker socket.
  GitHub Actions chạy cùng nhóm kiểm tra trong required check `backend-test`.
- **Build:** build/push một lần cho `linux/amd64`, chốt digest vào `release.env`.
- **G2:** `scripts/g2.sh` dựng `docker-compose.ci.yml` trên Docker của Jenkins,
  với project riêng cho build, DB rỗng riêng, không publish cổng, không dùng
  dữ liệu production. Flyway phải khởi tạo thành công; kiểm lịch sử migration,
  API, công thức, dự báo NAIVE và các trang tổng quan/lịch sử/dự báo. Dữ liệu
  tổng hợp được seed với nguồn bên ngoài tắt để kiểm thử lặp lại được.
  Sau ca cài mới, tạo lại DB và chạy image với Flyway target V1, ingest tắt;
  nạp `scripts/fixtures/upgrade-v1.sql` vào cả bốn bảng nghiệp vụ, rồi chạy
  cùng image ở target mới nhất. `assert-upgrade.sql` kiểm migration và dữ liệu
  cũ còn nguyên; smoke API và các trang cũng phải đạt sau nâng cấp.
  Log được in khi lỗi; container, mạng và dữ liệu tạm được dọn khi kết thúc.
- **Deploy:** chỉ `main`, dùng đúng hai digest đã qua G2, cấu hình release được
  truyền từ workspace Jenkins qua SSH; không `git pull` mã mới trên VPS.
- **G3:** đợi ứng dụng sẵn sàng, so image ID container với digest mong đợi,
  kiểm readiness backend và HTTPS qua Caddy đến các trang có nội dung thực.
  `/api/release` phải trả `releaseId` và `backendReleaseId` bằng `RELEASE_ID`
  trong manifest; endpoint thiếu, backend sai bản hoặc trang từ bản cũ đều lỗi.
  Chỉ khi tất cả đạt mới ghi nguyên tử `.last-good-release`.

Khi bắt đầu deploy, đường dẫn bản tốt trước đó được lưu cố định trong
`<release>/previous-good-release`, `.env` được giữ riêng trong `runtime.env`.
Nếu Deploy hoặc G3 lỗi, rollback dùng bản
đã lưu này, đợi sẵn sàng và xác minh lại; không lấy mục tiêu từ con trỏ
`.last-good-release` có thể đã thay đổi. Build vẫn đỏ sau rollback. Khóa trên
VPS ngăn các job khác deploy chồng nhau. Nếu rollback cũng lỗi, khóa được giữ
để người vận hành xử lý, không tự nhận bản lỗi là bản tốt.

Sau khi G3 đạt và `.last-good-release` đã cập nhật, lỗi dọn khóa chỉ phát cảnh
báo và không kích hoạt rollback. Nếu Jenkins gọi rollback do mất phản hồi SSH
sau thời điểm này, script nhận biết bản đã được ghi nhận thành công và bỏ qua.
Khi có cảnh báo dọn khóa hoặc build bị ngắt, dùng các lệnh phục hồi dưới đây.
Mỗi lần deploy lưu `deploy-started-at` (UTC), `deploy-build-url`, `deploy-phase`
và `previous-good-release` trong thư mục release.

Trên VPS, lấy đúng đường dẫn release từ build bị ngắt:

```bash
RELEASE_DIR='/opt/goldcast/releases/<commit>-<job>-<build>'
sh "$RELEASE_DIR/scripts/release.sh" status "$RELEASE_DIR"
```

Mở URL build, xác nhận build và lệnh SSH/deploy trên VPS đã dừng trước khi chạy:

```bash
sh "$RELEASE_DIR/scripts/release.sh" recover "$RELEASE_DIR" --confirm-stopped
```

Nếu bản mới đã được G3 ghi nhận, recovery xác minh lại rồi dọn khóa dư. Nếu
chưa được ghi nhận, recovery rollback theo bản trước đã lưu và xác minh lại.
Khóa thuộc release khác, thiếu mục tiêu rollback hoặc xác minh lỗi đều không
được tự xóa. Khóa thiếu owner trước lúc ghi nhận thành công cần kiểm tra thủ
công; script sẽ từ chối đoán release. Không phục hồi chỉ dựa vào tuổi của khóa.


**Kiểm:** console phải có kết quả G2 trước Deploy, G3 sau Deploy và artifact
`release.env`. Cố ý làm G2 lỗi thì Deploy bị bỏ qua và production giữ nguyên.
G3 lỗi thì `.last-good-release` giữ bản cũ và log có `Rollback verified` nếu
quay lui thành công.

**Giới hạn:** ca nâng cấp hiện dùng baseline V1 và dữ liệu mẫu, không phải bản
sao DB production hay tự động lấy image production trước đó. Khi schema phát
triển, cần bổ sung baseline/fixture cho các phiên bản còn hỗ trợ. Rollback chỉ
quay lui ứng dụng/cấu hình, không phục hồi DB;
migration phải tương thích với cả bản ứng dụng trước và sau. Kiểm trang dùng
HTTP và nội dung SSR, chưa thay thế kiểm thử thao tác trong trình duyệt.

---

# Khi nào thì biết là hỏng, và nhìn vào đâu

Bảng này là các lỗi thật đã gặp khi dựng hệ thống này, không phải lỗi giả định.

| Hiện tượng | Nguyên nhân | Chỗ nhìn |
|---|---|---|
| `mkswap: swap area needs to be at least 40 KiB` | image gốc có sẵn `/swapfile` rỗng 0 byte | `ls -la /swapfile` |
| Cấu hình sshd không có tác dụng dù file đã ghi | file trong `sshd_config.d` đọc theo thứ tự tên, và `50-cloud-init.conf` thắng | `sshd -T \| grep passwordauth` |
| `System clock synchronized: no` | NTP phân giải ra IPv6 mà máy không có IPv6 | `timedatectl timesync-status`, xem `Packet count` |
| `unauthorized` khi máy chủ kéo image | package GHCR mặc định private | trang Packages trên GitHub |
| Sửa Caddyfile mà không có gì đổi | bind mount không làm container nạp lại | dấu thời gian dòng đầu trong `docker logs caddy` |
| `No such DSL method 'sshagent'` | plugin chưa cài | chính thông báo lỗi liệt kê mọi bước đang có |
| Quét cổng báo mọi cổng đều đóng, kể cả 22 | cờ `nc -G` không được hỗ trợ | bỏ `2>/dev/null` để thấy lỗi thật |

Điểm chung của phần lớn những lỗi trên: **lệnh chạy xong, không báo lỗi, và
không làm gì cả.** Đó là lý do mỗi bước trong tài liệu này đều kèm một phép
kiểm nhìn vào *kết quả*, chứ không nhìn vào *mã thoát của lệnh*.
