#!/usr/bin/env bash
# 网络抖动兜底：只对「瞬时网络类失败」重试 gradle 调用，真失败立即退出。
#
# 背景：一次 PR 门禁 ~15 个 gradle job 并发启动，Maven Central 等公共仓库对
# GitHub runner IP 限流（HTTP 429 Too Many Requests）或偶发连接错误，随机
# 某个 job 的依赖解析挂掉（此前 CI 的"随机"失败——stonecutter 插件、xaerolib
# metadata、gson/ow2 pom——全是这一类）。Gradle 增量机制让重试很便宜：
# 已下载构件留在 ~/.gradle 缓存、成功过的任务 up-to-date 跳过，重试通常能
# 走得更远直至全绿。
#
# 用法: tools/gradle_retry.sh ./gradlew <args...>
#   RETRY_MAX   最大尝试次数（默认 3）
#   RETRY_SLEEP 基础重试间隔秒数（默认 60，附加 0-30s 随机抖动，
#               防止矩阵 job 同步重试再次撞限流）
#
# 真缺失构件（"Could not find xaero.map:..."）不含下方网络特征，不会触发
# 重试；网络型解析失败的深层 cause 必然含 Could not GET/429/超时之一。
set -u

MAX="${RETRY_MAX:-3}"
SLEEP="${RETRY_SLEEP:-60}"

PATTERNS=(
  "429 from server"
  "Too Many Requests"
  "Could not GET"
  "Could not get resource"
  "Could not parse POM"
  "Read timed out"
  "Connection reset"
  "Connection refused"
)

attempt=1
while :; do
  log="$(mktemp)"
  "$@" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  if [ "$rc" -eq 0 ]; then
    rm -f "$log"
    exit 0
  fi

  hit=""
  for p in "${PATTERNS[@]}"; do
    if grep -qi -- "$p" "$log"; then hit="$p"; break; fi
  done
  rm -f "$log"

  if [ -z "$hit" ]; then
    echo "::error::gradle failed (rc=$rc), no network-error signature — not retrying"
    exit "$rc"
  fi
  if [ "$attempt" -ge "$MAX" ]; then
    echo "::error::gradle still failing after $attempt attempts (last network signature: '$hit')"
    exit "$rc"
  fi
  wait_s=$((SLEEP + RANDOM % 30))
  echo "::warning::attempt $attempt/$MAX hit network error ('$hit'), retrying in ${wait_s}s"
  sleep "$wait_s"
  attempt=$((attempt + 1))
done
