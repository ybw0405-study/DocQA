#!/usr/bin/env bash
# 本地中间件栈引导脚本（WSL2 Ubuntu 内执行）
#
#   bash <repo>/docs/00-infra/prepare-local-stack.sh <repo 在 WSL 内的路径>
#
# 做四件事：
#   1) 在**仓库外**的 /opt/docqa-infra/.env 生成一次性本地密钥（已存在则复用，不覆盖）；
#   2) 按当前 WSL eth0 IP 渲染 RocketMQ broker.conf 的运行时副本；
#   3) 用 envsubst 把 compose 里的 ${...} 预渲染成运行时副本，再拉起四个容器；
#   4) 把 RocketMQ 命名卷属主改成容器内的 rocketmq 用户（uid 3000）。
#
# 三个踩过的坑，都是本环境的实测结论，不要"顺手优化"回去：
#   - 目录不能用 ~/docqa-infra：dockerd 以 root 跑时 HOME=/root，而 /root 是 700，
#     broker 容器内以非 root 的 rocketmq(3000) 运行，挂载进去的 broker.conf 读不到，
#     报 java.nio.file.AccessDeniedException。故固定用 /opt/docqa-infra 且目录 755、文件 644。
#   - 命名卷由 dockerd 以 root 创建，broker 写不进 /home/rocketmq/store，
#     表现为启动即退出、日志里只剩 shutdown 路径上的 NullPointerException。故第 4 步必须 chown。
#   - 本机 docker compose 为 v5.5.0，${VAR:?提示语} 形式的插值会**静默返回空串**而不是报错，
#     导致 MINIO_ROOT_USER / POSTGRES_PASSWORD 全为空。故第 3 步用 envsubst 预渲染，
#     compose 运行时不再依赖任何插值。
set -euo pipefail

REPO_DIR="${1:?用法: prepare-local-stack.sh <repo 在 WSL 内的路径>}"
INFRA_DIR="${DOCQA_INFRA_DIR:-/opt/docqa-infra}"
ENV_FILE="${INFRA_DIR}/.env"
COMPOSE_SRC="${REPO_DIR}/docs/00-infra/local-stack.compose.yaml"
BROKER_TMPL="${REPO_DIR}/docs/00-infra/rocketmq-broker.conf"

mkdir -p "${INFRA_DIR}"
chmod 755 "${INFRA_DIR}"

if [ ! -f "${ENV_FILE}" ]; then
  umask 077
  cat > "${ENV_FILE}" <<EOF
DOCQA_PG_PASSWORD=$(openssl rand -hex 16)
DOCQA_MINIO_ACCESS_KEY=$(openssl rand -hex 8)
DOCQA_MINIO_SECRET_KEY=$(openssl rand -hex 24)
EOF
  chmod 600 "${ENV_FILE}"
  echo "已生成 ${ENV_FILE}（权限 $(stat -c %a "${ENV_FILE}")）"
else
  echo "复用已存在的 ${ENV_FILE}"
fi

set -a
# shellcheck disable=SC1090
. "${ENV_FILE}"
set +a

WSL_IP="$(hostname -I | awk '{print $1}')"
sed "s/__BROKER_IP__/${WSL_IP}/" "${BROKER_TMPL}" > "${INFRA_DIR}/broker.conf"
chmod 644 "${INFRA_DIR}/broker.conf"
echo "brokerIP1 = ${WSL_IP}"

export DOCQA_INFRA_DIR="${INFRA_DIR}"
envsubst '${DOCQA_INFRA_DIR} ${DOCQA_PG_PASSWORD} ${DOCQA_MINIO_ACCESS_KEY} ${DOCQA_MINIO_SECRET_KEY}' \
  < "${COMPOSE_SRC}" > "${INFRA_DIR}/compose.rendered.yaml"
chmod 600 "${INFRA_DIR}/compose.rendered.yaml"

docker compose -f "${INFRA_DIR}/compose.rendered.yaml" up -d

# broker 起来之前先把卷属主修好；卷名 = <project>_<volume>，project 取 compose 里的 name 字段
for vol in rmq-broker-store rmq-namesrv-logs; do
  mount="$(docker volume inspect "docqa-infra_${vol}" --format '{{.Mountpoint}}' 2>/dev/null || true)"
  if [ -n "${mount}" ]; then
    chown -R 3000:3000 "${mount}"
    echo "chown 3000:3000 -> ${vol}"
  fi
done

docker compose -f "${INFRA_DIR}/compose.rendered.yaml" restart rocketmq-broker >/dev/null 2>&1 || true
echo "完成。用 'docker ps --format \"{{.Names}} | {{.Status}}\" | grep docqa' 核对四个容器。"
