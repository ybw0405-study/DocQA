import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { useAuthStore } from '../stores/auth';

declare module 'vue-router' {
  interface RouteMeta {
    /** 缺省即「需要登录」：默认拒绝，只有显式 `false` 的路由允许匿名访问。 */
    requiresAuth?: boolean;
    /** 只追加更严的一层，不能脱离 `requiresAuth` 单独成立。 */
    requiresAdmin?: boolean;
  }
}

/**
 * 视图全部懒加载：打开 /login 只下载登录页与其依赖，ADMIN 页面按路由切分，
 * 不再把全部 12 个视图塞进首屏单 chunk。
 */
export const routes: RouteRecordRaw[] = [
  { path: '/', name: 'home', component: () => import('../views/HomeView.vue') },
  { path: '/documents', name: 'documents', component: () => import('../views/DocumentsView.vue') },
  { path: '/ask', name: 'ask', component: () => import('../views/AskView.vue') },
  { path: '/reminders', name: 'reminders', component: () => import('../views/RemindersView.vue') },
  // 管理端页面各自一个 URL：审计查询的游标与用量窗口要能进 query，塞进一个 /admin 的 tab 就做不到。
  { path: '/admin/ai-controls', name: 'admin-ai-controls', component: () => import('../views/admin/AdminControlsView.vue'), meta: { requiresAdmin: true } },
  { path: '/admin/tenant-controls', name: 'admin-tenant-controls', component: () => import('../views/admin/AdminTenantControlsView.vue'), meta: { requiresAdmin: true } },
  { path: '/admin/quotas', name: 'admin-quotas', component: () => import('../views/admin/AdminQuotasView.vue'), meta: { requiresAdmin: true } },
  { path: '/admin/audit', name: 'admin-audit', component: () => import('../views/admin/AdminAuditView.vue'), meta: { requiresAdmin: true } },
  { path: '/admin/usage', name: 'admin-usage', component: () => import('../views/admin/AdminUsageView.vue'), meta: { requiresAdmin: true } },
  { path: '/admin/model-connections', name: 'admin-model-connections', component: () => import('../views/admin/AdminModelConnectionsView.vue'), meta: { requiresAdmin: true } },
  { path: '/admin/memories', name: 'admin-memories', component: () => import('../views/admin/AdminMemoriesView.vue'), meta: { requiresAdmin: true } },
  { path: '/admin/users', name: 'admin-users', component: () => import('../views/admin/AdminUsersView.vue'), meta: { requiresAdmin: true } },
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { requiresAuth: false } },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue') },
];

export const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  if (!auth.resolved) {
    await auth.hydrate();
  }
  // 默认拒绝：只有显式声明 requiresAuth: false 的路由才允许匿名访问。
  if (to.meta.requiresAuth !== false && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }
  /**
   * `roles` 只有 `hydrate()` 之后才知道，所以这条判定必须排在会话就绪之后。
   * 本地弹回只是「不让非管理员落在一个必然 403 的空页」，服务端对每个管理面操作的 ADMIN 校验
   * （403 `USR-FORBIDDEN-001`）才是最终判据，绕开前端守卫并不会多拿到一个字节的数据。
   */
  if (to.meta.requiresAdmin === true && !auth.isAdmin) {
    return { name: 'home' };
  }
  if (to.name === 'login' && auth.isAuthenticated) {
    return { name: 'home' };
  }
  return true;
});
