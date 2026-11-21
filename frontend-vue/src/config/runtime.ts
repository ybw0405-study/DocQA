/**
 * 运行时应用配置（CT-7 / CT-8）。
 *
 * 桶名与 API 前缀由托管层下发的 config.js 注入，绝不走 import.meta.env 烘焙进产物。
 * 这里只做「读取 + 白名单校验」：桶名会被拼进分片 PUT 的同源路径前缀，未校验的运行时值
 * 等于把上传出口放给任意路径，因此必须按 S3 / MinIO 桶名规则收敛后再参与 startsWith 判据。
 */
export interface AppConfig {
  readonly apiBase: string;
  readonly storageBucket: string;
}

declare global {
  interface Window {
    __APP_CONFIG__?: Partial<AppConfig>;
  }
}

const DEFAULT_BUCKET = 'docqa-documents';
const DEFAULT_API_BASE = '/api/v1';
// S3 / MinIO 桶名：3..63 位小写字母数字、点、连字符，首尾必须是字母或数字。
const BUCKET_PATTERN = /^[a-z0-9](?:[a-z0-9.-]{1,61}[a-z0-9])$/;

function resolveBucket(value: unknown): string {
  return typeof value === 'string' && BUCKET_PATTERN.test(value) ? value : DEFAULT_BUCKET;
}

function resolveApiBase(value: unknown): string {
  // 只允许同源相对前缀，且不得以协议相对地址开头（那会把请求引向外部主机）。
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')
    ? value
    : DEFAULT_API_BASE;
}

/** 每次读取都重新取 window 上的值：config.js 在 SPA 生命周期内可能被托管层热替换。 */
export function appConfig(): AppConfig {
  const raw: Partial<AppConfig> = typeof window === 'undefined' ? {} : (window.__APP_CONFIG__ ?? {});
  return {
    apiBase: resolveApiBase(raw.apiBase),
    storageBucket: resolveBucket(raw.storageBucket),
  };
}

/** 分片 PUT 允许的同源路径前缀；与 documents.ts 的白名单判据共用同一份来源。 */
export function storagePrefix(): string {
  return `/${appConfig().storageBucket}`;
}
