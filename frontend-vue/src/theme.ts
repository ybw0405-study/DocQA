/**
 * 主题偏好是本项目唯一允许写入浏览器存储的状态，且只允许这一个 owner 文件：
 * tests/redlines.spec.ts 用「owner 文件 + 固定键名 + 文件内禁现凭据类字面量」三重白名单守住这条边界。
 */
export type ThemePreference = 'auto' | 'light' | 'dark';

export const THEME_STORAGE_KEY = 'docqa_theme';

export const THEME_PREFERENCES: readonly ThemePreference[] = ['auto', 'light', 'dark'];

const THEME_ATTRIBUTE = 'data-theme';

export function isThemePreference(value: unknown): value is ThemePreference {
  return typeof value === 'string' && THEME_PREFERENCES.includes(value as ThemePreference);
}

/** 读取偏好；缺失或值不合法一律回落到 auto（跟随系统，且不写属性）。 */
export function readThemePreference(): ThemePreference {
  const stored = localStorage.getItem(THEME_STORAGE_KEY);
  return isThemePreference(stored) ? stored : 'auto';
}

/** auto 时不写属性，让 CSS 的 prefers-color-scheme 成为唯一裁决者；手动态才写属性覆盖。 */
export function applyTheme(preference: ThemePreference): void {
  if (preference === 'auto') {
    document.documentElement.removeAttribute(THEME_ATTRIBUTE);
    return;
  }
  document.documentElement.setAttribute(THEME_ATTRIBUTE, preference);
}

export function saveThemePreference(preference: ThemePreference): void {
  if (preference === 'auto') {
    localStorage.removeItem(THEME_STORAGE_KEY);
  } else {
    localStorage.setItem(THEME_STORAGE_KEY, preference);
  }
  applyTheme(preference);
}
