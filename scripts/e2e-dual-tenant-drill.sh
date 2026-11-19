#!/usr/bin/env bash
# DocQA 本地双租户 HTTP 演练（D4 的「本地演练」档）
#
# 目的：在真实启动的全套服务上，用真实 HTTP 验证租户隔离与跨语言契约，
#       产出可复核的证据。不进 CI、不改产品版本、不引入测试框架依赖。
#
# 前置：
#   1. Docker Desktop 已运行，ops/env/local.env 已按 local.env.example 填好，
#      且已设置 DOCQA_TENANT_BOOTSTRAP_TOKEN（高熵值）。
#   2. 已执行 scripts/start-dev.ps1 起 5 个 Java 服务，并另起 Python 数据面
#      （.venv/Scripts/python.exe -m uvicorn docqa_ai.api.main:app --port 18000）。
#   3. 本机有 curl 与 ai-python/.venv/Scripts/python.exe（用于 JSON 解析 / JWT 签发）。
#
# 复现：从仓库根目录执行 bash scripts/e2e-dual-tenant-drill.sh
# 证据：.artifacts/dual-tenant-evidence/（gitignored）
#
# 注意：注册端点按来源 IP 做 5 次 / 120 秒限流；连续重跑请间隔 2 分钟以上。
#       演练会在本地库留下租户 / 用户 / 文档数据，属于预期副作用。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GATEWAY="${DOCQA_DRILL_GATEWAY:-http://127.0.0.1:18080}"
USER_SVC="${DOCQA_DRILL_USER_SVC:-http://127.0.0.1:18081}"
PYTHON_API="${DOCQA_DRILL_PYTHON_API:-http://127.0.0.1:18000}"
ENV_FILE="ops/env/local.env"
EVIDENCE=".artifacts/dual-tenant-evidence"
PY="ai-python/.venv/Scripts/python.exe"
RUN_ID="$(date +%s)"
TENANT_SLUG="acme-drill-${RUN_ID}"
USER_A="tenanta.${RUN_ID}"
USER_B="tenantb.${RUN_ID}"
PASS_A="TenantAPass2026!"
PASS_B="TenantBPass2026!"

mkdir -p "$EVIDENCE"

fail() { echo "FAIL: $*" >&2; exit 1; }
note() { echo "  $*"; }

# 取值时不回显，避免把 bootstrap token / JWT secret 打进日志。
env_val() {
  local key="$1"
  sed -n "s/^${key}=//p" "$ENV_FILE" | head -1
}

# req <证据名> <期望状态码> <curl 参数...>
# 响应体写入证据文件；返回码不符即失败。
req() {
  local name="$1" expected="$2"; shift 2
  local code
  code="$(curl -sS --noproxy '*' -o "$EVIDENCE/${name}.json" -w '%{http_code}' \
          --max-time 20 "$@" 2>/dev/null)" || code="ERR"
  if [ "$code" != "$expected" ]; then
    echo "  ${name}: HTTP=${code} (期望 ${expected})"
    head -c 300 "$EVIDENCE/${name}.json" 2>/dev/null || true
    echo
    fail "${name} 状态码不符"
  fi
  note "${name}: HTTP=${code}"
}

json_get() { "$PY" -c "import json,sys; d=json.load(open(sys.argv[1],encoding='utf-8')); print(eval(sys.argv[2]))" "$1" "$2"; }

echo "=== 0. 前置检查 ==="
[ -f "$ENV_FILE" ] || fail "缺少 $ENV_FILE"
BOOTSTRAP_TOKEN="$(env_val DOCQA_TENANT_BOOTSTRAP_TOKEN)"
[ -n "$BOOTSTRAP_TOKEN" ] || fail "local.env 未设置 DOCQA_TENANT_BOOTSTRAP_TOKEN"
JWT_SECRET="$(env_val DOCQA_JWT_SECRET)"
[ "${#JWT_SECRET}" -ge 32 ] || fail "DOCQA_JWT_SECRET 长度不足 32 字节"
[ -x "$PY" ] || [ -f "$PY" ] || fail "缺少 Python venv: $PY"
for url in "$USER_SVC/actuator/health" "$GATEWAY/actuator/health" "$PYTHON_API/health"; do
  code="$(curl -sS --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 5 "$url" 2>/dev/null || echo 000)"
  [ "$code" = "200" ] || fail "服务未就绪: $url (HTTP=$code)"
  note "ready: $url"
done

echo "=== 1. bootstrap 供给：防护、创建、幂等 ==="
TENANT_BODY="{\"slug\":\"${TENANT_SLUG}\",\"name\":\"Acme Drill\",\"tier\":\"STANDARD\",\"isolation\":\"SHARED\"}"
req 01-no-token 403 -X POST "$USER_SVC/api/v1/admin/tenants" \
    -H 'Content-Type: application/json' -d "$TENANT_BODY"
req 02-wrong-token 403 -X POST "$USER_SVC/api/v1/admin/tenants" \
    -H 'Content-Type: application/json' -H 'X-Bootstrap-Token: definitely-wrong' -d "$TENANT_BODY"
req 03-create-tenant 201 -X POST "$USER_SVC/api/v1/admin/tenants" \
    -H 'Content-Type: application/json' -H "X-Bootstrap-Token: $BOOTSTRAP_TOKEN" -d "$TENANT_BODY"
req 04-replay-idempotent 201 -X POST "$USER_SVC/api/v1/admin/tenants" \
    -H 'Content-Type: application/json' -H "X-Bootstrap-Token: $BOOTSTRAP_TOKEN" -d "$TENANT_BODY"
TENANT_B="$(json_get "$EVIDENCE/03-create-tenant.json" "d['data']['id']")"
TENANT_B_REPLAY="$(json_get "$EVIDENCE/04-replay-idempotent.json" "d['data']['id']")"
[ "$TENANT_B" = "$TENANT_B_REPLAY" ] || fail "幂等重放返回了不同的 tenant id"
note "第二租户 id=${TENANT_B}（重放一致）"

echo "=== 2. 双租户注册与 JWT tenant_id 断言 ==="
req 05-register-A 201 -X POST "$GATEWAY/api/v1/auth/register" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: drill-${RUN_ID}-a" \
    -d "{\"username\":\"${USER_A}\",\"password\":\"${PASS_A}\"}"
req 06-register-B 201 -X POST "$GATEWAY/api/v1/auth/register" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: drill-${RUN_ID}-b" \
    -d "{\"username\":\"${USER_B}\",\"password\":\"${PASS_B}\",\"tenantSlug\":\"${TENANT_SLUG}\"}"
TOKEN_A="$(json_get "$EVIDENCE/05-register-A.json" "d['data']['accessToken']")"
TOKEN_B="$(json_get "$EVIDENCE/06-register-B.json" "d['data']['accessToken']")"
read -r CLAIM_A SUB_A <<<"$("$PY" -c "
import base64,json,sys
t=sys.argv[1]; p=t.split('.')[1]; p+='='*(-len(p)%4)
c=json.loads(base64.urlsafe_b64decode(p)); print(c['tenant_id'], c['sub'])
" "$TOKEN_A")"
read -r CLAIM_B SUB_B <<<"$("$PY" -c "
import base64,json,sys
t=sys.argv[1]; p=t.split('.')[1]; p+='='*(-len(p)%4)
c=json.loads(base64.urlsafe_b64decode(p)); print(c['tenant_id'], c['sub'])
" "$TOKEN_B")"
[ "$CLAIM_A" != "$CLAIM_B" ] || fail "两个租户的 JWT tenant_id 相同"
[ "$CLAIM_B" = "$TENANT_B" ] || fail "第二租户 JWT 的 tenant_id 与 bootstrap 返回值不一致"
note "tenantA=${CLAIM_A}  tenantB=${CLAIM_B}（与 bootstrap 一致）"

echo "=== 3. 文档链路与跨租户隔离 ==="
MD5="$("$PY" -c "import hashlib;print(hashlib.md5(b'docqa-drill-${RUN_ID}').hexdigest())")"
req 07-A-upload 201 -X POST "$GATEWAY/api/v1/documents/upload/init" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: drill-${RUN_ID}-up-a" \
    -H "Authorization: Bearer $TOKEN_A" \
    -d "{\"fileName\":\"tenant-a.pdf\",\"sizeBytes\":2048,\"contentType\":\"application/pdf\",\"md5\":\"$MD5\"}"
DOC_A="$(json_get "$EVIDENCE/07-A-upload.json" "d['data']['documentId']")"
req 08-A-own-status 200 "$GATEWAY/api/v1/documents/$DOC_A/status" -H "Authorization: Bearer $TOKEN_A"
req 09-B-cross-status 404 "$GATEWAY/api/v1/documents/$DOC_A/status" -H "Authorization: Bearer $TOKEN_B"
req 10-A-list 200 "$GATEWAY/api/v1/documents" -H "Authorization: Bearer $TOKEN_A"
req 11-B-list 200 "$GATEWAY/api/v1/documents" -H "Authorization: Bearer $TOKEN_B"
LIST_A="$(cat "$EVIDENCE/10-A-list.json")"
LIST_B="$(cat "$EVIDENCE/11-B-list.json")"
echo "$LIST_A" | grep -qF "$DOC_A" || fail "A 的文档列表未包含自己的文档"
if echo "$LIST_B" | grep -qF "$DOC_A"; then fail "B 的文档列表泄露了 A 的文档"; fi
note "A 列表含 ${DOC_A}；B 列表为空且跨租户 status 返回 404"

echo "=== 4. 管理面隔离（本地密钥签发 ADMIN 演练身份）==="
# 说明：注册账号默认是 USER，管理面需要 ADMIN。用本地 DOCQA_JWT_SECRET 签发
# 仅用于演练的 ADMIN JWT（真实验签链路，非伪造响应），不改变服务端行为。
"$PY" - "$JWT_SECRET" "$CLAIM_A" "$SUB_A" "$CLAIM_B" "$SUB_B" "$EVIDENCE" <<'PYEOF'
import base64, hashlib, hmac, json, sys, time, uuid
secret, tenant_a, sub_a, tenant_b, sub_b, out = sys.argv[1:7]
def b64(b): return base64.urlsafe_b64encode(b).rstrip(b'=').decode()
def sign(tenant, sub):
    now = int(time.time())
    h = b64(json.dumps({'alg': 'HS256'}, separators=(',', ':')).encode())
    p = b64(json.dumps({'sub': sub, 'iss': 'docqa-user-service', 'aud': ['docqa-api'],
                        'iat': now, 'exp': now + 900, 'jti': str(uuid.uuid4()),
                        'roles': ['ADMIN'], 'tenant_id': tenant}, separators=(',', ':')).encode())
    s = b64(hmac.new(secret.encode('utf-8'), (h + '.' + p).encode('ascii'), hashlib.sha256).digest())
    return h + '.' + p + '.' + s
open(out + '/admA.token', 'w').write(sign(tenant_a, sub_a))
open(out + '/admB.token', 'w').write(sign(tenant_b, sub_b))
PYEOF
ADM_A="$(cat "$EVIDENCE/admA.token")"
ADM_B="$(cat "$EVIDENCE/admB.token")"
req 12-A-controls-before 200 "$GATEWAY/api/v1/admin/ai/controls/tenant" -H "Authorization: Bearer $ADM_A"
req 13-B-controls-before 200 "$GATEWAY/api/v1/admin/ai/controls/tenant" -H "Authorization: Bearer $ADM_B"
req 14-A-controls-enable 200 -X PUT "$GATEWAY/api/v1/admin/ai/controls/tenant" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $ADM_A" -d '{"killSwitchActive":true}'
req 15-A-controls-after 200 "$GATEWAY/api/v1/admin/ai/controls/tenant" -H "Authorization: Bearer $ADM_A"
req 16-B-controls-after 200 "$GATEWAY/api/v1/admin/ai/controls/tenant" -H "Authorization: Bearer $ADM_B"
[ "$(json_get "$EVIDENCE/15-A-controls-after.json" "d['data']['active']")" = "True" ] || fail "A 的租户熔断未生效"
[ "$(json_get "$EVIDENCE/16-B-controls-after.json" "d['data']['active']")" = "False" ] || fail "B 的租户熔断被 A 的写入污染"
req 17-A-users 200 "$GATEWAY/api/v1/users" -H "Authorization: Bearer $ADM_A"
req 18-B-users 200 "$GATEWAY/api/v1/users" -H "Authorization: Bearer $ADM_B"
grep -qF "$USER_B" "$EVIDENCE/17-A-users.json" && fail "A 的用户列表泄露了 B 的用户"
grep -qF "$USER_A" "$EVIDENCE/18-B-users.json" && fail "B 的用户列表泄露了 A 的用户"
note "A 熔断=true / B 熔断=false；用户列表互不可见"

echo "=== 5. 跨语言契约（直连 Python /agent/ask）==="
"$PY" - "$CLAIM_B" "$SUB_B" "$DOC_A" "$EVIDENCE" <<'PYEOF'
import json, sys, uuid
tenant, user, doc, out = sys.argv[1:5]
base = {'protocolVersion': '1.0', 'tenantId': tenant, 'userId': user,
        'traceId': 'b' * 32, 'conversationId': str(uuid.uuid4()),
        'question': '请概述这份文档的内容', 'piiMaskedQuestion': '请概述这份文档的内容',
        'piiMaskingVersion': 'pii-v1', 'tokenBudget': 512, 'maxToolCalls': 0,
        'maxRounds': 1, 'allowedTools': [], 'deadlineMs': 5000,
        'documentScopes': [{'documentId': doc, 'indexVersion': 'v1'}]}
missing = dict(base); missing.pop('tenantId')
extra = dict(base); extra['unexpectedField'] = 'drift'
json.dump(missing, open(out + '/19-missing-tenantId.req.json', 'w'))
json.dump(extra, open(out + '/20-extra-field.req.json', 'w'))
json.dump(base, open(out + '/21-valid.req.json', 'w'))
PYEOF
for n in 19-missing-tenantId 20-extra-field; do
  req "$n" 422 -X POST "$PYTHON_API/agent/ask" -H 'Content-Type: application/json' \
      --data-binary "@$EVIDENCE/${n}.req.json"
done
grep -qF 'tenantId' "$EVIDENCE/19-missing-tenantId.json" || fail "缺 tenantId 未被明确报出"
grep -qF 'Extra inputs are not permitted' "$EVIDENCE/20-extra-field.json" || fail "extra=forbid 未拦截多余字段"
# 合法请求在无向量数据时必须 fail-closed，不静默放行。
req 21-valid 503 -X POST "$PYTHON_API/agent/ask" -H 'Content-Type: application/json' \
    --data-binary "@$EVIDENCE/21-valid.req.json"
note "缺 tenantId / 多余字段均 422；合法请求 503 fail-closed"

echo "=== 6. 环境复位：关闭 A 的租户熔断 ==="
req 22-A-controls-reset 200 -X PUT "$GATEWAY/api/v1/admin/ai/controls/tenant" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $ADM_A" -d '{"killSwitchActive":false}'

echo ""
echo "ALL ASSERTIONS PASSED — 证据目录: $EVIDENCE"
echo "提醒：本地库留有本次演练的租户/用户/文档数据；RAG 解析链路受 Windows 无 librocketmq.so 限制未覆盖。"
