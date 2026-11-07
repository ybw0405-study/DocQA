#!/usr/bin/env bash
# DocQA 本机栈的 WSL2 开机自启（systemd）安装器。
#
#   bash <repo>/docs/00-infra/install-wsl-autostart.sh install   [repo 在 WSL 内的路径]
#   bash <repo>/docs/00-infra/install-wsl-autostart.sh status
#   bash <repo>/docs/00-infra/install-wsl-autostart.sh uninstall
#
# 背景：WSL2 的 eth0 IP 每次 Windows 重启都会变，broker 又必须把 brokerIP1 写进
# broker.conf 才能让 Windows 侧客户端连上。所以中间件不能只依赖容器的 restart 策略，
# 每次 WSL 启动都要重跑 wsl-boot.sh（内部调用 prepare-local-stack.sh 渲染配置并预建 topic），
# 这里用一个 oneshot 单元承担。两个 worker 用 Type=simple + Restart=always 交给 systemd 托管，
# 顺带解决「一次性 wsl -e bash 会话结束就把 nohup 子进程回收」的老问题。
#
# Windows 侧的登录触发与端点同步由 docs/00-infra/start-local.ps1 负责，两者配套使用。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_REPO_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ACTION="${1:-status}"
REPO_DIR="${2:-${DEFAULT_REPO_DIR}}"

UNIT_DIR=/etc/systemd/system
VENV=/root/docqa-venv
WORKER_ENV=/root/parse-worker.env
INFRA_ENV=/opt/docqa-infra/.env
UNITS=(docqa-infra.service docqa-parse-worker.service docqa-vector-worker.service)

require_root() {
  [ "$(id -u)" -eq 0 ] || { echo "请以 root 运行：wsl -u root bash $0 ${ACTION}" >&2; exit 1; }
  [ -d /run/systemd/system ] || { echo "当前 WSL 未启用 systemd；请确认 /etc/wsl.conf 含 [boot] systemd=true" >&2; exit 1; }
  [ -f "${REPO_DIR}/docs/00-infra/wsl-boot.sh" ] || {
    echo "仓库路径无效：${REPO_DIR}" >&2; exit 1;
  }
  [ -f "${INFRA_ENV}" ] || { echo "缺少 ${INFRA_ENV}，请先执行 prepare-local-stack.sh" >&2; exit 1; }
  [ -x "${VENV}/bin/docqa-parse-worker" ] || {
    echo "缺少 worker 入口脚本，请先执行 setup-parse-worker.sh install" >&2; exit 1;
  }
  [ -f "${WORKER_ENV}" ] || { echo "缺少 ${WORKER_ENV}，请先执行 setup-parse-worker.sh env" >&2; exit 1; }
}

normalize_worker_env() {
  # 两项历史兼容：
  #   1) 旧版本每行带 export 前缀，systemd 的 EnvironmentFile 不接受；
  #   2) 从 Windows 管道写入的文件可能混入 \r，systemd 会在 \r 处截断值（曾导致
  #      Redis URL 被截成 redis://:<口令>，worker 启动时报 port out of range）。
  local dirty=0
  if grep -q '^export ' "${WORKER_ENV}"; then dirty=1; fi
  if grep -q $'\r' "${WORKER_ENV}"; then dirty=1; fi
  if [ "${dirty}" -eq 1 ]; then
    cp -p "${WORKER_ENV}" "${WORKER_ENV}.bak"
    sed -i 's/^export //; s/\r//g' "${WORKER_ENV}"
    echo "已规范化 ${WORKER_ENV}（去 export / 去 CR，备份 .bak）"
  fi
  chmod 600 "${WORKER_ENV}"
}

render() {
  # 单元里用 @REPO@ 占位，避免 heredoc 里的 $ 被 shell 提前展开
  sed "s|@REPO@|${REPO_DIR}|g"
}

write_units() {
  cat <<'UNIT' | render > "${UNIT_DIR}/docqa-infra.service"
[Unit]
Description=DocQA local middleware (RocketMQ / PostgreSQL / MinIO)
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=@REPO@
ExecStartPre=/usr/bin/env bash -c 'for i in $(seq 1 60); do [ -f @REPO@/docs/00-infra/wsl-boot.sh ] && exit 0; sleep 2; done; echo "repo not mounted" >&2; exit 1'
ExecStartPre=/usr/bin/env bash -c 'for i in $(seq 1 60); do docker info >/dev/null 2>&1 && exit 0; sleep 2; done; echo "dockerd not ready" >&2; exit 1'
ExecStart=/usr/bin/env bash @REPO@/docs/00-infra/wsl-boot.sh @REPO@

[Install]
WantedBy=multi-user.target
UNIT

  for target in parse vector; do
    local bin="docqa-${target}-worker"
    cat <<UNIT | render > "${UNIT_DIR}/docqa-${target}-worker.service"
[Unit]
Description=DocQA ${target} worker
After=docqa-infra.service
Requires=docqa-infra.service

[Service]
Type=simple
EnvironmentFile=/root/parse-worker.env
ExecStart=${VENV}/bin/${bin}
Restart=always
RestartSec=5
StandardOutput=append:/root/${target}-worker.log
StandardError=append:/root/${target}-worker.log

[Install]
WantedBy=multi-user.target
UNIT
  done
  chmod 644 "${UNIT_DIR}"/docqa-*.service
  echo "已写入 ${#UNITS[@]} 个单元到 ${UNIT_DIR}"
}

case "${ACTION}" in
  install)
    require_root
    normalize_worker_env
    write_units
    systemctl daemon-reload
    systemctl enable --now docqa-infra.service
    systemctl enable --now docqa-parse-worker.service docqa-vector-worker.service
    echo "--- 状态 ---"
    systemctl --no-pager --lines=0 status "${UNITS[@]}" || true
    ;;
  status)
    systemctl --no-pager --lines=0 status "${UNITS[@]}" || true
    echo "--- 容器 ---"
    docker ps --format '{{.Names}} | {{.Status}}' 2>/dev/null | grep docqa || true
    ;;
  uninstall)
    require_root
    systemctl disable --now docqa-parse-worker.service docqa-vector-worker.service docqa-infra.service 2>/dev/null || true
    rm -f "${UNIT_DIR}"/docqa-infra.service "${UNIT_DIR}"/docqa-parse-worker.service "${UNIT_DIR}"/docqa-vector-worker.service
    systemctl daemon-reload
    echo "已移除自启单元（容器与 worker 环境文件保留）"
    ;;
  *)
    echo "usage: $0 install|status|uninstall [repo 在 WSL 内的路径]" >&2
    exit 1
    ;;
esac
