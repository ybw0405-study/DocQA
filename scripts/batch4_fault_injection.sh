#!/usr/bin/env bash
# =============================================================================
# 批次 4 / 故障注入脚本骨架 — DocQA
# -----------------------------------------------------------------------------
# 三类注入（方案第 6 节批次 4）：
#   redis-halfopen   Redis TCP 半开（连接不 reset）-> 验证 AF-7/DQ 类超时收口
#   mq-backlog       RocketMQ 堆积          -> 验证有限重投、DLQ、对账不永久 ACK
#   rolling-restart  实例滚动关停           -> 验证 DQ-4 drain 在途、按序关连接池
#
# 【脚手架定位 / 诚实边界】
#   * 这是可执行骨架，**本机未做真实故障演练**：没有真实实例/流量，
#     没有产生任何演练记录或观测结论。
#   * 默认 DRY-RUN：只打印「前置检查 / 注入命令 / 观测点 / 恢复命令 / 判据」，
#     不执行任何 docker / redis-cli / mqadmin / kill 命令。
#   * 只有显式 --apply 才会执行；且必须自带 --target（容器名/实例地址）。
#   * 不修改任何现有源码/脚本/配置；本文件为新增脚手架。
#
# 运行（Windows Git Bash）：
#   bash batch4_fault_injection.sh redis-halfopen                 # dry-run
#   bash batch4_fault_injection.sh mq-backlog --apply --target docqa-rmq-broker
#   bash batch4_fault_injection.sh rolling-restart --apply --target docqa-parse-worker
# =============================================================================
set -euo pipefail

APPLY=0
TARGET=""
DURATION=30
case_name="${1:-}"
shift || true
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=1 ;;
    --target) TARGET="${2:-}"; shift ;;
    --duration) DURATION="${2:-30}"; shift ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
  shift
done

say() { echo "$*"; }
run() {
  if [ "$APPLY" = 1 ]; then
    echo "  [EXEC] $*"
    # shellcheck disable=SC2086
    eval "$*"
  else
    echo "  [dry-run] 将执行: $*"
  fi
}

say "============================================================================"
say "批次4 故障注入骨架 | case=$case_name apply=$APPLY target=${TARGET:-<none>} duration=${DURATION}s"
say "本机未做真实故障演练；本脚本默认只打印计划，--apply 才会动真实目标。"
say "============================================================================"

case "$case_name" in
  redis-halfopen)
    say "目的：Redis 半开（连接不 reset、既不抛异常也不返回）时，租约 renew/health 不被永久阻塞。"
    say "前置：readiness 探 Redis；client 设 socket_timeout/socket_connect_timeout；renew 用 wait_for 包裹。"
    say "注入思路（择一，按你的拓扑选）："
    say "  1) redis-cli DEBUG SLEEP <秒>            # 阻塞 Redis 主线程，制造无响应"
    say "  2) docker network disconnect <net> <ct>  # 制造半开网络分区"
    say "  3) iptables DROP 已建立连接回包           # 最贴近 TCP 半开"
    run "redis-cli -h ${TARGET:-127.0.0.1} DEBUG SLEEP ${DURATION}"
    say "观测点："
    say "  - /health/ready 是否返回 503（依赖 down 应摘除实例）"
    say "  - 租约 renew 是否在 socket_timeout 内超时并返回，而非永久阻塞"
    say "  - 执行任务是否被接管（不是永久僵死、不刷 'worker crashed'）"
    say "恢复：DEBUG SLEEP 到期 / network connect / 删除 iptables 规则"
    say "判据：超时 < 配置的 socket_timeout + 余量；无永久僵死任务；恢复后 PING 通。"
    ;;

  mq-backlog)
    say "目的：MQ 堆积时有限重投、FAILED 不永久 ACK、对账任务能覆盖待清理版本。"
    say "前置：记录基线堆积深度；确认 DLQ topic 与对账任务开关。"
    say "注入思路：暂停 consumer + 批量投递 parse-requested / vector-change 消息。"
    run "docker pause ${TARGET:-docqa-parse-worker} || true"
    say "  # 上一条暂停 parse-worker 容器（consumer 所在进程）；批量投递用项目自带 MQ 客户端或 mqadmin。"
    say "  # TODO(可选)：按真实 mqadmin/SDK 投递 N 条消息（本骨架默认只做「暂停消费」注入）。"
    say "观测点："
    say "  - 堆积深度随投递上升，恢复消费后回落"
    say "  - FAILED 消息不被 markProcessed 永久 ACK，超过重投上限进 DLQ"
    say "  - 旧版本向量不残留（DELETE_VERSION / 待清理 outbox 被对账收口）"
    say "恢复：docker unpause ${TARGET:-docqa-parse-worker}；清理本次注入的测试消息（勿动业务 topic 存量）"
    say "判据：无永久残留向量；DLQ 条数符合预期；对账后 pending 归零。"
    ;;

  rolling-restart)
    say "目的：SIGTERM 滚动发布时，在途解析任务先 drain 再按序关 publisher/PG/Redis。"
    say "前置：确认至少 2 个实例、LB 摘流、在途任务可观测；记录当前镜像 digest。"
    say "注入思路：逐个实例发 SIGTERM，间隔观察在途任务收口。"
    run "docker stop --signal=SIGTERM --time=${DURATION} ${TARGET:-docqa-parse-worker}"
    say "  # 逐个实例发 SIGTERM；多实例时重复对下一个 --target 执行，间隔 ${DURATION}s"
    say "观测点："
    say "  - 日志无 'Task was destroyed but it is pending!'"
    say "  - 在途解析结果不丢失（parse.completed 与 PG 行数一致）"
    say "  - 关停顺序：停 consumer -> gather 在途 -> publisher -> PG provider -> redis -> loop.close"
    say "  - 新实例 readiness 变绿后才接收流量"
    say "恢复：按 digest 重新拉起实例；确认 /health/ready 200"
    say "判据：零在途丢失、零幽灵任务、租约接管时延 < SLO（见 batch4_slo_alerts.yaml）。"
    ;;

  *)
    say "用法: bash batch4_fault_injection.sh {redis-halfopen|mq-backlog|rolling-restart} [--apply] [--target NAME] [--duration N]"
    exit 2
    ;;
esac

say "----------------------------------------------------------------------------"
if [ "$APPLY" = 1 ]; then
  say "注意：本次为 --apply，但骨架内多数注入点是 TODO 占位，未真正改动任何真实实例。"
else
  say "DRY-RUN 结束：以上全部为计划，未执行任何注入命令，未产生故障演练记录。"
fi
exit 0
