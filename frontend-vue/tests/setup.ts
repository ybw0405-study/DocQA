import { afterAll, afterEach, beforeAll, vi } from 'vitest';
import { clearCallLog } from './fixtures';
import { server } from './server';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => {
  server.resetHandlers();
  clearCallLog();
  localStorage.clear();
  // 流式通道把原生 fetch 换成了桩；不复位就会污染下一个用例里走 axios 的断言。
  vi.unstubAllGlobals();
});
afterAll(() => server.close());
