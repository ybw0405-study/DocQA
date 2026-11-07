#!/usr/bin/env bash
# WSL 开机时的中间件引导编排，由 docqa-infra.service 调用（也可手工执行做自检）。
#
#   bash <repo>/docs/00-infra/wsl-boot.sh [repo 在 WSL 内的路径]
#
# 顺序不能调换：
#   1) prepare-local-stack.sh 必须按**当前** WSL eth0 IP 重渲染 broker.conf，
#      否则 Windows 侧拿到的是上次开机的 brokerIP1，连不上 10911；
#   2) broker 重启后要等它真正注册到 namesrv，mqadmin 才建得出 topic；
#   3) topic 不存在时消费者只会静默空轮询，所以建 topic 必须带重试并核对输出。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_REPO_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
REPO_DIR="${1:-${DEFAULT_REPO_DIR}}"

MAX_WAIT="${DOCQA_BOOT_WAIT_SEC:-180}"

log() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*"; }

port_open() {
  timeout 2 bash -c "cat < /dev/null > /dev/tcp/${1}/${2}" 2>/dev/null
}

wait_for() {
  local label="$1" host="$2" port="$3" i
  for ((i = 0; i < MAX_WAIT / 3; i++)); do
    if port_open "${host}" "${port}"; then log "${label} ${host}:${port} ready"; return 0; fi
    sleep 3
  done
  log "ERROR: ${label} ${host}:${port} 未在 ${MAX_WAIT}s 内就绪"
  return 1
}

# broker 是否注册到 namesrv，不用日志字符串判断（docker restart 会保留旧日志，
# 近 120s 里可能还留着上一轮的 "boot success"）。mqadmin updateTopic 本身要求
# broker 已注册，所以由下面的重试循环充当真正的就绪判据。
ensure_topics_with_retry() {
  local i out
  for ((i = 1; i <= 10; i++)); do
    out="$(bash "${REPO_DIR}/docs/00-infra/setup-parse-worker.sh" topics 2>&1)" || true
    printf '%s\n' "${out}"
    if ! printf '%s\n' "${out}" | grep -q 'topic FAIL'; then
      log "topic 预建完成"
      return 0
    fi
    log "topic 预建第 ${i} 次未全成功，5s 后重试"
    sleep 5
  done
  log "ERROR: topic 预建连续 10 次失败"
  return 1
}

log "开始引导，仓库 = ${REPO_DIR}"
bash "${REPO_DIR}/docs/00-infra/prepare-local-stack.sh" "${REPO_DIR}"

wait_for namesrv 127.0.0.1 9876
wait_for broker 127.0.0.1 10911
ensure_topics_with_retry

log "WSL eth0 IP = $(hostname -I | awk '{print $1}')"
log "引导完成"
