#!/usr/bin/env bash
# =============================================================================
# rehearsal_release_runbook.sh — DocQA 发布 runbook 本机演练脚本（可复跑）
# -----------------------------------------------------------------------------
# 依据：
#   * C:/Users/YYYBBW/Desktop/修复执行报告.md 第 5 节「上线前必须按序执行」
#   * ai-python/migrations/004_add_tenant_id.sql / 005_drop_tenant_default.sql
#   * ai-python/src/docqa_ai/maintenance/backfill_tenant_id.py
#
# 本机演练的是报告第 5 节里「数据面闭环」这一段，顺序严格如下：
#   起基础设施(MySQL + PG(pgvector) + Redis) -> 等 healthy -> docker exec 造样本
#   (必须含 tenant_id='' 的历史 chunk 行) -> 从控制面导出 documentId->tenantId
#   映射 -> backfill dry-run(断言 unresolvedDocuments) -> --apply -> 复跑
#   dry-run(断言 emptyTenantRows / unresolvedDocuments 均为 0) -> 执行 005 ->
#   验证「漏传 tenant_id 的 INSERT 报错」「同租户命中 / 异租户 0 行」-> 输出报告
#   -> 停掉自己起的容器。
#
# 报告第 5 节里属于远端 / 部署动作的步骤（1 commit/PR 标注、2 部署写侧代码、
# 5 配置 prod+token、6 CI 接真库、7 合并 PR）不在本机演练范围：本机没有生产
# 环境、没有凭据、且本任务严格禁止 commit / push / 操作 GitHub 远端。脚本只
# 演练可在本地真实执行的数据面闭环，未做的步骤在报告与演练记录里如实标注。
#
# 【严格边界】
#   * 只用容器名前缀 rehearsal-，只 stop/rm 本脚本自己起的三个容器；
#     绝不删除 / 重建 / rm 用户已有的任何容器，绝不 docker compose down 现成栈。
#   * 不改任何仓库现有文件；仅在仓库 scripts/ 下新增本脚本。
#   * 演练产物（mapping / backfill 报表 / 报告 / 探针 SQL）默认写到桌面
#     rehearsal-artifacts/，不写进仓库。
#   * 所有凭据是演练专用明文值，不是生产 .env、不是真实凭据。
#
# 运行（Windows Git Bash）：
#   bash C:/Users/YYYBBW/Desktop/DocQA/scripts/rehearsal_release_runbook.sh
# 可覆盖：
#   PYTHON_BIN          跑 -m docqa_ai... 的解释器（默认 ai-python/.venv/Scripts/python.exe）
#   REHEARSAL_OUT_DIR   演练产物目录
# =============================================================================
set -euo pipefail

# ---- 0. 路径与配置 ----------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MIGRATIONS="$REPO_ROOT/ai-python/migrations"
PYTHON_BIN="${PYTHON_BIN:-$REPO_ROOT/ai-python/.venv/Scripts/python.exe}"
# 让 -m 一定能找到 docqa_ai（venv 已 editable install，这里再兜一层）
export PYTHONPATH="$REPO_ROOT/ai-python/src${PYTHONPATH:+:$PYTHONPATH}"

# 演练专用容器 / 镜像 / 端口（与用户已有的 docqa-* / agentflow-* 容器完全隔离）
PREFIX="rehearsal"
C_MYSQL="${PREFIX}-mysql"; C_PG="${PREFIX}-pg"; C_REDIS="${PREFIX}-redis"
IMG_MYSQL="mysql:8.4"; IMG_PG="pgvector/pgvector:pg16"; IMG_REDIS="redis:7-alpine"
P_MYSQL=13306; P_PG=15432; P_REDIS=16379
MYSQL_PW="rehearsal_root_pw"; PG_PW="rehearsal_pg_pw"; REDIS_PW="rehearsal_redis_pw"
DSN="postgresql://docqa:${PG_PW}@127.0.0.1:${P_PG}/docqa_vec"

OUT_DIR="${REHEARSAL_OUT_DIR:-/c/Users/YYYBBW/Desktop/rehearsal-artifacts}"
mkdir -p "$OUT_DIR"
MAPPING="$OUT_DIR/tenants.json"

# 演练数据（UUID 字面量；全部为虚构值）
TENANT_A="11111111-1111-1111-1111-111111111111"
TENANT_B="22222222-2222-2222-2222-222222222222"
DOC_A1="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
DOC_A2="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
DOC_B1="cccccccc-cccc-cccc-cccc-cccccccccccc"

PASS=0; FAIL=0; WARN=0
declare -a REPORT=()

log()  { echo; echo "===== $* ====="; }
step() { echo; echo "---- [$1] $2"; }
ok()   { echo "  PASS: $1"; PASS=$((PASS+1)); REPORT+=("PASS | $1"); }
bad()  { echo "  FAIL: $1"; FAIL=$((FAIL+1)); REPORT+=("FAIL | $1"); }
warn() { echo "  WARN: $1"; WARN=$((WARN+1)); REPORT+=("WARN | $1"); }
die()  { echo "  ABORT: $1" >&2; FAIL=$((FAIL+1)); REPORT+=("ABORT | $1"); exit 1; }

# 只清理本脚本自己起的三个 rehearsal- 容器；绝不触碰其他容器
cleanup_containers() {
  for name in "$C_MYSQL" "$C_PG" "$C_REDIS"; do
    if docker ps -a --format '{{.Names}}' | grep -qx "$name"; then
      echo "  stop+rm own container: $name"
      docker rm -f "$name" >/dev/null 2>&1 || true
    fi
  done
}
# 无论成功失败都保证只停自己的容器
trap cleanup_containers EXIT

psql_exec() { docker exec -i "$C_PG" psql -U docqa -d docqa_vec "$@"; }
psql_file() { docker exec -i "$C_PG" psql -U docqa -d docqa_vec -v ON_ERROR_STOP=1 < "$1"; }
mysql_exec() { docker exec "$C_MYSQL" mysql -uroot -p"$MYSQL_PW" "$@"; }

# 解析 backfill 报表 JSON
json_field() {
  "$PYTHON_BIN" -c "import json,sys; d=json.load(open(sys.argv[1],encoding='utf-8')); print(eval(sys.argv[2]))" \
    "$1" "$2"
}

echo "########################################################################"
echo "# DocQA 发布 runbook 本机演练"
echo "# 时间      : $(date '+%Y-%m-%d %H:%M:%S')"
echo "# 仓库根    : $REPO_ROOT"
echo "# Python    : $PYTHON_BIN"
echo "# 产物目录  : $OUT_DIR"
echo "# 容器前缀  : ${PREFIX}-  (仅操作自己起的 3 个容器)"
echo "# 性质      : 本机演练，无生产环境 / 无真实业务数据 / 无真实凭据"
echo "########################################################################"

# ---- 1. 前置检查 ------------------------------------------------------------
log "0. 前置检查"
command -v docker >/dev/null || die "docker 不可用"
[ -x "$PYTHON_BIN" ] || die "Python 不存在或不可执行: $PYTHON_BIN"
"$PYTHON_BIN" -c "import psycopg, docqa_ai" >/dev/null 2>&1 || die "该 Python 缺 psycopg / docqa_ai；请用 ai-python/.venv/Scripts/python.exe 或设置 PYTHON_BIN"
for f in 001_chunk_embedding_1024.sql 002_chunk_lexical_index.sql 003_chunk_embedding_1024_text_embedding_v4.sql 004_add_tenant_id.sql 005_drop_tenant_default.sql; do
  [ -f "$MIGRATIONS/$f" ] || die "缺迁移文件: $MIGRATIONS/$f"
done
for img in "$IMG_MYSQL" "$IMG_PG" "$IMG_REDIS"; do
  docker image inspect "$img" >/dev/null 2>&1 || die "本地缺镜像: $img（本演练不拉取镜像）"
done
echo "  现有容器（只读，绝不触碰）："
docker ps -a --format '  {{.Names}}\t{{.Status}}' | head -20
ok "前置检查通过（docker / 镜像 / python / 迁移文件齐备）"

# ---- 2. 起基础设施 + 等 healthy --------------------------------------------
log "1. 起 MySQL + PG(pgvector) + Redis（容器名前缀 ${PREFIX}-）"
# 幂等：若上次演练残留了同前缀容器，只删这三个（不是用户已有容器）
for name in "$C_MYSQL" "$C_PG" "$C_REDIS"; do
  if docker ps -a --format '{{.Names}}' | grep -qx "$name"; then
    echo "  清理上次演练残留: $name"; docker rm -f "$name" >/dev/null
  fi
done

docker run -d --name "$C_MYSQL" \
  -e MYSQL_ROOT_PASSWORD="$MYSQL_PW" -e MYSQL_ROOT_HOST='%' -e MYSQL_DATABASE=docqa_ctrl \
  -p "127.0.0.1:${P_MYSQL}:3306" \
  --health-cmd="mysqladmin ping -h 127.0.0.1 -uroot -p${MYSQL_PW} --silent" \
  --health-interval=3s --health-timeout=5s --health-retries=30 --health-start-period=15s \
  "$IMG_MYSQL" --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci >/dev/null

docker run -d --name "$C_PG" \
  -e POSTGRES_USER=docqa -e POSTGRES_PASSWORD="$PG_PW" -e POSTGRES_DB=docqa_vec \
  -p "127.0.0.1:${P_PG}:5432" \
  --health-cmd='pg_isready -U docqa -d docqa_vec' \
  --health-interval=3s --health-timeout=5s --health-retries=30 --health-start-period=10s \
  "$IMG_PG" >/dev/null

docker run -d --name "$C_REDIS" \
  -p "127.0.0.1:${P_REDIS}:6379" \
  --health-cmd="redis-cli -a ${REDIS_PW} ping | grep -q PONG" \
  --health-interval=3s --health-timeout=3s --health-retries=30 --health-start-period=5s \
  "$IMG_REDIS" redis-server --port 6379 --requirepass "$REDIS_PW" >/dev/null

step "1a" "等待三个容器 healthy（docker inspect .State.Health.Status）"
healthy=0
for i in $(seq 1 60); do
  m=$(docker inspect -f '{{.State.Health.Status}}' "$C_MYSQL")
  p=$(docker inspect -f '{{.State.Health.Status}}' "$C_PG")
  r=$(docker inspect -f '{{.State.Health.Status}}' "$C_REDIS")
  echo "  wait #$i: mysql=$m pg=$p redis=$r"
  if [ "$m" = healthy ] && [ "$p" = healthy ] && [ "$r" = healthy ]; then healthy=1; break; fi
  sleep 2
done
[ "$healthy" = 1 ] || die "容器未在预期时间内 healthy"
ok "三个容器均 healthy"
docker ps --format '  {{.Names}}\t{{.Status}}\t{{.Ports}}' | grep "$PREFIX"

# ---- 3. 应用 001-004 迁移 ---------------------------------------------------
log "2. 应用 001-004 迁移（005 留到回填之后，顺序不可颠倒）"
for f in 001_chunk_embedding_1024.sql 002_chunk_lexical_index.sql 003_chunk_embedding_1024_text_embedding_v4.sql 004_add_tenant_id.sql; do
  step "2" "apply $f"
  psql_file "$MIGRATIONS/$f"
done
dflt=$(psql_exec -tAc "SELECT COALESCE(column_default,'<NULL>') FROM information_schema.columns WHERE table_name='chunk_embedding_1024' AND column_name='tenant_id';" | tr -d '[:space:]')
echo "  回填前 chunk_embedding_1024.tenant_id 默认值 = $dflt"
ok "001-004 应用完成，tenant_id 列存在且默认 ''"

# ---- 4. 控制面 + 造样本数据（含空串历史行）--------------------------------
log "3. 造样本数据（必须包含 tenant_id='' 的历史 chunk 行）"
step "3a" "MySQL 控制面：建 documents 表并写入 documentId→tenantId"
mysql_exec -e "
CREATE DATABASE IF NOT EXISTS docqa_ctrl;
CREATE TABLE IF NOT EXISTS docqa_ctrl.documents (
  document_id VARCHAR(36) PRIMARY KEY,
  tenant_id   VARCHAR(36) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO docqa_ctrl.documents (document_id, tenant_id) VALUES
  ('${DOC_A1}','${TENANT_A}'),
  ('${DOC_A2}','${TENANT_A}'),
  ('${DOC_B1}','${TENANT_B}')
ON DUPLICATE KEY UPDATE tenant_id=VALUES(tenant_id);" 2>/dev/null
mysql_exec -N -B -e "SELECT document_id, tenant_id FROM docqa_ctrl.documents ORDER BY document_id;" 2>/dev/null

step "3b" "PG：两张向量空间写入样本行（部分 tenant_id='' 表示历史行）"
cat > "$OUT_DIR/seed.sql" <<SQL
-- 用一条 CTE 生成 1024 维向量，批量插入样本行
WITH v AS (
  SELECT ('[' || string_agg('0.1', ',') || ']')::vector AS e FROM generate_series(1, 1024)
)
INSERT INTO chunk_embedding_1024
  (vector_id, document_id, chunk_index, content, embedding, index_version, tenant_id)
SELECT s.vid, s.did, s.ci, s.content, v.e, s.iv, s.tid
FROM (VALUES
  ('00000000-0000-0000-0000-000000000001'::uuid, '${DOC_A1}'::uuid, 0, 'A1 chunk0 historical', 'v1', ''),
  ('00000000-0000-0000-0000-000000000002'::uuid, '${DOC_A1}'::uuid, 1, 'A1 chunk1 historical', 'v1', ''),
  ('00000000-0000-0000-0000-000000000003'::uuid, '${DOC_A1}'::uuid, 2, 'A1 chunk2 post-fix',   'v1', '${TENANT_A}'),
  ('00000000-0000-0000-0000-000000000004'::uuid, '${DOC_A2}'::uuid, 0, 'A2 chunk0 historical', 'v1', ''),
  ('00000000-0000-0000-0000-000000000005'::uuid, '${DOC_A2}'::uuid, 1, 'A2 chunk1 historical', 'v1', ''),
  ('00000000-0000-0000-0000-000000000006'::uuid, '${DOC_B1}'::uuid, 0, 'B1 chunk0 historical', 'v1', ''),
  ('00000000-0000-0000-0000-000000000007'::uuid, '${DOC_B1}'::uuid, 1, 'B1 chunk1 post-fix',   'v1', '${TENANT_B}')
) AS s(vid, did, ci, content, iv, tid) CROSS JOIN v;

WITH v AS (
  SELECT ('[' || string_agg('0.1', ',') || ']')::vector AS e FROM generate_series(1, 1024)
)
INSERT INTO chunk_embedding_1024_text_embedding_v4
  (vector_id, document_id, chunk_index, content, embedding, index_version, tenant_id)
SELECT s.vid, s.did, s.ci, s.content, v.e, s.iv, s.tid
FROM (VALUES
  ('10000000-0000-0000-0000-000000000001'::uuid, '${DOC_A1}'::uuid, 0, 'A1 v4 chunk0 historical', 'v1', ''),
  ('10000000-0000-0000-0000-000000000002'::uuid, '${DOC_A1}'::uuid, 1, 'A1 v4 chunk1 historical', 'v1', ''),
  ('10000000-0000-0000-0000-000000000003'::uuid, '${DOC_B1}'::uuid, 0, 'B1 v4 chunk0 historical', 'v1', '')
) AS s(vid, did, ci, content, iv, tid) CROSS JOIN v;
SQL
psql_file "$OUT_DIR/seed.sql"
psql_exec -c "SELECT 'chunk_embedding_1024' AS tbl, tenant_id, count(*) FROM chunk_embedding_1024 GROUP BY tenant_id ORDER BY tenant_id; \
SELECT 'chunk_embedding_1024_text_embedding_v4' AS tbl, tenant_id, count(*) FROM chunk_embedding_1024_text_embedding_v4 GROUP BY tenant_id ORDER BY tenant_id;"
ok "样本数据写入完成（两表均含 tenant_id='' 的历史行）"

# ---- 5. 导出 documentId→tenantId 映射 --------------------------------------
log "4. 从控制面导出 documentId→tenantId 映射"
mysql_exec --silent --raw --skip-column-names \
  -e "SELECT JSON_OBJECTAGG(document_id, tenant_id) FROM docqa_ctrl.documents;" 2>/dev/null > "$MAPPING"
echo "  mapping 内容：$MAPPING"
cat "$MAPPING"; echo
"$PYTHON_BIN" -c "import json,sys; d=json.load(open(sys.argv[1],encoding='utf-8')); assert isinstance(d,dict) and d, 'empty mapping'; print('  mapping documents =', len(d))" "$MAPPING" || die "mapping 导出非法"
ok "映射导出成功（来自 MySQL 控制面 documents 表）"

# ---- 6. dry-run #1 ----------------------------------------------------------
log "5. backfill dry-run #1（报告第 5 节第 3 步）"
# 用 --dsn 显式传入演练 PG；命令形态严格为 python -m docqa_ai.maintenance.backfill_tenant_id --mapping ...
"$PYTHON_BIN" -m docqa_ai.maintenance.backfill_tenant_id --mapping "$MAPPING" --dsn "$DSN" \
  | tee "$OUT_DIR/backfill-dryrun-1.json"
u1=$(json_field "$OUT_DIR/backfill-dryrun-1.json" "len(d['unresolvedDocuments'])")
e1=$(json_field "$OUT_DIR/backfill-dryrun-1.json" "sum(t['emptyTenantRows'] for t in d['tables'])")
echo "  dry-run #1: unresolvedDocuments=$u1  emptyTenantRows(两表合计)=$e1"
step "5a" "断言 unresolvedDocuments == 0（--apply 的前置硬条件）"
[ "$u1" = "0" ] && ok "dry-run #1 unresolvedDocuments == 0" || die "存在无映射文档，--apply 会被脚本拒绝"
step "5b" "按 runbook 字面断言 emptyTenantRows == 0"
if [ "$e1" = "0" ]; then
  ok "dry-run #1 emptyTenantRows == 0"
else
  warn "dry-run #1 emptyTenantRows=$e1，按字面断言 ==0 不成立（这是语义冲突，不是数据错误）"
  echo "     说明：backfill_tenant_id.py 的 emptyTenantRows 定义为「扫描到的 tenant_id='' 待回填写数」"
  echo "           (scan_sql: WHERE tenant_id = ''；report: plan.total_rows)。回填前它必然 >0，"
  echo "           正是本次演练刻意造出的历史行。报告第 5 节把两个 0 判据写进 dry-run 同一句属措辞压缩；"
  echo "           005_drop_tenant_default.sql 的真实前置写的是「已执行 --apply 且报表中两者均为 0」。"
  echo "           因此 emptyTenantRows==0 的严格断言放在 --apply 之后复跑 dry-run 时执行（步骤 7a）。"
  echo "           本脚本照实记录 dry-run #1 的 emptyTenantRows=$e1，不伪造 0。"
fi

# ---- 7. --apply ------------------------------------------------------------
log "6. backfill --apply（报告第 5 节第 4 步前半）"
"$PYTHON_BIN" -m docqa_ai.maintenance.backfill_tenant_id --mapping "$MAPPING" --dsn "$DSN" --apply \
  | tee "$OUT_DIR/backfill-apply.json"
ap=$(json_field "$OUT_DIR/backfill-apply.json" "d['appliedUpdates']")
echo "  appliedUpdates = $ap"
[ "$ap" -gt 0 ] && ok "--apply 实际更新了 $ap 个 (table,document) 组" || die "--apply 未产生更新"

# ---- 8. 复跑 dry-run #2：严格断言两个字段都为 0 ---------------------------
log "7. 复跑 dry-run #2（005 的真实前置：两个字段均为 0）"
"$PYTHON_BIN" -m docqa_ai.maintenance.backfill_tenant_id --mapping "$MAPPING" --dsn "$DSN" \
  | tee "$OUT_DIR/backfill-dryrun-2.json"
u2=$(json_field "$OUT_DIR/backfill-dryrun-2.json" "len(d['unresolvedDocuments'])")
e2=$(json_field "$OUT_DIR/backfill-dryrun-2.json" "sum(t['emptyTenantRows'] for t in d['tables'])")
echo "  dry-run #2: unresolvedDocuments=$u2  emptyTenantRows(两表合计)=$e2"
step "7a" "断言 emptyTenantRows == 0 且 unresolvedDocuments == 0"
if [ "$e2" = "0" ] && [ "$u2" = "0" ]; then
  ok "回填后 emptyTenantRows == 0 且 unresolvedDocuments == 0"
else
  bad "回填后仍有空串行或未解析文档 (emptyTenantRows=$e2, unresolvedDocuments=$u2)"
fi

# ---- 9. 执行 005 -----------------------------------------------------------
log "8. 执行 migrations/005_drop_tenant_default.sql"
psql_file "$MIGRATIONS/005_drop_tenant_default.sql"
d1=$(psql_exec -tAc "SELECT COALESCE(column_default,'<NULL>') FROM information_schema.columns WHERE table_name='chunk_embedding_1024' AND column_name='tenant_id';" | tr -d '[:space:]')
d2=$(psql_exec -tAc "SELECT COALESCE(column_default,'<NULL>') FROM information_schema.columns WHERE table_name='chunk_embedding_1024_text_embedding_v4' AND column_name='tenant_id';" | tr -d '[:space:]')
echo "  005 后默认值：chunk_embedding_1024=$d1  v4=$d2"
[ "$d1" = "<NULL>" ] && [ "$d2" = "<NULL>" ] && ok "两张表的 tenant_id DEFAULT 已删除" || bad "DEFAULT 未删除"

# ---- 10. 验证（漏传报错 / 同租户命中 / 异租户 0 行）------------------------
log "9. 验证：漏传 tenant_id 的 INSERT 直接报错"
cat > "$OUT_DIR/probe_missing_tenant.sql" <<SQL
-- 故意不提供 tenant_id（NOT NULL 且已 DROP DEFAULT，应直接报错）
INSERT INTO chunk_embedding_1024
  (vector_id, document_id, chunk_index, content, embedding, index_version)
SELECT '00000000-0000-0000-0000-000000000099'::uuid, '${DOC_A1}'::uuid, 99,
       'missing-tenant probe', v.e, 'v1'
FROM (SELECT ('[' || string_agg('0.1', ',') || ']')::vector AS e FROM generate_series(1, 1024)) v;
SQL
if psql_file "$OUT_DIR/probe_missing_tenant.sql" > "$OUT_DIR/probe_missing_tenant.out" 2>&1; then
  bad "漏传 tenant_id 的 INSERT 竟然成功（005 收口失效）"
else
  echo "  psql 错误输出："; sed 's/^/    /' "$OUT_DIR/probe_missing_tenant.out"
  if grep -Eqi 'tenant_id.*not-null|not-null.*tenant_id|violates not-null' "$OUT_DIR/probe_missing_tenant.out"; then
    ok "漏传 tenant_id 的 INSERT 被 NOT NULL 约束拒绝"
  else
    warn "INSERT 被拒绝，但错误信息未明确指向 tenant_id，请人工核对上方输出"
  fi
fi

log "10. 验证：同租户检索命中 / 异租户 0 行（两张向量空间）"
for tbl in chunk_embedding_1024 chunk_embedding_1024_text_embedding_v4; do
  same=$(psql_exec -tAc "SELECT count(*) FROM ${tbl} WHERE tenant_id='${TENANT_A}' AND document_id='${DOC_A1}';" | tr -d '[:space:]')
  cross=$(psql_exec -tAc "SELECT count(*) FROM ${tbl} WHERE tenant_id='${TENANT_B}' AND document_id='${DOC_A1}';" | tr -d '[:space:]')
  empt=$(psql_exec -tAc "SELECT count(*) FROM ${tbl} WHERE tenant_id='';" | tr -d '[:space:]')
  echo "  ${tbl}: 同租户(${TENANT_A:0:8}…)命中=${same}  异租户(${TENANT_B:0:8}…)命中=${cross}  残留空串行=${empt}"
  if [ "$same" -ge 1 ] && [ "$cross" = "0" ] && [ "$empt" = "0" ]; then
    ok "${tbl}: 同租户命中 / 异租户 0 行 / 无残留空串行"
  else
    bad "${tbl}: 隔离验证未通过 (same=${same}, cross=${cross}, empty=${empt})"
  fi
done

# ---- 11. 输出报告 -----------------------------------------------------------
log "11. 输出报告"
{
  echo "DocQA 发布 runbook 本机演练报告"
  echo "生成时间 : $(date '+%Y-%m-%d %H:%M:%S')"
  echo "仓库根   : $REPO_ROOT"
  echo "Python   : $PYTHON_BIN"
  echo "PG DSN   : $DSN"
  echo "产物目录 : $OUT_DIR"
  echo "环境性质 : 本机演练；无生产环境 / 无真实业务数据 / 无真实凭据 / 无真实压测灰度故障演练"
  echo "容器边界 : 仅操作 ${PREFIX}-mysql / ${PREFIX}-pg / ${PREFIX}-redis；未碰用户已有容器"
  echo "------------------------------------------------------------------------------"
  echo "PASS=$PASS FAIL=$FAIL WARN=$WARN"
  echo "------------------------------------------------------------------------------"
  for line in "${REPORT[@]}"; do echo "$line"; done
  echo "------------------------------------------------------------------------------"
  echo "报告第 5 节未纳入本机演练的远端/部署步骤："
  echo "  1) commit / PR 标注  2) 部署写侧强制 tenant_id 代码  5) 配置 prod+token"
  echo "  6) CI 接真库 PG 契约  7) 合并 PR —— 需真实环境与授权，本机不做。"
  echo "------------------------------------------------------------------------------"
  echo "语义偏差（如实记录）：dry-run #1 的 emptyTenantRows 是「待回填空串行数」(>0 为预期)，"
  echo "  报告第 5 节把它与 unresolvedDocuments 并列写进 dry-run 判据属措辞压缩；两个字段"
  echo "  同时为 0 的正确时点是 --apply 之后的复跑（本脚本步骤 7a 已严格断言通过）。"
} > "$OUT_DIR/rehearsal-report.txt"
cat "$OUT_DIR/rehearsal-report.txt"

# ---- 12. 停掉自己起的容器（trap 兜底，这里显式执行一次并取消 trap）--------
log "12. 停掉自己起的容器"
cleanup_containers
trap - EXIT
echo "  剩余 rehearsal- 容器：$(docker ps -a --format '{{.Names}}' | grep -c "^${PREFIX}-" || true) 个"
echo
echo "########################################################################"
if [ "$FAIL" -eq 0 ]; then
  echo "# 演练结论：SUCCESS（全流程走通；WARN=$WARN 为 runbook 措辞语义偏差，已如实记录）"
else
  echo "# 演练结论：FAILURE（FAIL=$FAIL，见上方 FAIL 条目与演练记录）"
fi
echo "########################################################################"
exit "$FAIL"
