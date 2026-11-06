import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';

import App from './App.vue';
import { routes } from './router';
import { useAuthStore } from './stores/auth';
import { THEME_STORAGE_KEY } from './theme';
import { server } from '../tests/server';

const ATTRIBUTE = 'data-theme';

/** 主题开关与登录态无关，因此挂载时不需要会话桥接，也不需要真实守卫。 */
async function mountHeader(options: { attachTo?: HTMLElement } = {}) {
  setActivePinia(createPinia());
  const instance = createRouter({ history: createMemoryHistory(), routes });
  await instance.push('/login');
  await instance.isReady();
  return mount(App, { global: { plugins: [instance] }, ...options });
}

afterEach(() => {
  document.documentElement.removeAttribute(ATTRIBUTE);
});

describe('theme switch in the app header', () => {
  it('offers the three preferences to an anonymous visitor as well', async () => {
    const wrapper = await mountHeader();
    const options = wrapper.findAll('.theme-switch option');

    expect(wrapper.find('.theme-switch').exists()).toBe(true);
    expect(options.map((option) => option.attributes('value'))).toEqual(['auto', 'light', 'dark']);
    expect(options.map((option) => option.text())).toEqual(['自动', '浅色', '深色']);
  });

  it('starts from the stored preference instead of a hard-coded default', async () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'light');
    const wrapper = await mountHeader();
    const select = wrapper.get('.theme-switch select').element as HTMLSelectElement;

    expect(select.value).toBe('light');
  });

  it('persists a manual choice and stamps the document element', async () => {
    const wrapper = await mountHeader();
    await wrapper.get('.theme-switch select').setValue('dark');

    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(document.documentElement.getAttribute(ATTRIBUTE)).toBe('dark');
  });

  it('clears both the stored value and the attribute when returning to auto', async () => {
    const wrapper = await mountHeader();
    const select = wrapper.get('.theme-switch select');
    await select.setValue('dark');
    await select.setValue('auto');

    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
    expect(document.documentElement.hasAttribute(ATTRIBUTE)).toBe(false);
  });

  it('ignores a value outside its own vocabulary', async () => {
    const wrapper = await mountHeader();
    const select = wrapper.get('.theme-switch select');
    await select.setValue('dark');
    const element = select.element as HTMLSelectElement;

    // 直接改 DOM 再派发 change，绕开 setValue 的合法性假设，模拟被脚本篡改的选择器。
    element.value = 'solarized';
    await select.trigger('change');

    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(document.documentElement.getAttribute(ATTRIBUTE)).toBe('dark');
  });
});

describe('skip link', () => {
  let host: HTMLElement;

  beforeEach(() => {
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(() => {
    host.remove();
  });

  it('comes before the header and stays out of the route-driven nav', async () => {
    const wrapper = await mountHeader({ attachTo: host });
    const link = wrapper.get('.skip-link');

    expect(host.querySelectorAll('a[href], button, select, input')[0]).toBe(link.element);
    expect(link.text()).toBe('跳到主内容');
    expect(link.attributes('href')).toBe('#main-content');
    expect(wrapper.find('.app-header__nav a.skip-link').exists()).toBe(false);
  });

  it('hands the focus to the main region instead of trusting fragment navigation', async () => {
    const wrapper = await mountHeader({ attachTo: host });
    await wrapper.get('.skip-link').trigger('click');
    const main = document.getElementById('main-content');

    expect(main?.getAttribute('tabindex')).toBe('-1');
    expect(document.activeElement).toBe(main);
  });
});

describe('sign out from the header', () => {
  /**
   * `signOut()` 只清本地会话、不动路由（S10-M4 的原始形态）：清完还停在原页面，
   * 界面看似在线，此后每个请求都带不上令牌、逐个 401。本用例钉住「清会话之后必须发起导航」。
   */
  it('navigates to the login page instead of leaving a logged-out session on screen', async () => {
    server.use(mswHttp.post('/api/v1/auth/logout', () => new HttpResponse(null, { status: 204 })));

    setActivePinia(createPinia());
    const instance = createRouter({ history: createMemoryHistory(), routes });
    await instance.push('/login');
    await instance.isReady();
    const wrapper = mount(App, { global: { plugins: [instance] } });

    // 先匿名挂载再补令牌：挂载时若已登录，`/login` 会被守卫弹回首页，而首页会触发它自己的取数。
    const auth = useAuthStore();
    auth.token = 'demo-token';
    auth.roles = ['USER'];
    auth.resolved = true;
    await wrapper.vm.$nextTick();

    const replaceSpy = vi.spyOn(instance, 'replace');
    await wrapper.get('[data-testid="sign-out"]').trigger('click');

    await vi.waitFor(() => expect(replaceSpy).toHaveBeenCalledWith({ name: 'login' }));
    expect(auth.token).toBeNull();
    expect(wrapper.find('[data-testid="sign-out"]').exists()).toBe(false);
  });
});
