#!/usr/bin/env bash
# 启动后端。会先加载同目录下的 .env（模型密钥等），再跑 Spring Boot。
#
# 用法：./run.sh
set -e
cd "$(dirname "$0")"

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
  echo "已加载 .env"
  echo "  模型端点: ${CODECANVAS_OPENAI_BASE}"
  echo "  旗舰/标准/轻量: ${CODECANVAS_MODEL_FLAGSHIP} / ${CODECANVAS_MODEL_STANDARD} / ${CODECANVAS_MODEL_LIGHT}"
else
  echo "未找到 .env，将以离线演示模式启动"
fi

# 找一个真正能跑起来的 mvn。
# 注意：IntelliJ 内置的 Maven 在 Git Bash 下直接调 bin/mvn 会报
# ClassNotFoundException: plexus-classworlds.launcher.Launcher，必须用 mvn.cmd。
pick_maven() {
  local candidates=()
  [ -n "$(command -v mvn || true)" ] && candidates+=("$(command -v mvn)")
  for c in /c/Program\ Files/JetBrains/*/plugins/maven/lib/maven3/bin/mvn.cmd \
           /c/Program\ Files/JetBrains/*/plugins/maven/lib/maven3/bin/mvn \
           /c/Program\ Files/apache-maven*/bin/mvn \
           /c/Users/*/scoop/apps/maven/current/bin/mvn; do
    [ -e "$c" ] && candidates+=("$c")
  done
  for c in "${candidates[@]}"; do
    if "$c" -v >/dev/null 2>&1; then
      echo "$c"
      return 0
    fi
  done
  return 1
}

MVN_BIN="$(pick_maven || true)"
if [ -z "$MVN_BIN" ]; then
  echo "找不到可用的 Maven。请安装 Maven 并加入 PATH，或在 IDE 里直接运行 CodeCanvasApplication。" >&2
  exit 1
fi
echo "使用 Maven: $MVN_BIN"

exec "$MVN_BIN" -s maven-settings.xml -DskipTests spring-boot:run \
  -Dspring-boot.run.arguments=--server.port=8080
