import { configureSession } from '../api/http';
import { useAuthStore } from './auth';

/**
 * 把 HTTP 层与鉴权状态接起来：令牌只在请求发出前一刻从 store 读取，
 * 401 先尝试静默续期，续期失败才结束会话并回到登录页。
 * 应用入口与测试共用这一处接线，避免「测试里能带上 Bearer、生产里忘了」的偏差。
 */
export function bindSessionToAuthStore(onUnauthorized?: () => void): void {
  const auth = useAuthStore();
  configureSession({
    accessToken: () => auth.token,
    refreshAccessToken: () => auth.refreshAccessToken(),
    onUnauthorized: () => {
      auth.clear();
      onUnauthorized?.();
    },
  });
}
