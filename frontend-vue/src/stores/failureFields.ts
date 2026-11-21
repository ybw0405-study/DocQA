import { ApiError } from '../api/errors';

/**
 * 列表/表单 store 共有的失败态字段。`retryAfterSeconds` 刻意不在这里：契约只在 429 上声明
 * `Retry-After`，只有少数 store 维护它。写入时按「目标本来是否持有这个键」决定，
 * 免得给三字段状态凭空添键——`'x' in store.$state` 那类断言钉的就是状态形状。
 */
export interface FailureFields {
  detail: string | null;
  code: string | null;
  traceId: string | null;
}

interface RateLimitedFailureFields extends FailureFields {
  retryAfterSeconds: number | null;
}

/** 契约 `PageLimit` 默认值；各列表页显式发送，让「每页 20 条」在请求里可见。 */
export const PAGE_LIMIT = 20;

/** 游标 400 的唯一正确动作是回第一页：keyset 锚点失效后停在坏游标上只会漏读更旧的行。 */
export function isStaleCursor(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400;
}

/** 目标维护 Retry-After 字段时才同步它；3 字段状态保持自己的字段集合不变。 */
function writeRetryAfter(target: FailureFields, value: number | null): void {
  if ('retryAfterSeconds' in target) {
    (target as RateLimitedFailureFields).retryAfterSeconds = value;
  }
}

/**
 * 生成绑定了兜底文案的失败写入函数：兜底文案由各页自己给，调用点不必重复传它。
 * 服务端给了 ProblemDetail 就原样转述，否则用兜底文案并把本地字段清空。
 */
export function makeApplyFailure(fallback: string): (target: FailureFields, error: unknown) => void {
  return (target, error) => {
    if (error instanceof ApiError) {
      target.detail = error.detail;
      target.code = error.code;
      target.traceId = error.traceId;
      writeRetryAfter(target, error.retryAfterSeconds);
      return;
    }
    target.detail = fallback;
    target.code = null;
    target.traceId = null;
    writeRetryAfter(target, null);
  };
}

/** 本地预检不通过：没有服务端响应，也就没有 code、traceId 与建议等待。 */
export function rejectLocal(target: FailureFields, message: string): void {
  target.detail = message;
  target.code = null;
  target.traceId = null;
  writeRetryAfter(target, null);
}

/** 请求开始前或成功后清空失败态，字段口径与 makeApplyFailure 一致。 */
export function clearFailureFields(target: FailureFields): void {
  target.detail = null;
  target.code = null;
  target.traceId = null;
  writeRetryAfter(target, null);
}
