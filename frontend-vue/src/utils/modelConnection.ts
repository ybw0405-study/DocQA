import type { ModelProbeFailure } from '../types/modelConnections';

/**
 * 模型连接向导的纯函数助手。
 *
 * 厂商预设目录由服务端下发（`GET /admin/ai/model-connections/presets`），不在前端硬编码：
 * 前端红线禁止生产源码出现绝对服务地址，厂商端点属于同一类字面量。
 * 这里只放不含任何地址的展示与合并逻辑。
 */

/** 从 Base URL 推导主机名，用于展示与出网提示；解析失败返回空串而不是抛错。 */
export function hostOf(baseUrl: string): string {
  try {
    return new URL(baseUrl).hostname;
  } catch {
    return '';
  }
}

/** 合并手填清单与探测结果，去重且保持「先填的优先」顺序。 */
export function mergeModels(...groups: string[][]): string[] {
  const seen = new Set<string>();
  for (const group of groups) {
    for (const m of group) {
      const trimmed = m.trim();
      if (trimmed) seen.add(trimmed);
    }
  }
  return [...seen];
}

/** 探测失败原因翻成人话：这些错误多半是填错了，不该只甩一个内部枚举名。 */
export function probeFailureMessage(reason: ModelProbeFailure | null, detail: string | null): string {
  switch (reason) {
    case 'HTTP_4XX':
      return '端点拒绝了请求，检查 Base URL 路径与 API Key';
    case 'HTTP_5XX':
      return '端点暂时不可用，稍后重试';
    case 'UNREACHABLE':
      return '端点连接不上，检查 Base URL 与出网环境';
    case 'TIMEOUT':
      return '请求超时，检查网络或换一个端点';
    case 'NOT_OPENAI_COMPATIBLE':
      return '端点不像 OpenAI 兼容服务（/models 没有返回模型列表）';
    case 'EGRESS_BLOCKED':
      return '出网被策略拦截，检查主机白名单与服务端放行开关';
    case 'CREDENTIAL_MISSING':
      return '还没有保存 API Key';
    case 'CREDENTIAL_UNREADABLE':
      return '凭据无法解密，请重新录入 API Key（主密钥轮换后需保留旧密钥）';
    default:
      return detail ?? '连接失败';
  }
}

/** 探测状态的三态文案：`UNKNOWN` 是「从未探测」，不是「探测失败」。 */
export function probeStatusLabel(status: string): string {
  switch (status) {
    case 'OK':
      return '探测通过';
    case 'FAILED':
      return '探测失败';
    default:
      return '从未探测';
  }
}
