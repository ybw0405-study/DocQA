import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { router, routes } from './index';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import { currentUserHandler, DEMO_TOKEN, DEMO_USER, refreshHandler } from '../../tests/fixtures';
import { server } from '../../tests/server';

/**
 * 模拟一次全新页面加载：内存里的令牌必然为空，唯一的会话来源是浏览器携带的
 * HttpOnly 刷新 Cookie。测试里用 `refreshHandler()` 显式模拟「Cookie 仍然有效」。
 */
async function reload(refreshToken: string | null = null): Promise<void> {
  setActivePinia(createPinia());
  bindSessionToAuthStore();
  if (refreshToken !== null) {
    server.use(refreshHandler(refreshToken));
  }
  await router.replace('/login');
}

describe('router guard', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('sends an anonymous visitor to the login page with a redirect back', async () => {
    await reload();
    await router.push('/');

    expect(router.currentRoute.value.name).toBe('login');
    expect(router.currentRoute.value.query.redirect).toBe('/');
  });

  it('hydrates a session through the refresh cookie before deciding access', async () => {
    await reload(DEMO_TOKEN);
    await router.push('/');

    expect(router.currentRoute.value.name).toBe('home');
    expect(useAuthStore().userId).toBe(DEMO_USER.userId);
  });

  it('does not let an authenticated user sit on the login page', async () => {
    await reload(DEMO_TOKEN);
    await router.push('/login');

    expect(router.currentRoute.value.name).toBe('home');
  });

  it('drops the session and asks to log in again when the refreshed token is rejected', async () => {
    await reload('stale-token');
    await router.push('/');

    expect(router.currentRoute.value.name).toBe('login');
    expect(useAuthStore().token).toBeNull();
  });

  it('treats an unknown path as protected rather than public', async () => {
    await reload();
    await router.push('/documents');

    expect(router.currentRoute.value.name).toBe('login');
    expect(router.currentRoute.value.query.redirect).toBe('/documents');
  });

  it('shows the not-found route to an authenticated visitor on an unknown path', async () => {
    await reload(DEMO_TOKEN);
    await router.push('/no-such-page');

    expect(router.currentRoute.value.name).toBe('not-found');
  });

  /**
   * 非管理员手输管理端 URL：本地弹回 home，而不是让他落在一个每个请求都 403 的空页上。
   * 这条用例证明的是「入口判定读的是 hydrate 之后才知道的 roles」，不证明服务端放行——
   * 每个管理面操作的 403 `USR-FORBIDDEN-001` 由后端自己的测试担保。
   * 遍历集合取自路由表的 `requiresAdmin` 全集：再抄一份路径清单的话，新增一页就会静默漏测。
   */
  it('bounces a non-admin back to home from every admin page', async () => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    server.use(refreshHandler(DEMO_TOKEN), currentUserHandler(['USER']));
    await router.replace('/login');

    const adminPaths = routes.filter((route) => route.meta?.requiresAdmin === true).map((route) => route.path);
    expect(adminPaths.length).toBeGreaterThan(0);
    expect(useAuthStore().isAdmin).toBe(false);
    for (const path of adminPaths) {
      await router.push(path);
      expect(router.currentRoute.value.name).toBe('home');
      expect(router.currentRoute.value.path).toBe('/');
    }
  });

  it('lets an ADMIN session stay on an admin page', async () => {
    await reload(DEMO_TOKEN);
    await router.push('/admin/audit');

    expect(useAuthStore().isAdmin).toBe(true);
    expect(router.currentRoute.value.name).toBe('admin-audit');
  });
});
