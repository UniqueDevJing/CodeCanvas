#!/usr/bin/env bash
# CodeCanvas 服务器端安装脚本（Ubuntu，以 root 执行，可重复运行）
#
# 部署形态：沿用服务器已有的「一个 80 端口站点 + 多项目路径前缀」惯例，
# 把 CodeCanvas 挂到 /canvas/ 下，与 /mira/、/jobagent/ 平级，互不影响。
#
# 做的事：装 JRE17 → 建目录 → 装 systemd 服务 → 往站点里注入 /canvas/ 的 location → 配访问口令
set -euo pipefail

APP_DIR=/opt/codecanvas
WEB_ROOT=/var/www/canvas
LOG_DIR=/var/log/codecanvas
BACKEND_PORT="${BACKEND_PORT:-18080}"
WEB_PREFIX="${WEB_PREFIX:-/canvas}"
# 空值表示自动探测（见下方"接入方式自动探测"），也可显式指定要挂载的站点配置
SITE_CONF="${SITE_CONF:-}"
SNIPPET=/etc/nginx/codecanvas-locations.conf
STANDALONE_CONF=/etc/nginx/conf.d/codecanvas.conf
HTPASSWD=/etc/nginx/.codecanvas_htpasswd
AUTH_USER="${AUTH_USER:-codecanvas}"
AUTH_PASS="${AUTH_PASS:-}"
# 限流：默认每个 IP 每分钟 10 次、突发 6 次。
# 一次真实生成要 15~20 秒，正常使用根本到不了这个量；它的作用是挡爬虫和恶意刷额度。
# 设为 off 可关闭：RATE_LIMIT=off ./deploy.sh
RATE_LIMIT="${RATE_LIMIT:-10r/m}"
RATE_BURST="${RATE_BURST:-6}"
RL_ZONE_FILE=/etc/nginx/conf.d/codecanvas-ratelimit.conf

log()  { printf '\033[1;34m[codecanvas]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[codecanvas]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[codecanvas]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 1. Java
#
# 优先装 Java 21：后端的 Agent 线程池在 21+ 才会走虚拟线程（见 CodeCanvasConfig）。
# 17 也能跑，只是退回有界平台线程池——所以这里尽量要 21，装不上才退回 17 并明确告警。

WANT_MAJOR=21

java_major() { "$1" -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p'; }

# 直接按安装路径挑，避免装了 21 之后 command -v java 仍指向 17
pick_java() {
  for cand in /usr/lib/jvm/java-21-openjdk-amd64/bin/java \
              /usr/lib/jvm/java-17-openjdk-amd64/bin/java; do
    [ -x "$cand" ] && { printf '%s' "$cand"; return; }
  done
  command -v java || true
}

JAVA_BIN=""
HAVE_MAJOR="$(java_major "$(command -v java || echo /usr/bin/java)" 2>/dev/null || true)"
if [ -n "$HAVE_MAJOR" ] && [ "$HAVE_MAJOR" -ge "$WANT_MAJOR" ] 2>/dev/null; then
  JAVA_BIN="$(command -v java)"
  log "已检测到 Java $HAVE_MAJOR，虚拟线程可用"
elif [ -n "$HAVE_MAJOR" ]; then
  log "现有 Java $HAVE_MAJOR 可用，但虚拟线程需要 21+，尝试安装 JDK 21…"
fi

if [ -z "$JAVA_BIN" ]; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  if apt-get install -y -qq --no-install-recommends openjdk-21-jre-headless >/dev/null 2>&1; then
    JAVA_BIN="$(pick_java)"
    log "已安装 JDK 21：$("$JAVA_BIN" -version 2>&1 | head -1)"
  else
    warn "openjdk-21 安装失败，退回 17 —— 虚拟线程不会启用，平台线程池仍有界可用"
    apt-get install -y -qq --no-install-recommends openjdk-17-jre-headless >/dev/null 2>&1
    JAVA_BIN="$(pick_java)"
  fi
fi
[ -n "$JAVA_BIN" ] && [ -x "$JAVA_BIN" ] || die "Java 安装失败，请手动安装 openjdk-21 后重跑"
log "使用 Java：$JAVA_BIN（$("$JAVA_BIN" -version 2>&1 | head -1)）"

# ---------------------------------------------------------------- 2. 目录与密钥

mkdir -p "$APP_DIR" "$WEB_ROOT" "$LOG_DIR"

if [ -f "$APP_DIR/.env.uploaded" ]; then
  mv -f "$APP_DIR/.env.uploaded" "$APP_DIR/.env"
fi
[ -f "$APP_DIR/.env" ] || die "缺少 $APP_DIR/.env"
chmod 600 "$APP_DIR/.env"
mkdir -p "$APP_DIR/codecanvas-data"

if ! command -v nginx >/dev/null 2>&1; then
  log "安装 nginx …"
  export DEBIAN_FRONTEND=noninteractive
  if command -v apt-get >/dev/null 2>&1; then
    apt-get install -y -qq --no-install-recommends nginx
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y nginx
  elif command -v yum >/dev/null 2>&1; then
    yum install -y nginx
  else
    die "无法自动安装 nginx，请手动安装后重跑"
  fi
fi
log "nginx：$(nginx -v 2>&1)"

# 接入方式自动探测：换服务器时不需要改脚本。
#   subpath   —— 机器上已有站点占用 80，把 /canvas/ 作为前缀挂进去（沿用原有项目，互不影响）
#   standalone—— 80 没人用，直接建一个独立站点
# 也可用 SITE_CONF=/etc/nginx/sites-available/xxx 显式指定要挂载的站点。
MODE=""
if [ -n "${SITE_CONF:-}" ]; then
  [ -f "$SITE_CONF" ] || die "指定的 SITE_CONF 不存在：$SITE_CONF"
  MODE="subpath"
else
  if [ -f /etc/nginx/sites-available/crossborder ]; then
    SITE_CONF=/etc/nginx/sites-available/crossborder
    MODE="subpath"
  else
    CANDIDATE="$(grep -RlE 'listen[[:space:]]+([0-9.]+:)?80([[:space:];]|$)' \
      /etc/nginx/sites-enabled /etc/nginx/conf.d 2>/dev/null | head -1 || true)"
    if [ -n "$CANDIDATE" ]; then
      SITE_CONF="$CANDIDATE"
      MODE="subpath"
    else
      MODE="standalone"
    fi
  fi
fi

if [ "$MODE" = "subpath" ]; then
  log "接入方式：子路径挂载（目标站点 $SITE_CONF）"
else
  log "接入方式：独立站点（80 端口空闲）"
  # Ubuntu 默认站点是个欢迎页占位符。它若占着 default_server，我们新建的站点会收不到请求，
  # 所以把它禁用掉——只摘掉软链接，文件还在，一条 ln -s 就能还原。
  if [ -L /etc/nginx/sites-enabled/default ] && [ -f /var/www/html/index.nginx-debian.html ]; then
    warn "检测到 nginx 默认欢迎页占位站点，临时禁用（可用 ln -s 还原）"
    mv /etc/nginx/sites-enabled/default "/etc/nginx/sites-enabled/default.disabled.$(date +%Y%m%d%H%M%S)"
  fi
fi

# ---------------------------------------------------------------- 3. systemd

log "写入 systemd 服务（后端端口 127.0.0.1:$BACKEND_PORT）"
cat > /etc/systemd/system/codecanvas.service <<EOF
[Unit]
Description=CodeCanvas backend (AI app generation agent)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$APP_DIR
EnvironmentFile=$APP_DIR/.env
# 只监听回环地址：后端不对公网暴露，流量必须经过 nginx（鉴权在这一层）
# 机器只有 1.7G 内存且无 swap，堆给紧一点，宁可慢也别被 OOM Killer 干掉
ExecStart=$JAVA_BIN -Xmx448m -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC \\
  -Dfile.encoding=UTF-8 -jar $APP_DIR/codecanvas-backend.jar \\
  --server.address=127.0.0.1 --server.port=$BACKEND_PORT
Restart=always
RestartSec=5
StandardOutput=append:$LOG_DIR/backend.log
StandardError=append:$LOG_DIR/backend.log
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable codecanvas >/dev/null 2>&1 || true

# ---------------------------------------------------------------- 4. 访问口令（可选）
#
# 默认不加口令：直接可访问，方便演示与分享。
# 需要时用 ENABLE_AUTH=1 部署即可开启（nginx basic auth）。

AUTH_BLOCK=""
if [ -n "$AUTH_PASS" ]; then
  log "配置访问口令"
  HASH="$(openssl passwd -apr1 "$AUTH_PASS")"
  [ -n "$HASH" ] || die "口令哈希生成失败"
  printf '%s:%s\n' "$AUTH_USER" "$HASH" > "$HTPASSWD"
  # nginx worker 以 www-data 运行：属主必须是它，否则读不到文件，
  # 表现是"不带口令 401 正常、带上口令反而 500"（这个坑踩过一次）
  chown root:www-data "$HTPASSWD"
  chmod 640 "$HTPASSWD"
  AUTH_BLOCK="    auth_basic \"CodeCanvas\";
    auth_basic_user_file $HTPASSWD;"
else
  log "未启用访问口令（任何人可直接访问）"
  rm -f "$HTPASSWD"
fi

# ---------------------------------------------------------------- 5. nginx location

# 限流规则。limit_req_zone 只能写在 http 上下文，所以单独放一个 conf.d 文件，
# 不能和 location 写在一起。
RL_BLOCK=""
if [ "$RATE_LIMIT" = "off" ]; then
  log "限流：已关闭"
  rm -f "$RL_ZONE_FILE"
else
  log "限流：每 IP $RATE_LIMIT（突发 $RATE_BURST）"
  cat > "$RL_ZONE_FILE" <<EOF
# CodeCanvas 限流区。必须在 http 上下文，所以单独成文件。
# 生成接口会真实消耗模型额度，这里是挡爬虫与恶意刷量的第一道闸。
limit_req_zone \$binary_remote_addr zone=codecanvas_api:10m rate=$RATE_LIMIT;
EOF
  RL_BLOCK="    limit_req zone=codecanvas_api burst=$RATE_BURST nodelay;
    limit_req_status 429;"
fi

log "写入 $WEB_PREFIX 的 location 配置"
cat > "$SNIPPET" <<EOF
# CodeCanvas —— 一句话生成应用平台
# 由 sites-available/crossborder 里的 include 引入到 80 端口站点中，
# 与 /mira/、/jobagent/ 平级，互不影响。删除本文件并移除那行 include 即可完全卸载。

location = $WEB_PREFIX {
    return 301 $WEB_PREFIX/;
}

location $WEB_PREFIX/ {
$AUTH_BLOCK
    root /var/www;
    index index.html;
    # SPA：找不到的路径都交给前端路由
    try_files \$uri \$uri/ $WEB_PREFIX/index.html;
}

# 构建产物带内容指纹，可以放心长缓存
location ~* ^$WEB_PREFIX/assets/ {
$AUTH_BLOCK
    root /var/www;
    expires 30d;
    add_header Cache-Control "public, immutable";
}

location $WEB_PREFIX/api/ {
$AUTH_BLOCK
$RL_BLOCK

    client_max_body_size 4m;
    proxy_pass http://127.0.0.1:$BACKEND_PORT/api/;
    proxy_http_version 1.1;
    proxy_set_header Host \$host;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    proxy_set_header Connection '';
    # SSE 必须关缓冲：否则生成过程的增量会被 nginx 攒着一起发，前端看不到逐字输出
    proxy_buffering off;
    proxy_cache off;
    chunked_transfer_encoding on;
    proxy_read_timeout 1800s;
    proxy_send_timeout 1800s;
}

# 公开发布页：任何人拿到链接都能打开，看到的是应用本身（无平台外壳、无需登录）。
# 刻意不加访问口令，也不做限流——发布的意义就是让别人随时能看到。
location $WEB_PREFIX/a/ {
    proxy_pass http://127.0.0.1:$BACKEND_PORT/a/;
    proxy_http_version 1.1;
    proxy_set_header Host \$host;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;

    # 给生成的应用套一层内容安全策略：
    # connect-src 'none' 挡掉它的 fetch/XHR，防止"某个恶意应用把平台当成免费令牌池"
    # 去刷生成接口；default-src 限制为本地与内联，与提示词里"不引用任何外部资源"的约束一致。
    add_header Content-Security-Policy "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval' data:; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'none'; frame-src 'none';" always;
    add_header X-Content-Type-Options "nosniff" always;
}
EOF

# 把 snippet 接入 nginx（幂等，且先备份原配置）
if [ "$MODE" = "standalone" ]; then
  log "写入独立站点 $STANDALONE_CONF"
  cat > "$STANDALONE_CONF" <<EOF
# CodeCanvas 独立站点（部署到没有其它站点的机器时使用）
# locations 复用 $SNIPPET，两种接入方式共用同一份配置。
server {
    listen 80;
    server_name _;
    include $SNIPPET;
}
EOF
else
  # 用 sed 的 r 命令按整行插入，不要用 awk 拼接字符串：
  # 之前用 awk 时忘了用 -v 传变量，生成的是 `include ;`（参数为空），
  # nginx 直接校验失败——这类"看起来只是少个引号"的问题会让整台机器的 nginx 起不来。
  if grep -q "codecanvas-locations.conf" "$SITE_CONF"; then
    log "站点已包含 CodeCanvas 的 include，跳过注入"
  else
    cp -a "$SITE_CONF" "$SITE_CONF.bak.$(date +%Y%m%d%H%M%S)"
    printf '\n    # CodeCanvas sub-path deploy; locations in %s\n    include %s;\n' \
      "$SNIPPET" "$SNIPPET" > /tmp/cc-include.txt
    sed -i "/server_name _;/r /tmp/cc-include.txt" "$SITE_CONF"
    rm -f /tmp/cc-include.txt
    log "已向站点注入 include（原配置已备份为 $SITE_CONF.bak.*）"
  fi
fi

nginx -t || die "nginx 配置校验失败，已保留备份。注意：nginx 未重载，现有站点仍按旧配置运行"
systemctl reload nginx
log "nginx 已重载"

# ---------------------------------------------------------------- 6. 启动后端

log "启动后端服务"
systemctl restart codecanvas

READY=0
for i in $(seq 1 40); do
  if curl -fsS --max-time 3 "http://127.0.0.1:$BACKEND_PORT/api/config" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 3
done
[ "$READY" = "1" ] && log "后端已就绪" || warn "后端 120 秒内未就绪，请查看 $LOG_DIR/backend.log"

# ---------------------------------------------------------------- 7. 端到端验证

echo
log "端到端自检"
CURL_AUTH=()
[ -n "$AUTH_PASS" ] && CURL_AUTH=(-u "$AUTH_USER:$AUTH_PASS")

echo -n "  静态页面  : "
curl -s -o /dev/null -w "HTTP %{http_code}\n" "${CURL_AUTH[@]}" "http://127.0.0.1$WEB_PREFIX/"
if [ -n "$AUTH_PASS" ]; then
  echo -n "  无口令访问: "
  curl -s -o /dev/null -w "HTTP %{http_code}（401 表示鉴权生效）\n" "http://127.0.0.1$WEB_PREFIX/"
fi
echo -n "  接口连通  : "
curl -s "${CURL_AUTH[@]}" --max-time 8 "http://127.0.0.1$WEB_PREFIX/api/config" | head -c 200
echo

if [ "$RATE_LIMIT" != "off" ]; then
  echo -n "  限流验证  : "
  LIMITED=0
  for _ in $(seq 1 12); do
    CODE="$(curl -s -o /dev/null -w '%{http_code}' "${CURL_AUTH[@]}" --max-time 5 \
      "http://127.0.0.1$WEB_PREFIX/api/config" || echo 000)"
    [ "$CODE" = "429" ] && LIMITED=$((LIMITED + 1))
  done
  echo "连发 12 次，被限 $LIMITED 次（429）"
  # 刚刚把本机 IP 的额度打满了，清一下计数，免得影响紧接着的手工测试
  systemctl reload nginx >/dev/null 2>&1 || true
fi

# ---------------------------------------------------------------- 8. 结果

PUBLIC_IP="${PUBLIC_IP:-$(curl -fsS --max-time 6 https://api.ipify.org 2>/dev/null || true)}"
[ -n "$PUBLIC_IP" ] || PUBLIC_IP="<服务器IP>"
echo
echo "============= 部署完成 ============="
echo "访问地址 : http://$PUBLIC_IP$WEB_PREFIX/"
if [ -n "$AUTH_PASS" ]; then
  echo "访问口令 : $AUTH_USER / $AUTH_PASS"
else
  echo "访问口令 : 未启用（任何人可直接访问）"
fi
if [ "$RATE_LIMIT" = "off" ]; then
  echo "限流     : 已关闭"
else
  echo "限流     : 每 IP $RATE_LIMIT，突发 $RATE_BURST"
fi
echo "后端日志 : tail -f $LOG_DIR/backend.log"
echo "服务状态 : systemctl status codecanvas"
echo "回滚     : 删除 $SNIPPET 并从 $SITE_CONF 移除 include 行即可完全卸载"
echo
