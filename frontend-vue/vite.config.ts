/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';

interface Backend {
  readonly context: string;
  readonly envName: string;
  readonly fallback: string;
}

// 每个 Java 服务监听独立端口，dev 期由代理按路径前缀寻址，前端代码只使用同源相对路径。
// 分组依据是网关自己的路由谓词（`agent-gateway/application.yml` 的 Path 列表）：管理面三个控制器
// 本就长在网关里，`/users` 与 `/auth` 同服务，`/notifications` 与 `/reminders` 同服务。
// 少一条前缀不是报错而是静默降级——dev 服务器会走 SPA fallback 返回 index.html，页面拿到的是 HTML 而不是 JSON。
const BACKENDS: readonly Backend[] = [
  { context: '/api/v1/auth', envName: 'VITE_PROXY_AUTH_URL', fallback: 'http://127.0.0.1:8081' },
  { context: '/api/v1/users', envName: 'VITE_PROXY_AUTH_URL', fallback: 'http://127.0.0.1:8081' },
  { context: '/api/v1/documents', envName: 'VITE_PROXY_DOCUMENT_URL', fallback: 'http://127.0.0.1:8082' },
  { context: '/api/v1/reminders', envName: 'VITE_PROXY_REMINDER_URL', fallback: 'http://127.0.0.1:8083' },
  { context: '/api/v1/notifications', envName: 'VITE_PROXY_REMINDER_URL', fallback: 'http://127.0.0.1:8083' },
  { context: '/api/v1/conversations', envName: 'VITE_PROXY_GATEWAY_URL', fallback: 'http://127.0.0.1:8080' },
  { context: '/api/v1/admin', envName: 'VITE_PROXY_GATEWAY_URL', fallback: 'http://127.0.0.1:8080' },
];

// 预签名分片 PUT 同样只走同源相对路径：前端取 part.url 的 pathname+search，桶名在路径第一段。
// changeOrigin 必须为 true —— MinIO 签名的 SignedHeaders 只有 host，转发时的 Host 要和签名时一致。
// 不做 rewrite：MinIO 的 path-style 寻址里桶名本就是路径的一部分。
const MINIO_BUCKET = 'docqa-documents';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const bucket = env.VITE_MINIO_BUCKET ?? MINIO_BUCKET;
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        ...Object.fromEntries(
          BACKENDS.map(({ context, envName, fallback }) => [
            context,
            { target: env[envName] ?? fallback, changeOrigin: true },
          ]),
        ),
        [`/${bucket}`]: { target: env.VITE_MINIO_URL ?? 'http://127.0.0.1:9000', changeOrigin: true },
      },
    },
    test: {
      environment: 'jsdom',
      environmentOptions: { jsdom: { url: 'http://localhost/' } },
      include: ['src/**/*.spec.ts', 'tests/**/*.spec.ts'],
      setupFiles: ['tests/setup.ts'],
      clearMocks: true,
      restoreMocks: true,
      // 默认 forks 并发数等于 CPU 核数。在 Windows 上同时孵化十几个 jsdom 子进程会
      // 偶发 "[vitest-pool-runner]: Timeout waiting for worker to respond"，
      // 表现为所有文件全绿但进程仍以 1 退出。收敛到 4 个即可稳定复跑。
      // Vitest 4 起 poolOptions 已移除，并发度改为顶层 minWorkers / maxWorkers。
      pool: 'forks',
      minWorkers: 1,
      maxWorkers: 4,
    },
  };
});
