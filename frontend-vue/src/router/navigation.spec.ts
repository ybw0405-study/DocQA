import { beforeEach, describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import App from '../App.vue';
import { router, routes } from './index';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import type { UserRole } from '../types/api';
import { DEMO_TOKEN, DEMO_USER } from '../../tests/fixtures';

const CATCH_ALL = '/:pathMatch(.*)*';

/** 带参数的路由（catch-all）没有可点的具体地址，匿名页由各自的 meta 声明排除。 */
const PAGES = routes
  .filter((route) => !route.path.includes(':') && route.meta?.requiresAuth !== false)
  .map((route) => route.path);

/** 导航入口按角色分两组：这两组之和必须等于受保护页全集，缺一组就是页面或入口失去对账。 */
const USER_PAGES = routes
  .filter((route) => !route.path.includes(':') && route.meta?.requiresAuth !== false && route.meta?.requiresAdmin !== true)
  .map((route) => route.path)
  .sort();

const ADMIN_PAGES = routes
  .filter((route) => route.meta?.requiresAdmin === true)
  .map((route) => route.path)
  .sort();

/** 用真实路由表 + 内存历史构造一个不带守卫的实例，只观察导航渲染。 */
async function mountChrome(token: string | null, roles: readonly UserRole[] = ['USER']) {
  setActivePinia(createPinia());
  bindSessionToAuthStore();
  const auth = useAuthStore();
  if (token !== null) {
    auth.$patch({ token, userId: DEMO_USER.userId, roles: [...roles], resolved: true });
  }
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push('/login');
  await router.isReady();
  const wrapper = mount(App, { global: { plugins: [router] } });
  /*
   * 入口分布在两处：命令条放登录/退出这类与身份相关的动作，工具轨放页面入口。
   * 两处合起来才是「可达集合」，任一处的 href 集合单独比对都会漏掉另一半；
   * 顺序仍不作约束（轨内按学习 → 治理 → 账户分组，与路由表顺序无关）。
   */
  return wrapper
    .findAll('.app-header__nav a, .app-rail a')
    .map((link) => link.attributes('href') ?? '')
    .sort();
}

describe('site navigation and route table', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('declares every path and route name exactly once', () => {
    const paths = routes.map((route) => route.path);
    const names = routes.map((route) => String(route.name));

    expect(new Set(paths).size).toBe(paths.length);
    expect(new Set(names).size).toBe(names.length);
    expect(names).toContain('login');
    expect(names).toContain('not-found');
  });

  it('keeps the catch-all route last so it cannot shadow a real page', () => {
    expect(routes.filter((route) => route.path === CATCH_ALL)).toHaveLength(1);
    expect(routes.at(-1)?.path).toBe(CATCH_ALL);
  });

  it('grants anonymous access only to the login page', () => {
    const open = routes.filter((route) => route.meta?.requiresAuth === false).map((route) => route.path);

    expect(open).toEqual(['/login']);
  });

  it('resolves every named route to the path it declares', () => {
    const probe = createRouter({ history: createMemoryHistory(), routes });
    for (const route of routes) {
      if (route.path.includes(':')) {
        continue;
      }
      const resolved = probe.resolve({ name: String(route.name) });
      expect(resolved.path).toBe(route.path);
      expect(resolved.name).toBe(route.name);
    }
  });

  it('marks exactly the eight admin pages and keeps the two nav groups covering every protected page', () => {
    expect(ADMIN_PAGES).toEqual([
      '/admin/ai-controls',
      '/admin/audit',
      '/admin/memories',
      '/admin/model-connections',
      '/admin/quotas',
      '/admin/tenant-controls',
      '/admin/usage',
      '/admin/users',
    ]);
    expect([...USER_PAGES, ...ADMIN_PAGES].sort()).toEqual([...PAGES].sort());
  });

  it('renders the user entries for a signed-in non-admin', async () => {
    expect(await mountChrome(DEMO_TOKEN, ['USER'])).toEqual(USER_PAGES);
  });

  it('adds the eight admin entries only for an ADMIN session', async () => {
    expect(await mountChrome(DEMO_TOKEN, DEMO_USER.roles)).toEqual([...USER_PAGES, ...ADMIN_PAGES].sort());
  });

  it('offers an anonymous visitor nothing but the login link', async () => {
    expect(await mountChrome(null)).toEqual(['/login']);
  });

  it('sends an anonymous visitor to login from every protected page, keeping the intended target', async () => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    const auth = useAuthStore();

    for (const path of PAGES) {
      // 无刷新 Cookie 的匿名访问由默认 401 桩决定，hydrate 失败即视为未登录。
      auth.$reset();
      await router.push(path);
      expect(router.currentRoute.value.name).toBe('login');
      expect(router.currentRoute.value.query.redirect).toBe(path);
    }
  });

  it('does not leave a signed-in visitor on the login page', async () => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().$patch({ token: DEMO_TOKEN, userId: DEMO_USER.userId, resolved: true });

    await router.push('/documents');
    expect(router.currentRoute.value.name).toBe('documents');
    await router.push('/login');
    expect(router.currentRoute.value.name).toBe('home');
  });
});
