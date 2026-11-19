#!/usr/bin/env bash
# Python worker（解析 + 向量删除）的 WSL2 侧引导与运行脚本（幂等，可重复执行）
#
#   wsl -u root bash <repo>/docs/00-infra/setup-parse-worker.sh install [WSL 内的仓库路径]
#        （仓库路径可省略，按脚本自身位置推导；目录改名不影响）
#   wsl -u root bash <同上> start            # 后台拉起解析 worker，日志 /root/parse-worker.log
#   wsl -u root bash <同上> start vector     # 后台拉起向量删除 worker，日志 /root/vector-worker.log
#   wsl -u root bash <同上> start all        # 两个 worker 都要跑：S2 之后缺 vector worker 就没人消费删除请求
#   wsl -u root bash <同上> status [目标]     # 环境变量存在性（只报长度）+ 依赖端口连通性
#   wsl -u root bash <同上> log [目标]        # 最近 40 行日志
#   wsl -u root bash <同上> stop [目标]
#
# start 之前 broker 上必须已存在对应 topic（消费者不会自动建 topic）：
#   docker exec docqa-rmq-broker sh -c "cd /home/rocketmq/rocketmq-5.3.1/bin && \
#     sh mqadmin updateTopic -n 127.0.0.1:9876 -b 127.0.0.1:10911 -t vector-change-requested"
#   docker exec docqa-rmq-broker sh -c "cd /home/rocketmq/rocketmq-5.3.1/bin && \
#     sh mqadmin updateTopic -n 127.0.0.1:9876 -b 127.0.0.1:10911 -t vector-change-completed"
#   docker exec docqa-rmq-broker sh -c "cd /home/rocketmq/rocketmq-5.3.1/bin && \
#     sh mqadmin topicList -n 127.0.0.1:9876" | grep vector-change
#
# 为什么 Worker 只能在 WSL 里跑：rocketmq-client-python 是 ctypes 绑定，import 时需要
# /usr/local/lib/librocketmq.so，而官方只提供 Linux 的 C++ 客户端包。Windows 侧 pip 能装上，
# 但启动即 ImportError，所以解析链路必须有一个 Linux 运行位。
#
# 本脚本不写入、不打印任何密钥明文：PG/MinIO 口令取自 prepare-local-stack.sh 生成的
# /opt/docqa-infra/.env（仓库外），Redis 口令从 stdin 读入。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_REPO_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
VENV=/root/docqa-venv
ENV_FILE=/root/parse-worker.env
LOG_FILE=/root/parse-worker.log
VECTOR_LOG_FILE=/root/vector-worker.log
CPP_DEB_URL="${ROCKETMQ_CPP_DEB_URL:-https://github.com/apache/rocketmq-client-cpp/releases/download/2.2.0/rocketmq-client-cpp-2.2.0.amd64.deb}"
INFRA_ENV=/opt/docqa-infra/.env
# Redis 与 broker 分处两个位置，这里逐项探测；地址变化后需同步 DOCQA_AI_REDIS_URL
REDIS_HOSTPORT="${DOCQA_REDIS_HOSTPORT:-127.0.0.1:16379}"

port_open() {
  local host="${1%%:*}" port="${1##*:}"
  timeout 3 bash -c "cat < /dev/null > /dev/tcp/${host}/${port}" 2>/dev/null
}

install() {
  local repo_dir="${1:-${DEFAULT_REPO_DIR}}"
  [ -d "${repo_dir}/ai-python" ] || { echo "仓库路径不对：${repo_dir}/ai-python 不存在"; exit 1; }
  [ -f "${INFRA_ENV}" ] || { echo "缺少 ${INFRA_ENV}，请先执行 prepare-local-stack.sh"; exit 1; }

  if [ ! -f /usr/local/lib/librocketmq.so ]; then
    if ! dpkg -l rocketmq 2>/dev/null | grep -q '^ii'; then
      echo "安装 RocketMQ C++ 客户端（librocketmq.so）"
      curl -fsSL -o /tmp/rocketmq-client-cpp.amd64.deb "${CPP_DEB_URL}"
      dpkg -i /tmp/rocketmq-client-cpp.amd64.deb
    fi
  fi

  if ! command -v python3 >/dev/null || ! python3 -m venv -h >/dev/null 2>&1; then
    echo "安装 python3-venv / python3-pip"
    apt-get update -y
    apt-get install -y python3-venv python3-pip
  fi

  [ -d "${VENV}" ] || python3 -m venv "${VENV}"
  # 主依赖是 >= 区间，重复解析可能把 fastapi/uvicorn 升到未经本仓库验证的版本；
  # 因此只有当包、C 库绑定或 console script 确实缺失时才执行安装，已就绪则原样跳过。
  # console script 只在 pip install 时生成，新增入口脚本必须靠这个判据触发重装。
  if "${VENV}/bin/python" -c "import docqa_ai, rocketmq" 2>/dev/null \
     && [ -x "${VENV}/bin/docqa-parse-worker" ] \
     && [ -x "${VENV}/bin/docqa-vector-worker" ]; then
    echo "venv 已就绪且两个 worker 入口脚本齐备，跳过 pip install：${VENV}"
  else
    "${VENV}/bin/pip" install --quiet --upgrade pip
    # [mq] extra 才会拉入 rocketmq-client-python，主依赖刻意不含它（离线测试不需要 C 库）
    "${VENV}/bin/pip" install --quiet -e "${repo_dir}/ai-python[mq]"
    "${VENV}/bin/python" -c "import docqa_ai, rocketmq"
    echo "venv 就绪：${VENV}"
  fi
}

gen_env() {
  # Redis 口令走 stdin，不进 argv，也不落到进程列表
  local redis_pw
  read -r redis_pw
  # 口令常由 Windows PowerShell 管道喂入，行尾会带 \r；它会嵌进 URL 变成
  # "redis://:pw\r@host:port"。bash source 时只是多了个字符，但 systemd 的
  # EnvironmentFile 会在 \r 处截断，把 "redis://:<口令>" 当成完整 URL 并报
  # "Port out of range 0-65535"。这里在生成端就掐掉。
  redis_pw="${redis_pw%$'\r'}"
  [ -n "${redis_pw}" ] || { echo "stdin 未读到 Redis 口令"; exit 1; }
  set -a
  # shellcheck disable=SC1090
  . "${INFRA_ENV}"
  set +a
  umask 077
  # 纯 KEY=VALUE，不带 export：systemd 的 EnvironmentFile 不接受 export 前缀，
  # 而本脚本 status/start 在 source 前都执行了 `set -a`，两种用法可以共用同一份文件。
  cat > "${ENV_FILE}" <<EOF
DOCQA_AI_PG_DSN=postgresql://docqa:${DOCQA_PG_PASSWORD}@127.0.0.1:5432/docqa_vec
DOCQA_AI_REDIS_URL=redis://:${redis_pw}@${REDIS_HOSTPORT}/0
DOCQA_AI_ROCKETMQ_NAMESRV=127.0.0.1:9876
DOCQA_AI_MINIO_ENDPOINT=http://127.0.0.1:9100
DOCQA_AI_MINIO_ACCESS_KEY=${DOCQA_MINIO_ACCESS_KEY}
DOCQA_AI_MINIO_SECRET_KEY=${DOCQA_MINIO_SECRET_KEY}
DOCQA_AI_MINIO_BUCKET=docqa-documents
DOCQA_AI_MQ_ENABLED=true
DOCQA_AI_MINIO_ENABLED=true
DOCQA_AI_EMBEDDING_PROVIDER=offline
DOCQA_AI_LLM_PROVIDER=offline
DOCQA_AI_LOG_LEVEL=INFO
EOF
  chmod 600 "${ENV_FILE}"
  echo "已写入 ${ENV_FILE}（权限 $(stat -c %a "${ENV_FILE}")）"
}

worker_bin() {
  case "${1}" in
    parse) echo docqa-parse-worker ;;
    vector) echo docqa-vector-worker ;;
  esac
}

worker_log() {
  case "${1}" in
    parse) echo "${LOG_FILE}" ;;
    vector) echo "${VECTOR_LOG_FILE}" ;;
  esac
}

resolve_targets() {
  case "${1:-parse}" in
    parse) echo parse ;;
    vector) echo vector ;;
    all) echo "parse vector" ;;
    *) echo "目标只能是 parse | vector | all（收到：${1}）" >&2; exit 1 ;;
  esac
}

status() {
  [ -f "${ENV_FILE}" ] || { echo "${ENV_FILE} 不存在，先执行 env"; exit 1; }
  set -a
  # shellcheck disable=SC1090
  . "${ENV_FILE}"
  set +a
  local v
  for v in DOCQA_AI_PG_DSN DOCQA_AI_REDIS_URL DOCQA_AI_ROCKETMQ_NAMESRV \
           DOCQA_AI_MINIO_ENDPOINT DOCQA_AI_MINIO_ACCESS_KEY DOCQA_AI_MINIO_SECRET_KEY; do
    local val
    val="$(eval echo "\${${v}:-}")"
    if [ -z "${val}" ]; then echo "${v} = MISSING"; else echo "${v} = present len=${#val}"; fi
  done
  for hp in 127.0.0.1:5432 127.0.0.1:9100 127.0.0.1:9876 "${REDIS_HOSTPORT}"; do
    if port_open "${hp}"; then echo "${hp} open"; else echo "${hp} closed"; fi
  done
  local target bin
  for target in $(resolve_targets "${1:-all}"); do
    bin="$(worker_bin "${target}")"
    if pgrep -f "${bin}" >/dev/null; then
      echo "${target} worker = running pid $(pgrep -f "${bin}" | tr '\n' ' ')"
    else
      echo "${target} worker = stopped"
    fi
  done
}

# ---- topic 预建 -------------------------------------------------------------
# 消费者不会自动建 topic：订阅一个不存在的 topic 只会静默空轮询，日志里除了
# "the topic[xxx] not exist" 之外没有任何错误，属于沉默故障（实测确认）。
# 生产端第一次发送虽然能靠 autoCreateTopicEnable 建出 topic，但队列数与读写权限
# 由 broker 模板决定，且首个消费者要等下一轮 rebalance（约 20s）才开始消费。
# 因此把 7 个 topic 的预建纳入启动顺序，updateTopic 幂等，可反复执行。
BROKER_CONTAINER="${BROKER_CONTAINER:-docqa-rmq-broker}"
TOPIC_LIST="audit-event parse-requested parse-completed parse-failed vector-change-requested vector-change-completed reminder-due"

ensure_topics() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "跳过 topic 预建：没有 docker CLI（宿主机手工建过 topic 时忽略本步）"
    return 0
  fi
  if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "${BROKER_CONTAINER}"; then
    echo "跳过 topic 预建：容器 ${BROKER_CONTAINER} 未运行"
    return 0
  fi
  local bin_dir
  bin_dir="$(docker exec "${BROKER_CONTAINER}" sh -c 'ls -d /home/rocketmq/rocketmq-*/bin 2>/dev/null | head -1' || true)"
  if [ -z "${bin_dir}" ]; then
    echo "跳过 topic 预建：容器内找不到 mqadmin（bin 目录未知）"
    return 0
  fi
  local topic out rc
  for topic in ${TOPIC_LIST}; do
    out="$(docker exec "${BROKER_CONTAINER}" sh -c \
      "cd ${bin_dir} && sh mqadmin updateTopic -n 127.0.0.1:9876 -b 127.0.0.1:10911 -t ${topic} 2>&1" || true)"
    rc=0
    echo "${out}" | grep -qiE "success|create topic" || rc=1
    if [ "${rc}" -eq 0 ]; then
      echo "topic ok   ${topic}"
    else
      echo "topic FAIL ${topic} :: $(echo "${out}" | tail -1)"
    fi
  done
}

# 装了 systemd 自启单元后，worker 的生命周期归 systemd 管；此处只做转发，
# 避免「systemd 拉起一个 + 手工 nohup 再拉起一个」造成重复消费。
systemd_managed() {
  command -v systemctl >/dev/null 2>&1 || return 1
  [ -d /run/systemd/system ] || return 1
  systemctl list-unit-files --no-legend 2>/dev/null | grep -q '^docqa-parse-worker\.service' || return 1
  return 0
}

worker_unit() {
  echo "docqa-${1}-worker.service"
}

start() {
  [ -f "${ENV_FILE}" ] || { echo "${ENV_FILE} 不存在，先执行 env"; exit 1; }
  ensure_topics
  if systemd_managed; then
    for target in $(resolve_targets "${1:-parse}"); do
      unit="$(worker_unit "${target}")"
      systemctl start "${unit}"
      echo "${target} worker -> systemctl start ${unit}"
      sleep 2
      journalctl -u "${unit}" -n 20 --no-pager 2>/dev/null | tail -20 || true
    done
    return 0
  fi
  set -a
  # shellcheck disable=SC1090
  . "${ENV_FILE}"
  set +a
  local target bin log
  for target in $(resolve_targets "${1:-parse}"); do
    bin="$(worker_bin "${target}")"
    log="$(worker_log "${target}")"
    if pgrep -f "${bin}" >/dev/null; then
      echo "${target} worker 已在运行，跳过"
      continue
    fi
    nohup "${VENV}/bin/${bin}" > "${log}" 2>&1 &
    sleep 8
    echo "== ${target} worker =="
    tail -20 "${log}"
  done
}

case "${1:-status}" in
  install) install "${2:-}" ;;
  env) gen_env ;;
  start) start "${2:-parse}" ;;
  stop)
    if systemd_managed; then
      for target in $(resolve_targets "${2:-all}"); do
        unit="$(worker_unit "${target}")"
        systemctl stop "${unit}"
        echo "${target} worker -> systemctl stop ${unit}"
      done
      exit 0
    fi
    stopped=0
    for target in $(resolve_targets "${2:-all}"); do
      if pkill -f "$(worker_bin "${target}")"; then
        echo "${target} worker stopped"
        stopped=1
      else
        echo "${target} worker 未在运行"
      fi
    done
    [ "${stopped}" -eq 1 ] || true
    ;;
  status) status "${2:-all}" ;;
  log) tail -40 "$(worker_log "${2:-parse}")" ;;
  topics) ensure_topics ;;
  *) echo "usage: $0 install <repo> | env | start [parse|vector|all] | stop [目标] | status [目标] | log [目标] | topics"; exit 1 ;;
esac
