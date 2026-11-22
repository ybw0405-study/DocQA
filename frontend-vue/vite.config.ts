/// <reference types="vitest/config" />
import { defineConfig, loadEnv, type Plugin } from 'vite';
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
// CT-7：dev 代理的桶前缀固定为本地默认桶；生产桶名来自运行时 config.js，客户端产物
// 对 VITE_MINIO_BUCKET 零引用（由 tests/redlines.spec.ts 机检），绝不烘焙进产物。
const MINIO_BUCKET = 'docqa-documents';

// dev 下 Vite 用内联 <style> 注入 CSS，而 index.html 的 meta CSP（CT-5 刻意去掉 unsafe-inline）
// 会把它们全部拦掉，页面只剩裸 HTML。生产产物是外链 CSS，不需要这条例外，故只在 serve 时改写。
function devCspForServe(): Plugin {
  return {
    name: 'docqa-dev-csp',
    apply: 'serve',
    transformIndexHtml(html: string): string {
      return html.replace("style-src 'self'", "style-src 'self' 'unsafe-inline'");
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  // CT-8：Vite 的 base 只能在构建期确定，因此采用「相对 base + 可覆盖 env」：
  // 默认 './' 让 dist 以相对路径引用 assets，可挂在任意子路径而无需重新构建；
  // 需要绝对前缀时由部署环境在 build 时注入 VITE_APP_BASE。运行时配置由 config.js 提供。
  const base = env.VITE_APP_BASE ?? './';
  return {
    base,
    plugins: [vue(), devCspForServe()],
    build: {
      rollupOptions: {
        output: {
          // 框架与运行时收敛到单一 vendor chunk：懒加载的每个路由只带自己的业务代码，
          // 不各自复制一份 vue/pinia；首屏 chunk 也因此明显变小。
          // Vite 8 / Rollup 的这一版类型只接受函数式 manualChunks，故不用对象写法。
          manualChunks(id: string): string | undefined {
            if (/node_modules[/\\](vue|vue-router|pinia|axios|@vue)[/\\]/.test(id)) {
              return 'vendor';
            }
            return undefined;
          },
        },
      },
    },
    server: {
      port: 5173,
      proxy: {
        ...Object.fromEntries(
          BACKENDS.map(({ context, envName, fallback }) => [
            context,
            { target: env[envName] ?? fallback, changeOrigin: true },
          ]),
        ),
        [`/${MINIO_BUCKET}`]: { target: env.VITE_MINIO_URL ?? 'http://127.0.0.1:9000', changeOrigin: true },
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
