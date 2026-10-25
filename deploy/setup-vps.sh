#!/bin/sh
# setup-vps.sh — VPS 一次性 bootstrap (Ubuntu/Debian) — saas-identity-platform-kotlin
#
# 用法:
#   sudo sh deploy/setup-vps.sh saas-kotlin.example.com [cert-basename]
#
# 同一台 VPS 上要先跑家族其他仓的 setup-vps.sh（如果同时托管多个服务）。
# 本脚本只负责 kotlin 部分: 目录, deploy 用户, 不同 default_server。
#
# kotlin 容器需要 PostgreSQL 远程连接, 但本脚本**不**生成 kotlin.env（避免把
# 数据库密码写进 setup）：kotlin.env 由 deploy 脚本首启自举时从环境
# DATABASE_URL/JWT_SIGNING_KEY 读入。本脚本只保证:
#   1. apt 装 nginx、docker (如未装, 幂等)
#   2. 创建 deploy 用户 (key-only SSH) + 加进 docker 组
#   3. 建 /home/deploy/saas-identity-platform-kotlin/
#   4. 渲染 deploy/nginx-vps.conf.example → /etc/nginx/sites-available/$DOMAIN
#   5. 启用 sites-enabled symlink; 删 Ubuntu 默认页避免 default_server 冲突
#   6. nginx -t && reload + deploy 用户 sudoers 白名单（nginx/systemctl/!requiretty）
#
# 你**还要做**的（不在脚本里）:
#   a) 把 .crt / .key 放到 /etc/nginx/ssl/xiangru-uk.{cert,key}（复用家族通配 cert 则跳过）
#   b) 本地跑: ssh-copy-id -i ~/.ssh/id_ed25519.pub deploy@VPS（家族任一仓已做则跳过）
#   c) GitHub Repository Secrets 加: DOCKER_USERNAME / DOCKER_PASSWORD / VPS_HOST /
#      VPS_USER / VPS_SSH_KEY / DATABASE_URL / JWT_SIGNING_KEY；
#      vars 加 NGINX_DOMAIN / NGINX_CERT_BASENAME
#   d) 首次 deploy 由 CI 自举 kotlin.env（或 setup 时 sudo -E 传 DATABASE_URL/JWT_SIGNING_KEY）

set -eu

DOMAIN="${1:-}"
if [ -z "$DOMAIN" ]; then
  echo "Usage: $0 <saas-kotlin.example.com>" >&2
  exit 1
fi

BASE="/home/deploy/saas-identity-platform-kotlin"

log() { printf '→ %s\n' "$*"; }

# ── 1. 系统包 ─────────────────────────────────────
if ! command -v nginx >/dev/null 2>&1; then
  log "install nginx"
  apt-get update
  apt-get install -y nginx
fi
if ! command -v docker >/dev/null 2>&1; then
  log "install docker.io"
  apt-get install -y docker.io
fi

# ── 2. deploy 用户（无密码、SSH key only）─────────
if ! id deploy >/dev/null 2>&1; then
  log "create deploy user"
  adduser --disabled-password --gecos "" --shell /bin/bash deploy
fi
log "ensure deploy in docker group"
usermod -aG docker deploy

# ── 3. 部署目录 ───────────────────────────────────
log "create $BASE"
mkdir -p "$BASE"
chown deploy:deploy "$BASE"

# ── 4. sudoers 白名单（deploy 免密只放 nginx/systemctl，禁全量 NOPASSWD）──
SUDOERS_FILE="/etc/sudoers.d/deploy-nginx"
if [ ! -f "$SUDOERS_FILE" ]; then
  log "write $SUDOERS_FILE (nginx + systemctl NOPASSWD, !requiretty)"
  printf 'Defaults !requiretty\ndeploy ALL=(root) NOPASSWD: /usr/sbin/nginx, /bin/systemctl\n' > "$SUDOERS_FILE"
  chmod 440 "$SUDOERS_FILE"
  visudo -c -f "$SUDOERS_FILE"
fi

# ── 5. nginx vhost ────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATE="$SCRIPT_DIR/nginx-vps.conf.example"
if [ ! -f "$TEMPLATE" ]; then
  log "template not local, fetch from master"
  curl -fsSL "https://raw.githubusercontent.com/zcqiand/saas-identity-platform-kotlin/refs/heads/master/deploy/nginx-vps.conf.example" -o "$TEMPLATE"
fi
VHOST="/etc/nginx/sites-available/$DOMAIN"
log "render nginx vhost $VHOST"
sed \
  -e "s|/etc/nginx/ssl/your-cert\.cert|/etc/nginx/ssl/xiangru-uk.cert|g" \
  -e "s|/etc/nginx/ssl/your-cert\.key|/etc/nginx/ssl/xiangru-uk.key|g" \
  -e "s|saas\.YOUR_DOMAIN|$DOMAIN|g" \
  "$TEMPLATE" > "$VHOST"
ln -sf "$VHOST" "/etc/nginx/sites-enabled/$DOMAIN"

# default_server 冲突防护（家族同款）
if [ -f /etc/nginx/sites-enabled/default ]; then
  log "remove default site (default_server conflict)"
  rm -f /etc/nginx/sites-enabled/default
fi

nginx -t
systemctl reload nginx
log "setup done — kotlin.env 将由 CI deploy job 首启自举（或 sudo -E DATABASE_URL=... JWT_SIGNING_KEY=... sh $0 手工引导）"
