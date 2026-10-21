# Dựng lại GoldCast khi hết hạn VPS

Dùng file này khi VPS đã hết hạn hoặc bị xoá, và cần dựng lại hệ thống trên
**một máy chủ mới, địa chỉ IP mới** — có thể cùng nhà cung cấp, có thể khác.

Luồng release hiện tại dùng **digest đã qua G2**, cấu hình theo từng release
và `.last-good-release` chỉ được ghi sau xác minh. Khôi phục phải giữ các thông
tin đó, không lấy `latest` hoặc `THE_TAG` để đoán phiên bản.

Lần dựng lại pipeline cũ trên cùng IP mất 27 phút theo nhật ký. Chưa đo thời
lượng khôi phục theo luồng release mới; thời gian chờ DNS còn tuỳ môi trường.

---

## 1. Những gì KHÔNG mất khi VPS chết

Đọc bảng này trước, để biết chỉ phải dựng lại đúng phần máy chủ.

| Thứ | Nằm ở đâu | Trạng thái |
|---|---|---|
| Mã nguồn, Jenkinsfile, các tệp compose | GitHub | nguyên |
| Image `goldcast-backend`, `goldcast-frontend` | GHCR | chỉ phục hồi được nếu digest cần dùng vẫn còn và VPS kéo được |
| Jenkins: job, credentials, lịch sử build | volume Docker trên máy Mac | nguyên |
| Tên miền | nhà đăng ký | nguyên, chỉ cần sửa bản ghi A |
| Dữ liệu giá và dự báo đã lưu | database trên VPS | mất nếu không có backup; lịch sử nguồn thật không được seed tái tạo |
| Manifest và cấu hình release đang chạy | `/opt/goldcast/releases/` trên VPS | mất nếu chưa sao lưu; artifact Jenkins chỉ chứa `release.env` |
| `.env` production | trên VPS | cần dựng lại cấu hình và bí mật; không đưa vào Git |

Giữ backup bên ngoài VPS. Seed mô phỏng là lựa chọn cho môi trường demo,
không phải bản sao của dữ liệu thị trường hoặc database cũ.

---

## 2. Trước khi để hết hạn — giữ release và dữ liệu

Có thể dùng script backup tự kiểm checksum/restore và job lịch riêng trong
[vận hành production](van-hanh-production.md#4-backup-ngoài-vps). Các lệnh
thủ công dưới đây vẫn dùng được; không thay thế bằng chứng restore bằng việc
chỉ kiểm file dump có dung lượng.

Trên máy cá nhân, gán `IP_CU` bằng IP VPS cũ rồi chạy:

```bash
IP_CU=103.175.248.236
BACKUP_DIR="$HOME/goldcast-backup-$(date -u +%Y%m%dT%H%M%SZ)"
umask 077
mkdir -p "$BACKUP_DIR"
ssh deploy@$IP_CU 'set -eu
  cd /opt/goldcast
  release=$(cat .last-good-release)
  case "$release" in /opt/goldcast/releases/*) ;; *) exit 1;; esac
  set -- release.env docker-compose.prod.yml deploy/Caddyfile scripts/release.sh scripts/check-pages.mjs
  for file in runtime.env legacy-local-origin; do
    if [ -f "$release/$file" ]; then set -- "$@" "$file"; fi
  done
  tar -cf - -C "$release" "$@"
' > "$BACKUP_DIR/release.tar"
tar -tf "$BACKUP_DIR/release.tar"
```

Archive chứa manifest, cấu hình và `runtime.env` nếu có, không chứa con trỏ
hay khóa cũ. Snapshot runtime có thể chứa bí mật; giữ archive quyền riêng như
bản DB dump, không đưa vào Git hoặc artifact công khai.
Nếu chưa bootstrap, không có `.last-good-release`: xem phần chuyển production
cũ trong [tutorial](tutorial.md#b9-jenkins-tự-deploy), xác minh baseline trước.
Baseline đời cũ cần `bootstrap-release.sh --refresh` khi production khỏe,
đã dừng deploy; không xóa con trỏ bằng tay để vượt kiểm tra.

Backup DB (lệnh dưới dùng tên user/database mặc định `goldcast`; đổi nếu cấu
hình khác). Thực hiện khi không có deploy/migration đang chạy:

```bash
ssh deploy@$IP_CU 'docker exec goldcast-db pg_dump -U goldcast -d goldcast -Fc' > "$BACKUP_DIR/database.dump"
test -s "$BACKUP_DIR/database.dump"
```

Lưu riêng cấu hình vận hành cần giữ và bí mật trong nơi lưu bí mật của bạn.
Kiểm hai digest trong archive còn kéo được từ GHCR. Nếu package private,
VPS mới cần được đăng nhập registry bằng token có quyền đọc package.

**Nếu VPS đã mất và chưa có archive:** lấy `release.env` từ một build Jenkins
đã đạt G2, cùng commit của build đó. Với bản từng chạy production, ưu tiên build
có log G3 thành công. Từ checkout local chứa commit tương ứng:

```bash
# Gán SHA đầy đủ của build đã chọn; tải artifact release.env vào Downloads trước.
RESTORE_COMMIT='SHA_CUA_BUILD_DA_CHON'
BACKUP_DIR="$HOME/goldcast-recovery"
umask 077
mkdir -p "$BACKUP_DIR/candidate"
git archive "$RESTORE_COMMIT" docker-compose.prod.yml deploy/Caddyfile scripts/release.sh scripts/check-pages.mjs |
  tar -xf - -C "$BACKUP_DIR/candidate"
cp "$HOME/Downloads/release.env" "$BACKUP_DIR/candidate/release.env"
tar -cf "$BACKUP_DIR/release.tar" -C "$BACKUP_DIR/candidate" release.env docker-compose.prod.yml deploy/Caddyfile scripts/release.sh scripts/check-pages.mjs
```

Không có DB backup thì không thể tuyên bố đã phục hồi lịch sử cũ. Không dùng
job xanh với Production `SKIPPED` làm bằng chứng G3 của production đã thành công.
Artifact Jenkins không chứa `runtime.env`; nếu chỉ có artifact, cấu hình bí
mật phải lấy từ nơi lưu bí mật của bạn. Bản mới cần `RELEASE_ID` cùng hai digest.

---

## 3. Thuê máy mới

Ba yêu cầu bắt buộc, thiếu một cái là không chạy được:

- **Ảo hoá KVM**, không phải OpenVZ — Docker cần KVM
- **IPv4 public riêng**, không phải NAT dùng chung
- **Ubuntu 24.04 LTS** trong danh sách hệ điều hành

Cấu hình tối thiểu đã đo: **1 vCPU, 2 GB RAM, 20 GB đĩa**. Cả stack dùng khoảng
531 MiB, đĩa dùng khoảng 6,4 GB sau vài ngày chạy.

Khi chọn image, lấy đúng bản `ubuntu-24.04-x86_64`. Không lấy bản `desktop` (có
giao diện đồ hoạ, phí RAM) và không lấy các bản cài sẵn phần mềm như `-n8n`,
`-ezyplatform` — chúng thường đã chiếm cổng 80 và 443.

Đặt mật khẩu root và **lưu ra ngoài trình duyệt ngay**. Mất nó là mất đường vào
vì chưa có khoá nào trên máy mới.

---

## 4. Đổi DNS trước, vì nó cần thời gian lan truyền

Làm bước này **ngay sau khi có IP mới**, trước cả khi SSH vào máy. DNS cần vài
phút để lan, nên cứ đổi trước rồi làm việc khác trong lúc chờ.

Vào trang quản lý tên miền, sửa hai bản ghi A:

| Loại | Tên | Giá trị | TTL |
|---|---|---|---|
| A | `@` | IP mới | 300 |
| A | `www` | IP mới | 300 |

Sửa chứ không thêm. Nếu để sót bản ghi A cũ trỏ vào IP đã chết thì tên miền sẽ
lúc vào được lúc không, và Caddy có thể xin chứng chỉ thất bại.

---

## 5. Dựng máy chủ

Đặt biến cho đỡ gõ nhầm. **Gán lại mỗi khi mở terminal mới:**

```bash
IP='DIA_CHI_IP_MOI'
TEN_MIEN=goldcast.click
REPO=duongtanminh1912/goldcast
```

Thay `DIA_CHI_IP_MOI` trước khi chạy. Hướng dẫn này giữ domain `goldcast.click`;
nếu đổi domain, phải đổi cả Caddyfile và URL HTTPS trong `scripts/release.sh`.

### 5.1 Xoá dấu vân tay cũ và đẩy khoá lên

Máy mới có khoá định danh mới. Nếu IP mới tình cờ từng dùng trước đây thì SSH
sẽ từ chối; xoá trước cho chắc:

```bash
ssh-keygen -R $IP
```

Máy đã có khoá SSH rồi thì **bỏ qua lệnh tạo khoá**. Chỉ tạo khi chưa có:

```bash
ls -l ~/.ssh/id_ed25519 || ssh-keygen -t ed25519 -C "goldcast" -f ~/.ssh/id_ed25519
```

Đẩy khoá công khai lên:

```bash
ssh-copy-id -i ~/.ssh/id_ed25519.pub root@$IP
```

`-i` là bắt buộc. Thiếu nó, lệnh sẽ đẩy **mọi** khoá công khai trong `~/.ssh`
lên tài khoản root, gồm cả khoá dành riêng cho Jenkins.

**Kiểm:**

```bash
ssh -o PasswordAuthentication=no root@$IP 'hostname; grep PRETTY_NAME /etc/os-release'
```

Phải vào được mà không hỏi mật khẩu, và phải thấy `Ubuntu 24.04`. Ra phiên bản
khác thì dừng lại, cài lại hệ điều hành — các kịch bản viết cho 24.04.

### 5.2 Cài công cụ và lấy mã nguồn

Chạy từ máy cá nhân, lệnh tự mang theo đích đến nên không lo dán nhầm cửa sổ:

```bash
ssh root@$IP "apt-get update -qq && apt-get install -y -qq git && \
  git clone https://github.com/$REPO.git /root/goldcast && ls /root/goldcast/deploy"
```

Phải thấy `Caddyfile`, `dung-may-chu.sh`, `siet-ssh.sh`.

### 5.3 Chạy kịch bản dựng máy

```bash
ssh root@$IP 'bash /root/goldcast/deploy/dung-may-chu.sh 2>&1 | tee /root/dung-may-chu.log'
```

Khoảng 2–10 phút, phần lớn là cài Docker. Kịch bản làm sáu việc: đặt múi giờ,
tạo swap 2 GB, cài Docker, tạo tài khoản `deploy`, mở tường lửa cho 22/80/443,
rồi in lại kết quả.

**Kiểm — sáu dòng cuối phải cho thấy:** Docker có số phiên bản, Compose có số
phiên bản, swap `2.0Gi`, tài khoản `deploy` thuộc nhóm `docker`, tường lửa
active và chỉ mở 22/80/443.

### 5.4 Cấp quyền quản trị cho `deploy`

```bash
ssh root@$IP "usermod -aG sudo deploy && \
  echo 'deploy ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/deploy && \
  chmod 440 /etc/sudoers.d/deploy && visudo -c"
```

**Kiểm, từ máy cá nhân:**

```bash
ssh deploy@$IP 'sudo -n id && echo SUDO_OK'
```

Phải ra `uid=0(root)` kèm `SUDO_OK`. **Chưa ra thì tuyệt đối không đi tiếp** —
bước sau sẽ tắt đăng nhập root, và nếu `deploy` chưa vào được thì không còn
đường nào vào máy.

### 5.5 Siết SSH — bước nguy hiểm nhất

Cần **hai cửa sổ**. Cửa sổ A giữ một phiên root đang mở làm đường lui.

Cửa sổ A:

```bash
ssh root@$IP
TOI_DA_KIEM_KHOA=yes bash /root/goldcast/deploy/siet-ssh.sh
```

Phải thấy **bốn dòng `ok`**. Kịch bản tự hỏi lại tiến trình `sshd` đang chạy
chứ không tin vào việc tệp cấu hình đã được ghi.

Cửa sổ B (mở mới, vẫn trên máy cá nhân):

```bash
ssh deploy@$IP 'echo VAO_DUOC'
```

Ra `VAO_DUOC` thì mới được đóng cửa sổ A.

Không ra thì **đừng đóng cửa sổ A**, gõ vào đó để hoàn tác:

```bash
rm /etc/ssh/sshd_config.d/01-goldcast.conf && systemctl reload ssh
```

Kể cả mất cả hai cửa sổ vẫn chưa chết: bảng điều khiển của nhà cung cấp luôn có
console VNC vào thẳng máy, không qua SSH.

---

## 6. Dựng ứng dụng

Từ đây root không SSH vào được nữa, mọi thứ đi qua `deploy` và `sudo`.

### 6.1 Tạo chỗ và lấy mã nguồn

```bash
ssh deploy@$IP "sudo mkdir -p /opt/goldcast && sudo chown deploy:deploy /opt/goldcast && \
  git clone https://github.com/$REPO.git /opt/goldcast && ls /opt/goldcast"
```

### 6.2 Sinh tệp bí mật trên máy mới

```bash
ssh deploy@$IP "umask 077; cd /opt/goldcast && \
  printf 'POSTGRES_DB=goldcast\nPOSTGRES_USER=goldcast\nPOSTGRES_PASSWORD=%s\nAPP_ADMIN_TOKEN=%s\nAPP_CORS_ALLOWED_ORIGINS=https://$TEN_MIEN\nAPP_INGEST_ENABLED=true\nAPP_INGEST_SEED_ON_EMPTY=false\n' \
  \"\$(openssl rand -hex 24)\" \"\$(openssl rand -hex 32)\" > .env && \
  chmod 600 .env && ls -l .env && grep -c . .env"
```

Bí mật sinh thẳng trên máy chủ, không đi qua màn hình và không vào lịch sử
shell của máy cá nhân. Mật khẩu mới khác mật khẩu cũ — không sao, database cũng
mới. Khi restore DB dump, user/database cần khớp với bản dump. Không ghi đè `.env`
theo lệnh này nếu đang phục hồi trên một database volume đã có dữ liệu.

**Kiểm:** ra `-rw-------` và số `7`. Đừng `cat` tệp đó ra. Nếu chủ động dựng
demo không có DB backup, có thể đặt seed thành `true` trước lúc chạy; dữ liệu
được sinh là mô phỏng. Tắt seed không xoá dữ liệu mô phỏng có sẵn.

### 6.3 Chờ DNS xong rồi mới chạm vào Caddy

```bash
dig +short $TEN_MIEN
dig +short www.$TEN_MIEN
```

**Cả hai phải ra đúng IP mới.** Chưa ra thì chờ, kiểm lại sau vài phút.

Chờ DNS đúng để tránh lỗi cấp chứng chỉ và các lần thử lại không cần thiết.

### 6.4 Phục hồi đúng manifest và database

Từ máy cá nhân, dùng `BACKUP_DIR` đã tạo ở mục 2:

```bash
scp "$BACKUP_DIR/release.tar" deploy@$IP:/opt/goldcast/release-restore.tar
# Chỉ chạy dòng sau khi có bản dump cần phục hồi.
scp "$BACKUP_DIR/database.dump" deploy@$IP:/opt/goldcast/database.dump
ssh deploy@$IP
```

Các lệnh tiếp theo chạy trên VPS mới:

```bash
cd /opt/goldcast
RELEASE_DIR="/opt/goldcast/releases/restore-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$RELEASE_DIR"
tar -xf release-restore.tar -C "$RELEASE_DIR"
if [ -f "$RELEASE_DIR/runtime.env" ]; then
  cp "$RELEASE_DIR/runtime.env" /opt/goldcast/.env
  chmod 600 /opt/goldcast/.env
fi
grep -Ec '^(BACKEND|FRONTEND)_IMAGE=[^[:space:]]+@sha256:[a-f0-9]{64}$' "$RELEASE_DIR/release.env"
release_compose() {
  docker compose --env-file /opt/goldcast/.env --env-file "$RELEASE_DIR/release.env" \
    -p goldcast -f "$RELEASE_DIR/docker-compose.prod.yml" "$@"
}
release_compose pull
release_compose up -d --wait --wait-timeout 300 db
```

Phép kiểm manifest phải ra `2`. Nếu kéo image thất bại, xử lý quyền GHCR hoặc
digest đã bị xoá; không thay bằng `latest`. Nếu có dump, khôi phục vào **DB mới
vừa tạo**, trước khi chạy backend:

```bash
docker exec -i goldcast-db pg_restore -U goldcast -d goldcast \
  --no-owner --no-privileges --exit-on-error < /opt/goldcast/database.dump
```

Không có dump thì bỏ qua lệnh `pg_restore`. Với seed tắt, cần provider nạp đủ
lịch sử trước khi các trang dự báo vượt kiểm tra. Sau đó:

```bash
release_compose up -d --wait --wait-timeout 300
release_compose ps
```

Project phải là `goldcast` để dùng đúng volume `goldcast_goldcast-pgdata`.
Chờ bằng healthcheck, không dựa vào một khoảng `sleep` cố định.

Với archive runtime, bản `.env` vừa sinh ở 6.2 được thay bằng cấu hình đã lưu
để mật khẩu DB và các biến khớp snapshot. Chỉ làm trên máy mới trước khi tạo
DB; không dùng thao tác này để đổi mật khẩu một volume đang có dữ liệu.

**Kiểm:** bốn container `db`, `backend` (healthy), `frontend`, `caddy`. Ở cột
cổng, **chỉ `caddy` được có `0.0.0.0:80` và `0.0.0.0:443`**. Ba cái kia không
được có gì cả.

### 6.5 Kiểm từ bên ngoài

Giữ phiên VPS ở mục 6.4 mở. Dùng terminal khác trên máy cá nhân, gán lại
`IP` và `TEN_MIEN` rồi kiểm:

```bash
curl -I https://$TEN_MIEN; curl -I http://$TEN_MIEN
```

Cái đầu `HTTP/2 200`, cái sau `308` chuyển sang https.

```bash
for p in 22 80 443 3000 5432 8080; do
  nc -z -w 2 $IP "$p" >/dev/null 2>&1 && echo "$p MO" || echo "$p dong"
done
```

Đúng ba cổng mở: 22, 80, 443. Ba cổng kia đóng. Phải quét từ máy cá nhân;
các cổng Docker publish có thể đi qua đường xử lý khác với luật ufw.

### 6.6 Xác minh và ghi baseline mới

Trở lại phiên VPS ở mục 6.4, vẫn còn biến `RELEASE_DIR`:

```bash
sh "$RELEASE_DIR/scripts/release.sh" bootstrap "$RELEASE_DIR"
test "$(cat /opt/goldcast/.last-good-release)" = "$RELEASE_DIR"
sh "$RELEASE_DIR/scripts/release.sh" status "$RELEASE_DIR"
```

Bootstrap so image ID/digest, readiness và HTTPS qua các trang thật trước khi
ghi con trỏ. Release mới phải khớp `RELEASE_ID` frontend/backend qua URL công
khai. Archive legacy giữ marker `legacy-local-origin`, cần DNS trỏ trực tiếp
vào VPS. Nó không restart stack. Nếu lỗi, chưa có baseline; sửa nguyên
nhân và kiểm lại trước khi nối Jenkins. Không tự tạo `.last-good-release`.

Nếu archive dùng script đời cũ chưa có snapshot/định danh, cập nhật checkout
chứa script mới rồi chạy `sh /opt/goldcast/scripts/bootstrap-release.sh --refresh`
trên production vừa phục hồi, trước khi chạy pipeline mới. Lệnh kiểm lại
runtime và chỉ thay baseline sau khi đạt; không restart stack.

---

## 7. Nối lại Jenkins sau khi baseline đạt

Đây là phần khác hẳn tutorial, và là chỗ dễ quên nhất.

### 7.1 Đẩy khoá Jenkins lên tài khoản `deploy`

```bash
ls -l ~/.ssh/jenkins_deploy* && ssh-copy-id -i ~/.ssh/jenkins_deploy.pub deploy@$IP
```

**Kiểm — và phải có `IdentitiesOnly=yes`:**

```bash
ssh -i ~/.ssh/jenkins_deploy -o IdentitiesOnly=yes deploy@$IP 'docker ps && echo JENKINS_KEY_OK'
```

Thiếu `IdentitiesOnly=yes`, SSH sẽ thử lần lượt mọi khoá trong `~/.ssh` và rất
có thể vào được bằng khoá cá nhân — phép kiểm báo đạt mà không chứng minh được
điều cần chứng minh.

Nếu `~/.ssh/jenkins_deploy` đã mất, tạo cặp mới rồi cập nhật credential
`vps-deploy` trong Jenkins:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/jenkins_deploy -N "" -C "jenkins"
ssh-copy-id -i ~/.ssh/jenkins_deploy.pub deploy@$IP
pbcopy < ~/.ssh/jenkins_deploy
```

Rồi Manage Jenkins → Credentials → `vps-deploy` → Update → dán khoá riêng vào.

### 7.2 Sửa IP trong Jenkinsfile

Trong `Jenkinsfile`, sửa dòng:

```groovy
VPS = 'deploy@<ip-cu>'
```

thành IP mới. Commit qua pull request như bình thường.

### 7.3 Xoá khoá định danh cũ trong container Jenkins

```bash
docker exec jenkins ssh-keygen -f /var/jenkins_home/.ssh/known_hosts -R "$IP_CU"
```

Dùng đường dẫn tuyệt đối, vì `docker exec` không chắc đặt biến `HOME`. Không
thêm `|| true` — lệnh hỏng thì phải thấy nó hỏng.

**Kiểm bằng chính tệp đó, đừng tin vào lệnh vừa chạy:**

```bash
docker exec jenkins ssh-keygen -F "$IP_CU" -f /var/jenkins_home/.ssh/known_hosts
```

Gán lại `IP_CU` trong terminal nếu cần. Không còn bản ghi thì lệnh `-F` không
in host và trả mã 1. Chỉ xóa bản ghi cũ khi đã xác nhận thay máy/host key.

### 7.4 Chạy thử toàn tuyến

Sau khi đã chủ động cho phép deploy VPS mới, chạy job checkout **`main`**.
Kiểm G1, Build, G2, Deploy và G3 đạt; Production không được `SKIPPED`. Rồi:

```bash
ssh deploy@$IP 'cat /opt/goldcast/.last-good-release'
```

Đường dẫn phải chứa commit và số build vừa chạy. Hai digest trong
`<release>/release.env` phải khớp artifact build đó. Chỉ sau G3 con trỏ mới đổi.
Lưu log và thời lượng thực đo; không dùng build feature chỉ đạt G1/G2 để kết luận
đã deploy. Nếu build ngắt để lại khóa, dùng `status`/`recover` trong tutorial,
không tự xoá khóa theo tuổi.

---

## 8. Bảng sự cố thường gặp

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| `REMOTE HOST IDENTIFICATION HAS CHANGED` | Khoá định danh máy chủ đổi | `ssh-keygen -R $IP` trên máy cá nhân |
| Chặng Deploy đỏ, `Host key verification failed` | Container Jenkins còn nhớ khoá cũ | Mục 7.3 |
| `docker compose pull` báo `denied` | Package GHCR đang riêng tư | Đổi sang Public trong Package settings |
| `curl https://...` lỗi TLS | DNS chưa trỏ đúng, hoặc Caddy bị chạm trước khi DNS xong | `dig +short $TEN_MIEN` trước; **đừng thử lại nhiều lần**, mỗi lần hỏng là một lượt bị đếm |
| Sửa `Caddyfile` ở checkout mà production không đổi | Production dùng cấu hình trong release đã chốt | Tạo release mới qua pipeline; kiểm đúng đường dẫn Caddyfile của release |
| Kịch bản chết ở `mkswap` | `/swapfile` rỗng 0 byte của image gốc | Bản vá hiện tại đã xử lý; nếu vẫn chết, `rm /swapfile` rồi chạy lại kịch bản |
| `nc` báo mọi cổng đóng, kể cả 22 | Dùng nhầm cờ `-G` | Dùng `-w 2` |
| `timedatectl` báo `System clock synchronized: no` | NTP chưa đồng bộ | Giờ vẫn đúng thì bỏ qua; nếu đồng hồ trôi thì Let's Encrypt sẽ từ chối |

---

## 9. Danh sách kiểm cuối

Đánh dấu từng dòng, không bỏ qua dòng nào:

- [ ] `ssh -o PasswordAuthentication=no deploy@$IP` vào được, không hỏi mật khẩu
- [ ] `sshd -T` trên máy chủ cho thấy `permitrootlogin no` và `passwordauthentication no`
- [ ] Bốn container chạy, `db` và `backend` ở trạng thái healthy
- [ ] Quét cổng từ máy khác: chỉ 22, 80, 443 mở
- [ ] `curl -I https://$TEN_MIEN` ra `HTTP/2 200`
- [ ] `curl -I http://$TEN_MIEN` ra `308`
- [ ] Baseline đã được xác minh; không tự khôi phục khóa hoặc con trỏ cũ
- [ ] Build `main` đạt G1/G2/Deploy/G3, Production không bị bỏ qua
- [ ] `.last-good-release` trỏ release vừa đạt G3, manifest khớp artifact Jenkins
- [ ] DB backup đã được phục hồi, hoặc đã ghi rõ đây là DB mới/dữ liệu demo
- [ ] Ghi lại IP mới vào nhật ký dự án, và sửa IP trong `docs/tutorial.md` nếu
      tài liệu đó còn ghi IP cũ
