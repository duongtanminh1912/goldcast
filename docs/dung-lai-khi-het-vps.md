# Dựng lại GoldCast khi hết hạn VPS

Dùng file này khi VPS đã hết hạn hoặc bị xoá, và cần dựng lại hệ thống trên
**một máy chủ mới, địa chỉ IP mới** — có thể cùng nhà cung cấp, có thể khác.

Khác với `docs/tutorial.md` ở đúng một điểm, nhưng điểm đó xuyên suốt cả quy
trình: tutorial giả định tên miền đã trỏ sẵn vào máy chủ. Ở đây IP đổi, nên
**phải sửa IP ở ba nơi** và phải chờ DNS trước khi chạm vào HTTPS.

Thời gian thực tế đo được cho lần dựng lại trên cùng IP là 27 phút. Lần này
cộng thêm thời gian chờ DNS, tính khoảng **40–50 phút**.

---

## 1. Những gì KHÔNG mất khi VPS chết

Đọc bảng này trước, để biết chỉ phải dựng lại đúng phần máy chủ.

| Thứ | Nằm ở đâu | Trạng thái |
|---|---|---|
| Mã nguồn, Jenkinsfile, các tệp compose | GitHub | nguyên |
| Image `goldcast-backend`, `goldcast-frontend` | GHCR | nguyên, mọi tag cũ còn đó |
| Jenkins: job, credentials, lịch sử build | volume Docker trên máy Mac | nguyên |
| Tên miền | nhà đăng ký | nguyên, chỉ cần sửa bản ghi A |
| Dữ liệu giá vàng | database trên VPS | mất, nhưng tự sinh lại khi rỗng |
| `.env` production | chỉ trên VPS | mất — không sao, sinh lại mật khẩu mới |

Không có gì cần sao lưu trước khi để VPS hết hạn. Đây là hệ quả của việc dữ
liệu trong hệ thống là dữ liệu tự sinh.

---

## 2. Trước khi để hết hạn — ba việc, 5 phút

Làm trước khi mất quyền truy cập, vì sau đó thì muộn:

1. **Ghi lại phiên bản đang chạy**, để biết sau này dựng lại có đúng bản đó
   không:
   ```
   ssh deploy@<IP-CU> 'grep THE_TAG /opt/goldcast/.env'
   ```

2. **Kiểm hai package trên GHCR đang để công khai.** Vào
   `github.com/<ten-github>?tab=packages`, mở từng package, Package settings,
   phần Danger Zone phải ghi là Public. Nếu để riêng tư thì máy chủ mới sẽ
   không kéo image được mà không có token.

3. **Kiểm `main` đang xanh.** Nếu `main` đang đỏ thì lúc dựng lại sẽ phải vừa
   dựng vừa sửa code, rất mất thời gian.

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
IP=<dien-ip-moi-vao-day>
TEN_MIEN=<dien-ten-mien-vao-day>
REPO=<ten-github>/goldcast
```

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

### 6.2 Sinh tệp bí mật

```bash
ssh deploy@$IP "umask 077; cd /opt/goldcast && \
  printf 'POSTGRES_DB=goldcast\nPOSTGRES_USER=goldcast\nPOSTGRES_PASSWORD=%s\nAPP_ADMIN_TOKEN=%s\nAPP_CORS_ALLOWED_ORIGINS=https://$TEN_MIEN\n' \
  \"\$(openssl rand -hex 24)\" \"\$(openssl rand -hex 32)\" > .env && \
  chmod 600 .env && ls -l .env && grep -c . .env"
```

Bí mật sinh thẳng trên máy chủ, không đi qua màn hình và không vào lịch sử
shell của máy cá nhân. Mật khẩu mới khác mật khẩu cũ — không sao, database cũng
mới.

**Kiểm:** ra `-rw-------` và số `5`. Đừng `cat` tệp đó ra.

### 6.3 Chờ DNS xong rồi mới chạm vào Caddy

```bash
dig +short $TEN_MIEN
dig +short www.$TEN_MIEN
```

**Cả hai phải ra đúng IP mới.** Chưa ra thì chờ, kiểm lại sau vài phút.

Đây là phép kiểm rẻ nhất trong cả quy trình và nó đứng chắn trước thứ đắt nhất:
Let's Encrypt đếm số lần xin chứng chỉ thất bại, và giới hạn số chứng chỉ trùng
hệt một bộ tên miền trong 7 ngày là khoảng 5 lần. Chạm vào Caddy khi DNS chưa
đúng là đốt hạn mức vô ích.

### 6.4 Kéo image và dựng stack

```bash
ssh deploy@$IP 'cd /opt/goldcast && docker compose -f docker-compose.prod.yml pull'
```

Báo `denied` hoặc `unauthorized` nghĩa là package trên GHCR đang để riêng tư —
quay lại mục 2 việc số 2.

```bash
ssh deploy@$IP 'cd /opt/goldcast && docker compose -f docker-compose.prod.yml up -d && \
  echo "--- cho 45 giay ---" && sleep 45 && docker compose -f docker-compose.prod.yml ps'
```

Chờ 45 giây ngay trong lệnh, vì backend cần khoảng 32 giây mới chuyển sang
`healthy`.

**Kiểm:** bốn container `db`, `backend` (healthy), `frontend`, `caddy`. Ở cột
cổng, **chỉ `caddy` được có `0.0.0.0:80` và `0.0.0.0:443`**. Ba cái kia không
được có gì cả.

### 6.5 Kiểm từ bên ngoài

```bash
curl -I https://$TEN_MIEN; curl -I http://$TEN_MIEN
```

Cái đầu `HTTP/2 200`, cái sau `308` chuyển sang https.

```bash
for p in 22 80 443 3000 5432 8080; do
  nc -z -w 2 $IP "$p" >/dev/null 2>&1 && echo "$p MO" || echo "$p dong"
done
```

Đúng ba cổng mở: 22, 80, 443. Ba cổng kia đóng. Phải quét từ máy cá nhân chứ
không phải từ máy chủ — Docker ghi luật iptables vào chuỗi `DOCKER-USER` nằm
trước luật của ufw, nên nhìn từ bên trong sẽ không thấy cổng nào bị hở.

---

## 7. Nối lại Jenkins — ba chỗ phải sửa IP

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
docker exec jenkins ssh-keygen -f /var/jenkins_home/.ssh/known_hosts -R <ip-cu>
```

Dùng đường dẫn tuyệt đối, vì `docker exec` không chắc đặt biến `HOME`. Không
thêm `|| true` — lệnh hỏng thì phải thấy nó hỏng.

**Kiểm bằng chính tệp đó, đừng tin vào lệnh vừa chạy:**

```bash
docker exec jenkins sh -c 'grep -c <ip-cu> /var/jenkins_home/.ssh/known_hosts; echo "ma thoat: $?"'
```

Phải ra `0` và `ma thoat: 1`.

### 7.4 Chạy thử toàn tuyến

Mở Jenkins, bấm **Build Now**. Sáu chặng phải xanh hết. Rồi:

```bash
ssh deploy@$IP 'grep THE_TAG /opt/goldcast/.env'
```

Số phải khớp với số build vừa chạy. Đó là lúc dây chuyền khép kín trở lại.

---

## 8. Bảng sự cố thường gặp

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| `REMOTE HOST IDENTIFICATION HAS CHANGED` | Khoá định danh máy chủ đổi | `ssh-keygen -R $IP` trên máy cá nhân |
| Chặng Deploy đỏ, `Host key verification failed` | Container Jenkins còn nhớ khoá cũ | Mục 7.3 |
| `docker compose pull` báo `denied` | Package GHCR đang riêng tư | Đổi sang Public trong Package settings |
| `curl https://...` lỗi TLS | DNS chưa trỏ đúng, hoặc Caddy bị chạm trước khi DNS xong | `dig +short $TEN_MIEN` trước; **đừng thử lại nhiều lần**, mỗi lần hỏng là một lượt bị đếm |
| Sửa `Caddyfile` rồi mà không thấy đổi | `up -d` không làm gì vì Caddyfile là bind mount | `docker compose -f docker-compose.prod.yml restart caddy` |
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
- [ ] Jenkins Build Now xanh cả sáu chặng
- [ ] `grep THE_TAG /opt/goldcast/.env` khớp số build vừa chạy
- [ ] Ghi lại IP mới vào nhật ký dự án, và sửa IP trong `docs/tutorial.md` nếu
      tài liệu đó còn ghi IP cũ
