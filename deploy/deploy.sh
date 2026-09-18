#!/usr/bin/env bash
# CodeCanvas 一键部署：本地构建 → 上传 → 服务器端安装
#
# 用法：
#   SSH_PASS='服务器密码' ./deploy.sh     # 首次（用密码登录，结束后会装上公钥）
#   ./deploy.sh                           # 之后（用本机 id_ed25519 免密登录）
#
# 可选环境变量：
#   SERVER_HOST   默认 114.215.186.113
#   SSH_USER      默认 root
#   AUTH_PASS     站点访问口令；不传则随机生成并写入 .deploy-credentials
#   SKIP_BUILD=1  跳过本地构建（复用已有产物）
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"
SERVER_HOST="${SERVER_HOST:-114.215.186.113}"
SSH_USER="${SSH_USER:-root}"
SSH_PASS="${SSH_PASS:-}"
AUTH_USER="${AUTH_USER:-codecanvas}"
AUTH_PASS="${AUTH_PASS:-}"
WEB_PREFIX="${WEB_PREFIX:-/canvas}"
BACKEND_PORT="${BACKEND_PORT:-18080}"
SKIP_BUILD="${SKIP_BUILD:-0}"

log()  { printf '\033[1;34m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[deploy]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[deploy]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- SSH 传输层
#
# 本机没有 sshpass，用 OpenSSH 的 SSH_ASKPASS 机制喂密码。
# 密码只写进一个临时文件（0600），退出时立即删除，不落到项目里。

ASKPASS_FILE=""
SSH_OPTS=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
          -o ConnectTimeout=25 -o LogLevel=ERROR)

if [ -n "$SSH_PASS" ]; then
  ASKPASS_FILE="$(mktemp)"
  {
    printf '#!/bin/sh\n'
    printf 'cat <<"__CC_PW__"\n'
    printf '%s\n' "$SSH_PASS"
    printf '__CC_PW__\n'
  } > "$ASKPASS_FILE"
  chmod 700 "$ASKPASS_FILE"
  SSH_OPTS+=(-o PreferredAuthentications=password -o PubkeyAuthentication=no
             -o NumberOfPasswordPrompts=1)
else
  SSH_OPTS+=(-o BatchMode=yes)
fi

# 注意：必须显式 return 0。
# EXIT trap 的最后一条命令如果是 `[ -n "" ]` 这种返回 1 的判断，
# 它的状态码会直接变成整个脚本的退出码——脚本明明成功却报失败。
cleanup() {
  if [ -n "$ASKPASS_FILE" ]; then
    rm -f "$ASKPASS_FILE"
  fi
  return 0
}
trap cleanup EXIT INT TERM

ssh_run() {
  if [ -n "$ASKPASS_FILE" ]; then
    SSH_ASKPASS="$ASKPASS_FILE" SSH_ASKPASS_REQUIRE=force DISPLAY=:0 \
      ssh "${SSH_OPTS[@]}" "$SSH_USER@$SERVER_HOST" "$@"
  else
    ssh "${SSH_OPTS[@]}" "$SSH_USER@$SERVER_HOST" "$@"
  fi
}

scp_run() {
  if [ -n "$ASKPASS_FILE" ]; then
    SSH_ASKPASS="$ASKPASS_FILE" SSH_ASKPASS_REQUIRE=force DISPLAY=:0 \
      scp "${SSH_OPTS[@]}" "$@"
  else
    scp "${SSH_OPTS[@]}" "$@"
  fi
}

log "连接服务器 $SSH_USER@$SERVER_HOST …"
ssh_run "echo ok" >/dev/null || die "SSH 登录失败"
[ -f "$PROJECT/backend/.env" ] || die "缺少 $PROJECT/backend/.env（模型密钥配置）"

FRONTEND="$PROJECT/frontend"

# ---------------------------------------------------------------- 构建产物

if [ "$SKIP_BUILD" != "1" ]; then
  log "构建前端（部署前缀 $WEB_PREFIX/）…"
  command -v npm >/dev/null || die "找不到 npm"
  ( cd "$FRONTEND" && npm run build )
  [ -f "$FRONTEND/dist/index.html" ] || die "前端构建产物缺失"

  log "构建后端 …"
  MVN_BIN="$(command -v mvn || true)"
  if [ -z "$MVN_BIN" ]; then
    for c in /c/Program\ Files/JetBrains/*/plugins/maven/lib/maven3/bin/mvn.cmd \
             /c/Program\ Files/JetBrains/*/plugins/maven/lib/maven3/bin/mvn; do
      [ -e "$c" ] && MVN_BIN="$c" && break
    done
  fi
  [ -n "$MVN_BIN" ] || die "找不到 Maven"
  ( cd "$PROJECT/backend" && "$MVN_BIN" -s maven-settings.xml -B -DskipTests -q package )
  [ -f "$PROJECT/backend/target/codecanvas-backend.jar" ] || die "后端构建产物缺失"
fi

# ---------------------------------------------------------------- 访问口令（默认关闭）

ENABLE_AUTH="${ENABLE_AUTH:-0}"
CRED_FILE="$HERE/.deploy-credentials"

if [ "$ENABLE_AUTH" = "1" ]; then
  if [ -z "$AUTH_PASS" ] && [ -f "$CRED_FILE" ]; then
    AUTH_PASS="$(sed -n 's/^password=//p' "$CRED_FILE" | head -1)"
    log "复用已有访问口令"
  fi
  if [ -z "$AUTH_PASS" ]; then
    AUTH_PASS="$(openssl rand -base64 12 | tr -d '/+=' | cut -c1-14)"
    log "已生成新访问口令"
  fi
  printf 'url=http://%s%s/\nusername=%s\npassword=%s\n' \
    "$SERVER_HOST" "$WEB_PREFIX" "$AUTH_USER" "$AUTH_PASS" > "$CRED_FILE"
  chmod 600 "$CRED_FILE"
else
  # 默认不加口令：部署完直接可访问。要开启用 ENABLE_AUTH=1
  AUTH_PASS=""
  rm -f "$CRED_FILE"
  log "访问口令：关闭（如需开启：ENABLE_AUTH=1 ./deploy.sh）"
fi

# ---------------------------------------------------------------- 上传

log "上传产物 …"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"; cleanup' EXIT INT TERM

cp "$PROJECT/backend/target/codecanvas-backend.jar" "$STAGE/"
cp "$PROJECT/backend/.env" "$STAGE/env.txt"
cp "$HERE/remote-setup.sh" "$STAGE/"

# 只创建目录，不做任何删除动作——这台服务器上还跑着别的项目，宁可留几个旧的
# 带指纹静态文件（无害），也不冒误删的风险。
ssh_run "mkdir -p /opt/codecanvas /tmp/codecanvas-upload /var/www/canvas"

scp_run "$STAGE/codecanvas-backend.jar" "$SSH_USER@$SERVER_HOST:/tmp/codecanvas-upload/"
scp_run "$STAGE/remote-setup.sh"        "$SSH_USER@$SERVER_HOST:/tmp/codecanvas-upload/"
scp_run "$STAGE/env.txt"                "$SSH_USER@$SERVER_HOST:/tmp/codecanvas-upload/.env.uploaded"
scp_run -r "$FRONTEND/dist/."           "$SSH_USER@$SERVER_HOST:/var/www/canvas/"

log "安装到服务器 …"
ssh_run "set -e
  install -m 644 /tmp/codecanvas-upload/codecanvas-backend.jar /opt/codecanvas/codecanvas-backend.jar
  mv -f /tmp/codecanvas-upload/.env.uploaded /opt/codecanvas/.env.uploaded
  sed -i 's/\r\$//' /tmp/codecanvas-upload/remote-setup.sh
  AUTH_USER='$AUTH_USER' AUTH_PASS='$AUTH_PASS' WEB_PREFIX='$WEB_PREFIX' \
    BACKEND_PORT='$BACKEND_PORT' PUBLIC_IP='$SERVER_HOST' \
    bash /tmp/codecanvas-upload/remote-setup.sh
"

# ---------------------------------------------------------------- 装公钥免密

if [ -f "$HOME/.ssh/id_ed25519.pub" ]; then
  log "安装部署公钥（下次免密码）"
  PUBKEY="$(cat "$HOME/.ssh/id_ed25519.pub")"
  ssh_run "mkdir -p /root/.ssh && chmod 700 /root/.ssh
    touch /root/.ssh/authorized_keys
    grep -qF '$PUBKEY' /root/.ssh/authorized_keys || echo '$PUBKEY' >> /root/.ssh/authorized_keys
    chmod 600 /root/.ssh/authorized_keys
    echo 'publickey installed'"
fi

echo
echo "================ 部署完成 ================"
echo "访问地址 : http://$SERVER_HOST$WEB_PREFIX/"
if [ -n "$AUTH_PASS" ]; then
  echo "访问口令 : $AUTH_USER / $AUTH_PASS"
else
  echo "访问口令 : 未启用（任何人可直接访问）"
fi
echo "========================================="
