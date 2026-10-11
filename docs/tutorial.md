# Tutorial: dựng quy trình DevOps cho GoldCast

Tài liệu này hướng dẫn dựng lại toàn bộ quy trình từ con số không: chạy ứng
dụng, chạy test, dựng CI, đưa lên internet, và để mỗi commit tự đi tới
production.

## Dành cho ai

Người biết lập trình và dùng được dòng lệnh, nhưng chưa từng dựng CI/CD.
Không cần biết Jenkins, Docker Compose hay Caddy từ trước.

## Hai phần, và vì sao chia

**Phần A chạy được trên máy bạn, không tốn đồng nào.** Ai cũng làm theo được.

**Phần B cần một máy chủ thuê và một tên miền** — khoảng 215.000đ/tháng và
19.000đ/năm. Nếu bạn chỉ muốn hiểu quy trình thì đọc là đủ; phần A đã có đầy đủ
code, build, test và cổng chất lượng.

Mỗi bước đều kết thúc bằng một **phép kiểm**. Đừng đi tiếp khi phép kiểm chưa
đạt — mọi lỗi tốn nhiều thời gian nhất trong đồ án này đều đến từ việc bỏ qua
một phép kiểm và phát hiện ra ba bước sau đó.

## Giá trị cụ thể của đồ án này

Thay bằng giá trị của bạn khi làm theo.

| Chỗ trống | Đồ án này dùng |
|---|---|
| `<REPO>` | `github.com/duongtanminh1912/goldcast` |
| `<IP>` | `103.175.248.236` |
| `<TEN_MIEN>` | `goldcast.click` |
| `<REGISTRY>` | `ghcr.io/duongtanminh1912` |

---

# Phần A — chạy trên máy bạn

## A1. Chuẩn bị

Cần Docker Desktop (hoặc Docker Engine) và Git. Không cần cài Java, Node hay
PostgreSQL — tất cả chạy trong container.

```bash
git clone https://github.com/<REPO>.git
cd goldcast
cp .env.example .env
```

**Kiểm:** `docker --version` và `docker compose version` đều ra số phiên bản.

## A2. Chạy toàn bộ ứng dụng

```bash
docker compose up -d
docker compose ps
```

Lần đầu mất vài phút vì phải build cả backend lẫn frontend.

**Kiểm:** bốn dòng trong `ps`, backend và db ở trạng thái `healthy`. Mở
`http://localhost:3000` thấy giao diện có dữ liệu.

Dữ liệu là **tổng hợp**, không phải giá vàng thật. Đồ án nghiên cứu quy trình,
không nghiên cứu độ chính xác của dự báo.

## A3. Chạy test, và xem cổng G1 chặn

```bash
cd backend
mvn -B test
```

**Kiểm:** `Tests run: 102, Failures: 0`.

Trong số đó có `ApiContractTest` — cổng chất lượng thứ nhất. Nó đối chiếu các
DTO Java với các kiểu TypeScript ở frontend, và đỏ khi hai bên lệch nhau.

Thử phá nó để thấy nó thật sự gác:

```bash
# doi ten mot truong trong types.ts
sed -i '' 's/  series:/  seriesX:/' ../frontend/src/lib/types.ts
mvn -B test -Dtest=ApiContractTest
```

**Kiểm:** test đỏ, và thông báo chỉ đúng tên trường bị lệch. Hoàn nguyên:

```bash
git checkout ../frontend/src/lib/types.ts
```

Bước này quan trọng hơn vẻ ngoài của nó: **một cổng chưa từng được chứng minh
là biết chặn thì chưa phải là cổng.**

## A4. Dựng Jenkins

```bash
cd ../jenkins
docker compose up -d
```

Mở `http://localhost:8081`. Lấy mật khẩu khởi tạo:

```bash
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Chọn "Install suggested plugins", tạo tài khoản quản trị.

**Kiểm:** đăng nhập được vào trang chủ Jenkins.

## A5. Pipeline đầu tiên

New Item → đặt tên → **Pipeline** → OK.

Trong phần Pipeline: chọn **Pipeline script from SCM**, SCM là **Git**, URL là
repo của bạn, Script Path là `Jenkinsfile`.

Trong Build Triggers, tick **Poll SCM** và điền `H/5 * * * *` — Jenkins sẽ hỏi
Git mỗi 5 phút xem có commit mới không.

Bấm **Build Now**.

**Kiểm:** build xanh, và trong Console Output thấy `Tests run: 102`.

Tới đây bạn đã có: ứng dụng chạy, test tự động, một cổng chất lượng, và một CI
server tự chạy khi có commit. Phần A hết.

---

# Phần B — đưa lên internet

## B1. Thuê máy chủ

Cấu hình tối thiểu đã đo được là đủ: **1 vCPU, 2 GB RAM, 20 GB đĩa**. Cả stack
dùng khoảng 531 MiB.

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

Máy bạn chưa có khoá thì tạo:

```bash
ssh-keygen -t ed25519 -C "goldcast" -f ~/.ssh/id_ed25519
ssh-copy-id root@<IP>
```

**Kiểm — đừng bỏ qua:**

```bash
ssh -o PasswordAuthentication=no root@<IP>
```

Vào được mà không hỏi mật khẩu máy chủ. Nếu bước này chưa đạt mà vẫn đi tiếp
tới B4, bạn sẽ tự khoá mình ra khỏi máy.

## B3. Dựng máy chủ bằng script

```bash
apt update && apt install -y git tmux
tmux new -s setup
git clone https://github.com/<REPO>.git
cd goldcast
cat deploy/dung-may-chu.sh
```

Đọc script trước khi chạy. Rồi:

```bash
bash deploy/dung-may-chu.sh
```

Chạy trong `tmux` để mất mạng giữa chừng script vẫn tiếp tục.

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
ssh deploy@<IP> 'sudo -n id && echo SUDO_OK'
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
ssh deploy@<IP> 'echo VAO_DUOC'
```

Ra `VAO_DUOC` thì mới đóng phiên root. Không ra thì phiên root vẫn còn, hoàn
tác bằng `rm /etc/ssh/sshd_config.d/01-goldcast.conf && systemctl reload ssh`.

## B5. Chuẩn bị chỗ cho ứng dụng

Sau khi khoá SSH, `/root` không với tới được nữa. Mã nguồn phải nằm ở chỗ
`deploy` sở hữu:

```bash
mkdir -p /opt/goldcast
chown deploy:deploy /opt/goldcast
sudo -u deploy git clone https://github.com/<REPO>.git /opt/goldcast
```

Tạo `.env` production. Bí mật sinh thẳng vào file, không qua màn hình:

```bash
cd /opt/goldcast
umask 077
cat > .env <<EOF
POSTGRES_DB=goldcast
POSTGRES_USER=goldcast
POSTGRES_PASSWORD=$(openssl rand -hex 24)
APP_ADMIN_TOKEN=$(openssl rand -hex 32)
APP_CORS_ALLOWED_ORIGINS=https://<TEN_MIEN>
EOF
chmod 600 .env
```

**Kiểm:** `ls -l .env` ra `-rw-------`, và `grep -c . .env` ra `5`. Đừng `cat`
file đó ra.

## B6. Build image cho đúng kiến trúc

Máy cá nhân Apple Silicon là `arm64`, máy chủ là `x86_64`. Image build mặc định
**không chạy được** trên máy chủ.

Kiểm máy bạn:

```bash
uname -m
```

Ra `arm64` thì phải build chéo:

```bash
docker login ghcr.io -u <TEN_GITHUB>
docker buildx build --platform linux/amd64 -t <REGISTRY>/goldcast-backend:latest --push ./backend
docker buildx build --platform linux/amd64 -t <REGISTRY>/goldcast-frontend:latest --push ./frontend
```

Token đăng nhập là Personal Access Token **classic** với scope `write:packages`.

Sau khi đẩy, package trên GHCR **mặc định private** kể cả khi repo công khai.
Vào `github.com/<TEN_GITHUB>?tab=packages`, mở từng package → Package settings
→ Change visibility → Public.

**Kiểm:** từ máy chủ kéo về được và đúng kiến trúc:

```bash
ssh deploy@<IP> 'docker pull <REGISTRY>/goldcast-backend:latest && docker image inspect <REGISTRY>/goldcast-backend:latest --format "{{.Architecture}}"'
```

Phải ra `amd64`.

## B7. Chạy stack trên máy chủ

```bash
ssh deploy@<IP>
docker compose -f /opt/goldcast/docker-compose.prod.yml up -d
docker compose -f /opt/goldcast/docker-compose.prod.yml ps
```

File compose production khác bản dev ở ba chỗ: không build mà chỉ kéo image, và
**không publish cổng nào** cho db/backend/frontend — chỉ Caddy publish 80/443.

**Kiểm — phép kiểm quan trọng nhất của cả phần B.** Từ một máy khác, không phải
từ máy chủ:

```bash
for p in 22 80 443 3000 5432 8080; do
  nc -z -w 2 <IP> "$p" >/dev/null 2>&1 && echo "$p MO" || echo "$p dong"
done
```

Phải thấy `3000 dong`, `5432 dong`, `8080 dong`. Nếu `5432 MO` thì cơ sở dữ liệu
đang phơi ra internet — dừng mọi thứ lại.

Lý do phải kiểm từ ngoài: **Docker ghi luật iptables vào chain `DOCKER-USER`,
nằm trước luật của ufw.** Một cổng được `ports:` trong compose sẽ ra internet
bất kể ufw nói gì. `docker compose ps` nhìn từ bên trong nên không thấy điều đó.

## B8. Tên miền và HTTPS

Mua tên miền, rồi thêm hai bản ghi A trỏ về `<IP>`:

| Loại | Tên | Giá trị | TTL |
|---|---|---|---|
| A | `@` | `<IP>` | 300 |
| A | `www` | `<IP>` | 300 |

**Kiểm, và chờ cho tới khi đạt:**

```bash
dig +short <TEN_MIEN>
dig +short www.<TEN_MIEN>
```

Cả hai phải ra `<IP>`. **Đừng đụng vào Caddy trước khi đạt** — Let's Encrypt
giới hạn 5 lượt xin chứng chỉ thất bại mỗi giờ cho một tên miền.

Rồi sửa dòng đầu `deploy/Caddyfile`:

```
<TEN_MIEN>, www.<TEN_MIEN> {
	encode gzip
	reverse_proxy frontend:3000
}
```

Commit, đẩy lên, rồi trên máy chủ:

```bash
git -C /opt/goldcast pull
docker compose -f /opt/goldcast/docker-compose.prod.yml restart caddy
```

**`restart` chứ không phải `up -d`.** Caddyfile là bind mount, không phải một
phần cấu hình container — compose thấy container không đổi nên sẽ không làm gì
cả, và lệnh vẫn trả về thành công.

**Kiểm:**

```bash
curl -I https://<TEN_MIEN>
curl -I http://<TEN_MIEN>
```

Cái đầu `HTTP/2 200`, cái sau `308` chuyển sang `https://`. Caddy tự xin chứng
chỉ và tự dựng chuyển hướng; không có dòng cấu hình TLS nào phải viết tay.

## B9. Jenkins tự deploy

Tạo khoá riêng cho Jenkins — **không dùng khoá cá nhân**, vì Jenkins chạy tự
động nên khoá không được có passphrase:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/jenkins_deploy -N "" -C "jenkins"
ssh-copy-id -i ~/.ssh/jenkins_deploy.pub deploy@<IP>
```

**Kiểm:**

```bash
ssh -i ~/.ssh/jenkins_deploy deploy@<IP> 'docker ps && echo OK'
```

Trong Jenkins, Manage Jenkins → Credentials → System → Global, thêm hai mục:

| Kind | ID | Nội dung |
|---|---|---|
| SSH Username with private key | `vps-deploy` | username `deploy`, khoá `~/.ssh/jenkins_deploy` |
| Username with password | `ghcr` | tên GitHub, token `write:packages` |

Lấy khoá vào clipboard mà không in ra màn hình: `pbcopy < ~/.ssh/jenkins_deploy`
(macOS) hoặc `xclip -sel clip < ~/.ssh/jenkins_deploy` (Linux).

`Jenkinsfile` trong repo đã có sẵn sáu stage. Hai chi tiết trong đó đáng chú ý:

**Đăng nhập registry phải dùng nháy đơn:**

```groovy
sh 'echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin'
```

Nháy đơn nên Groovy không nội suy; shell mới mở biến, và Jenkins che được thành
`****`. Dùng nháy kép thì token bị nhúng vào chuỗi lệnh trước khi Jenkins nhìn
thấy, và nó hiện nguyên trong log.

**Deploy theo số build, không theo `latest`:** pipeline ghi `THE_TAG=<số build>`
vào `.env` trên máy chủ. Nhờ đó trả lời được "đang chạy bản nào" bằng
`grep THE_TAG .env`, và rollback là sửa một dòng rồi `up -d`.

**Kiểm:** đẩy một commit bất kỳ lên `main`, **không bấm gì trong Jenkins**.
Trong 5 phút phải có build mới, và trang build ghi **"Started by an SCM change"**
chứ không phải "Started by user". Rồi:

```bash
ssh deploy@<IP> 'grep THE_TAG /opt/goldcast/.env'
```

Số phải khớp với số build vừa chạy.

## B10. Cổng G2

Stage cuối chạy `scripts/smoke.mjs` — cổng chất lượng thứ hai. Nó kiểm **nội
dung**, không kiểm trạng thái: `docker ps` nói `healthy` và `curl` nói `200` vẫn
có thể xanh trong khi dữ liệu sai hoàn toàn.

Script chạy **bên trong mạng compose** trên máy chủ, không phải từ Jenkins và
không phải từ internet. Lý do: Caddy định tuyến mọi thứ về frontend, nên
`/api/v1` không ra tới ngoài. Và vì Jenkins không cài Node, script chạy trong
một container `node:22-alpine` dùng xong là xoá.

**Kiểm:** Console Output của build có 18 dòng `OK`.

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
