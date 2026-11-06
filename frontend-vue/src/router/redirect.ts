const ABSOLUTE_URL_WITHIN_PATH = /^\/[a-z][a-z0-9+.-]*:/i;

/**
 * 只接受同源站内路径：`//host` 会被解析为协议相对 URL，`\` 在浏览器里等价于 `/`，
 * `http:/x` 之类的写法也能被某些 UA 识别为绝对地址，三者都可能把登录后的跳转变成开放重定向。
 * 非法值一律回落到首页。
 */
export function safeInternalPath(raw: unknown, fallback = '/'): string {
  if (typeof raw !== 'string' || raw.length === 0) {
    return fallback;
  }
  if (!raw.startsWith('/') || raw.startsWith('//') || raw.includes('\\') || ABSOLUTE_URL_WITHIN_PATH.test(raw)) {
    return fallback;
  }
  return raw;
}
