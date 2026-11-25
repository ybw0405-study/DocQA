import type { ChangePasswordRequest } from '../types/users';
import type { CurrentUserData, LoginData } from '../types/api';
import { get, post, postNoContent } from './http';

export function login(username: string, password: string): Promise<LoginData> {
  return post<LoginData>('/auth/login', { username, password });
}

export function register(username: string, password: string): Promise<LoginData> {
  return post<LoginData>('/auth/register', { username, password });
}

/** 刷新令牌只存在于 HttpOnly Cookie，前端不读取、也不参与构造请求体。 */
export function refreshSession(): Promise<LoginData> {
  return post<LoginData>('/auth/refresh');
}

export function logout(accessToken?: string): Promise<void> {
  return postNoContent('/auth/logout', undefined, accessToken);
}

export function fetchCurrentUser(): Promise<CurrentUserData> {
  return get<CurrentUserData>('/auth/me');
}

/**
 * 修改口令（契约 `changePassword`，204 无响应体）。三件契约事实决定了调用方拿到 `void` 之后必须做什么：
 * 1. 必须提供当前口令，缺失或不正确统一返回 401 `USR-AUTH-001` 并**计入登录失败限流**（服务端刻意不给区分度）；
 * 2. 契约声明了 `IdempotencyKey` 参数，但本文件不拼这个头——共享拦截器已覆盖「非 GET 且缺该头」；
 * 3. 成功后服务端吊销该用户**全部**刷新令牌家族并把该用户的吊销水位推进到当前时刻，
 *    响应不含任何令牌，所以调用方必须随即清空本地会话并引导重新登录，不能尝试静默续期。
 */
export function changePassword(request: ChangePasswordRequest): Promise<void> {
  return postNoContent('/auth/password', request);
}
