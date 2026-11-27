# May thu dung mot lan, giong VPS Ubuntu vua thue: co openssh-server, python3,
# python3-apt va ca-certificates (co san tren Ubuntu Server; --check can chung),
# va 50-cloud-init.conf bat dang nhap mat khau nhu image cua nha cung cap.
# Giu danh sach apt nhu VPS that: --check khong chay apt-get update.
# Khong co systemd: sshd chay lam PID 1, check.sh gui SIGHUP thay cho reload.
ARG UBUNTU_IMAGE
FROM ${UBUNTU_IMAGE}
RUN apt-get update -qq \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends openssh-server python3 python3-apt ca-certificates \
 && printf 'PasswordAuthentication yes\n' > /etc/ssh/sshd_config.d/50-cloud-init.conf \
 && install -d -m 755 /run/sshd \
 && install -d -m 700 /root/.ssh
CMD ["/usr/sbin/sshd", "-D", "-e"]
