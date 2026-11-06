import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  THEME_PREFERENCES,
  THEME_STORAGE_KEY,
  applyTheme,
  isThemePreference,
  readThemePreference,
  saveThemePreference,
} from './theme';

const ATTRIBUTE = 'data-theme';

afterEach(() => {
  document.documentElement.removeAttribute(ATTRIBUTE);
});

describe('theme preference', () => {
  it('offers exactly three preferences and recognises none other', () => {
    expect(THEME_PREFERENCES).toEqual(['auto', 'light', 'dark']);
    for (const preference of THEME_PREFERENCES) {
      expect(isThemePreference(preference)).toBe(true);
    }
    for (const value of [null, undefined, '', 'AUTO', 'dark ', 'system', 0, { theme: 'dark' }]) {
      expect(isThemePreference(value)).toBe(false);
    }
  });

  it('falls back to auto for a missing or foreign stored value', () => {
    expect(readThemePreference()).toBe('auto');

    localStorage.setItem(THEME_STORAGE_KEY, 'solarized');
    expect(readThemePreference()).toBe('auto');

    localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    expect(readThemePreference()).toBe('dark');
  });

  it('writes the attribute only for a manual choice', () => {
    applyTheme('light');
    expect(document.documentElement.getAttribute(ATTRIBUTE)).toBe('light');

    applyTheme('dark');
    expect(document.documentElement.getAttribute(ATTRIBUTE)).toBe('dark');

    // auto 必须让属性消失，否则 prefers-color-scheme 就被一个「跟随系统」的假承诺挡住了。
    applyTheme('auto');
    expect(document.documentElement.hasAttribute(ATTRIBUTE)).toBe(false);
  });

  it('remembers a manual choice and forgets auto', () => {
    saveThemePreference('dark');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(document.documentElement.getAttribute(ATTRIBUTE)).toBe('dark');

    saveThemePreference('auto');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
    expect(document.documentElement.hasAttribute(ATTRIBUTE)).toBe(false);
  });

  it('paints the stored preference as soon as the bootstrap module loads', async () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    // 动态 import 前必须清空模块缓存，否则命中的是上一个用例已经执行过副作用的实例。
    vi.resetModules();
    await import('./theme-bootstrap');

    expect(document.documentElement.getAttribute(ATTRIBUTE)).toBe('dark');
  });

  it('leaves the document untouched when nothing is stored', async () => {
    vi.resetModules();
    await import('./theme-bootstrap');

    expect(document.documentElement.hasAttribute(ATTRIBUTE)).toBe(false);
  });
});
