import { defineStore } from 'pinia';
import { fetchCurrentUser, login, logout, refreshSession, register } from '../api/auth';
import { ApiError } from '../api/errors';
import type { CurrentUserData, LoginData, UserRole, UserStatus } from '../types/api';

interface AuthState {
  token: string | null;
  userId: string | null;
  username: string | null;
  status: UserStatus | null;
  roles: UserRole[];
  /**
   * `/auth/me` 回显的剩余秒数快照（契约 `accessTokenExpiresIn`，1–900）。
   * 只在每次读到 `/auth/me` 时刷新，界面按快照呈现、不本地倒数。
   */
  accessTokenExpiresIn: number | null;
  resolved: boolean;
  /** 同一时刻只允许一次刷新在途，多个 401 共用同一个结果，避免轮换链被并发请求互相作废。 */
  refreshInFlight: Promise<string | null> | null;
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: null,
    userId: null,
    username: null,
    status: null,
    roles: [],
    accessTokenExpiresIn: null,
    resolved: false,
    refreshInFlight: null,
  }),

  getters: {
    isAuthenticated: (state): boolean => state.token !== null,
    isAdmin: (state): boolean => state.roles.includes('ADMIN'),
  },

  actions: {
    async signIn(username: string, password: string): Promise<void> {
      this.acceptSession(await login(username, password));
      await this.loadCurrentUser();
    },

    async signUp(username: string, password: string): Promise<void> {
      this.acceptSession(await register(username, password));
      await this.loadCurrentUser();
    },

    /** 访问令牌只放在内存里；刷新令牌由浏览器持有，页面脚本读不到也写不了。 */
    acceptSession(data: LoginData): void {
      this.token = data.accessToken;
    },

    async loadCurrentUser(): Promise<void> {
      this.applyUser(await fetchCurrentUser());
      this.resolved = true;
    },

    applyUser(user: CurrentUserData): void {
      this.userId = user.userId;
      this.username = user.username;
      this.status = user.status;
      this.roles = [...user.roles];
      this.accessTokenExpiresIn = user.accessTokenExpiresIn;
    },

    /**
     * 页面加载后没有内存令牌，唯一恢复会话的途径是让浏览器带上 HttpOnly 刷新 Cookie。
     * 刷新失败等同于未登录；网络故障也不保留伪会话，用户重新登录即可。
     */
    async hydrate(): Promise<void> {
      if (this.resolved) {
        return;
      }
      const token = await this.refreshAccessToken();
      if (token === null) {
        this.clear();
        return;
      }
      try {
        this.applyUser(await fetchCurrentUser());
        this.resolved = true;
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          this.clear();
          return;
        }
        // 非 401（/auth/me 网络抖动、5xx）：原实现吞掉异常却仍置 resolved=true，
        // 留下「token 在、user/roles 全空」的伪会话让守卫判定已登录，且此后不再重试。
        // 改为清空本地状态、保持 resolved=false，让下一次导航的守卫重新水合（即重试）。
        // clear() 内部会把 resolved 置真，这里必须显式改回 false。
        // 守卫只在 !resolved 时水合；/login 允许匿名访问、守卫直接提交，不会形成重定向环。
        this.clear();
        this.resolved = false;
      }
    },

    refreshAccessToken(): Promise<string | null> {
      if (this.refreshInFlight !== null) {
        return this.refreshInFlight;
      }
      const pending = refreshSession()
        .then((data) => {
          this.token = data.accessToken;
          return data.accessToken;
        })
        .catch(() => null)
        .finally(() => {
          this.refreshInFlight = null;
        });
      this.refreshInFlight = pending;
      return pending;
    },

    /**
     * 本地状态先行清空，界面立即回到未登录；服务端吊销是尽力而为——
     * 它决定刷新 Cookie 是否被清掉，失败不回滚本地退出。
     */
    async signOut(): Promise<void> {
      const token = this.token;
      this.clear();
      try {
        await logout(token ?? undefined);
      } catch {
        // 服务端不可达时 Cookie 仍可能存活，下一次静默续期会重新恢复会话，这是已知取舍。
      }
    },

    clear(): void {
      this.token = null;
      this.userId = null;
      this.username = null;
      this.status = null;
      this.roles = [];
      this.accessTokenExpiresIn = null;
      this.resolved = true;
      this.refreshInFlight = null;
    },
  },
});
