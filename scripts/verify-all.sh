#!/usr/bin/env bash
# 三端门禁一键复跑：Java / Python / 前端
# 用法：
#   ./scripts/verify-all.sh               # 默认：在线 Maven + 前端构建
#   ./scripts/verify-all.sh --offline     # 本机离线 Maven（依赖已在 ~/.m2）
#   ./scripts/verify-all.sh --skip-java --skip-frontend
# 退出码：0 = 全部通过；1 = 至少一步失败；2 = 参数错误
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT/.artifacts"
STAMP="$(date +%Y%m%d-%H%M%S)"
JSON="$ARTIFACT_DIR/verify-$STAMP.json"

SKIP_JAVA=0
SKIP_PYTHON=0
SKIP_FRONTEND=0
OFFLINE=0

for arg in "$@"; do
  case "$arg" in
    --skip-java) SKIP_JAVA=1 ;;
    --skip-python) SKIP_PYTHON=1 ;;
    --skip-frontend) SKIP_FRONTEND=1 ;;
    --offline) OFFLINE=1 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg（用 --help 查看用法）" >&2; exit 2 ;;
  esac
done

mkdir -p "$ARTIFACT_DIR"
COMMIT="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"

NAMES=()
CODES=()
SECONDS_LIST=()
FAILED=0

record() {
  NAMES+=("$1")
  CODES+=("$2")
  SECONDS_LIST+=("$3")
  [ "$2" -ne 0 ] && FAILED=1
  return 0
}

run_step() {
  local name="$1" workdir="$2"
  shift 2
  echo ""
  echo "==> $name"
  local start end rc
  start=$(date +%s)
  ( cd "$workdir" && "$@" )
  rc=$?
  end=$(date +%s)
  if [ "$rc" -eq 0 ]; then
    echo "<== $name PASS ($((end - start))s)"
  else
    echo "<== $name FAIL rc=$rc ($((end - start))s)" >&2
  fi
  record "$name" "$rc" "$((end - start))"
  return 0
}

# 离线门禁必须使用干净环境。开发机往往在 User 环境变量里配了运行期的 DOCQA_*
# （审计守卫、实验预算、LLM 凭据……），Spring Boot 宽松绑定与 pydantic-settings 会自动
# 读取它们；而 CI 上这些变量根本不存在，于是出现「CI 全绿、本机假红」。
# 典型症状：AuditAppendOnlyAutoConfigurationTest.disabledWithoutProperty 被
# DOCQA_AUDIT_APPEND_ONLY_GUARD=enforce 激活、实验预算用例断言 2000 == 0。
for _env_name in $(compgen -e); do
  case "${_env_name}" in
    DOCQA_*|SERVER__PORT|SERVER_PORT) unset "${_env_name}" ;;
  esac
done

echo "DocQA · 三端门禁复跑"
echo "仓库：$ROOT"
echo "提交：$COMMIT ($BRANCH)"
echo "时间：$(date '+%Y-%m-%d %H:%M:%S %z')"

# ---------- Java ----------
if [ "$SKIP_JAVA" -eq 0 ]; then
  MVN_ARGS=(-B -ntp)
  [ "$OFFLINE" -eq 1 ] && MVN_ARGS+=(-o)
  run_step "java:mvn clean verify" "$ROOT/backend-java" mvn "${MVN_ARGS[@]}" clean verify
else
  echo "==> java 已跳过（--skip-java）"
fi

# ---------- Python ----------
if [ "$SKIP_PYTHON" -eq 0 ]; then
  if [ -x "$ROOT/ai-python/.venv/Scripts/python.exe" ]; then
    PY="$ROOT/ai-python/.venv/Scripts/python.exe"
  elif [ -x "$ROOT/ai-python/.venv/bin/python" ]; then
    PY="$ROOT/ai-python/.venv/bin/python"
  else
    PY="$(command -v python3 || command -v python)"
  fi
  echo ""
  echo "Python 解释器：$PY"
  run_step "python:pytest" "$ROOT/ai-python" "$PY" -m pytest -p no:warnings -q
  run_step "python:ruff check" "$ROOT/ai-python" "$PY" -m ruff check .
  run_step "python:ruff format" "$ROOT/ai-python" "$PY" -m ruff format --check .
else
  echo "==> python 已跳过（--skip-python）"
fi

# ---------- 前端 ----------
if [ "$SKIP_FRONTEND" -eq 0 ]; then
  if [ ! -d "$ROOT/frontend-vue/node_modules" ]; then
    run_step "frontend:npm ci" "$ROOT/frontend-vue" npm ci
  fi
  run_step "frontend:typecheck" "$ROOT/frontend-vue" npm run typecheck
  run_step "frontend:lint" "$ROOT/frontend-vue" npm run lint
  run_step "frontend:test" "$ROOT/frontend-vue" npm run test
  run_step "frontend:build" "$ROOT/frontend-vue" npm run build
else
  echo "==> frontend 已跳过（--skip-frontend）"
fi

# ---------- 汇总 ----------
TOTAL=0
for s in "${SECONDS_LIST[@]}"; do TOTAL=$((TOTAL + s)); done

echo ""
echo "================ 汇总 ================"
printf "%-28s %-8s %s\n" "步骤" "结果" "耗时"
for i in "${!NAMES[@]}"; do
  if [ "${CODES[$i]}" -eq 0 ]; then RESULT="PASS"; else RESULT="FAIL"; fi
  printf "%-28s %-8s %ss\n" "${NAMES[$i]}" "$RESULT" "${SECONDS_LIST[$i]}"
done
echo "--------------------------------------"
if [ "$FAILED" -eq 0 ]; then echo "结论：全部通过（总耗时 ${TOTAL}s）"; else echo "结论：存在失败步骤（总耗时 ${TOTAL}s）"; fi

# ---------- 落盘摘要（不提交）----------
{
  printf '{\n'
  printf '  "timestamp": "%s",\n' "$(date -Iseconds)"
  printf '  "commit": "%s",\n' "$COMMIT"
  printf '  "branch": "%s",\n' "$BRANCH"
  printf '  "totalSeconds": %s,\n' "$TOTAL"
  printf '  "failed": %s,\n' "$([ "$FAILED" -eq 0 ] && echo false || echo true)"
  printf '  "steps": [\n'
  for i in "${!NAMES[@]}"; do
    COMMA=","
    [ "$i" -eq $((${#NAMES[@]} - 1)) ] && COMMA=""
    printf '    {"name": "%s", "exitCode": %s, "seconds": %s}%s\n' "${NAMES[$i]}" "${CODES[$i]}" "${SECONDS_LIST[$i]}" "$COMMA"
  done
  printf '  ]\n}\n'
} > "$JSON"
echo "摘要：$JSON"

[ "$FAILED" -eq 0 ] || exit 1
exit 0
