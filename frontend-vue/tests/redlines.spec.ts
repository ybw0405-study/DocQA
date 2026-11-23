import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

import { describe, expect, it } from 'vitest';

import { THEME_STORAGE_KEY } from '../src/theme';

const ROOT = process.cwd();
const SRC = join(ROOT, 'src');

/** 渲染注入类：任何源码（含测试）都不允许出现，前端一律用文本插值渲染 `title`/`content`/`snippet`。 */
const RENDER_INJECTION = ['v-html', 'innerHTML', 'outerHTML', 'insertAdjacentHTML', 'document.write', 'eval('];

/** 寻址与越界类：绝对服务地址只允许出现在 vite.config.ts 的 dev 代理里；Python 端口与 /agent/ 路径不得被前端引用。 */
const ADDRESS_LEAKS = ['http://', 'https://', ':8000', '/agent/'];

/** 日志类：预签名分片地址与用户内容一律不得进入浏览器控制台。 */
const NEVER_LOG = ['console.'];

/**
 * 浏览器存储三重白名单（D-185）：原判据扫的是 `localStorage` 这个**词**，而它要守的是「访问令牌不落浏览器存储」。
 * 判据严于命名会把主题偏好这类非敏感状态一并禁掉，因此这里把它拆成三层可机判的约束：
 * 谁能用（owner 文件）、用什么 key（固定集合 + 只允许三个读写 API）、文件内不得出现什么（凭据类字面量）。
 * 这不是放宽红线：任何一项越界都仍然红，且多出来的两层是原来扫字面量抓不到的。
 */
const BROWSER_STORAGE_OWNER = 'src/theme.ts';
const BROWSER_STORAGE_METHODS = ['getItem', 'removeItem', 'setItem'];
const CREDENTIAL_LITERALS = ['token', 'session', 'authorization', 'access'];

/** 单点约束：这些字面量只应存在于唯一的实现位置。 */
const SINGLE_SOURCE = [
  { token: 'Idempotency-Key', owners: ['src/api/http.ts'] },
  { token: "'Authorization'", owners: ['src/api/http.ts'] },
  { token: "'retry-after'", owners: ['src/api/http.ts'] },
  { token: 'crypto.randomUUID', owners: ['src/api/http.ts'] },
  { token: 'axios.create', owners: ['src/api/http.ts', 'src/api/documents.ts'] },
];

/**
 * 请求层不得自带身份：调用方是谁只由 JWT `sub` 决定，所以 `src/api` 里不该出现任何用户标识。
 * 唯一例外由契约点名——管理面 `PUT /admin/ai/quotas/{userId}` 的路径参数与审计查询的 `userId`
 * 过滤，携带的是**被治理用户**而不是调用方。因此这里把「不得出现」收紧成
 * 「只能出现在契约点名的三种行形态里」：蛇形身份键与身份请求头仍然全域禁止，
 * 别处出现 `userId` 也仍然红，同一文件里的多余提法（比如自造的身份字段）同样红。
 */
const IDENTITY_NEEDLES = ['userId', 'user_id', 'X-User-Id'];
const CONTRACT_TARGET_IDENTITY = [
  {
    file: 'src/api/admin.ts',
    shapes: [/userId\??: string/, /userId: query\.userId/, /\$\{encodeURIComponent\(userId\)\}/],
  },
  /**
   * S10-7 的用户管理面（`PUT /users/{…}/roles`、`PATCH /users/{…}/status`）携带的也是**被管理对象**：
   * 契约把标识放在路径上，Java 侧仍以 JWT `sub` 判调用者，因此放行形态与配额页同构，只是没有查询键那条。
   */
  {
    file: 'src/api/users.ts',
    shapes: [/userId\??: string/, /\$\{encodeURIComponent\(userId\)\}/],
  },
  /**
   * 契约 2.8.0 的长期记忆管理面：记忆按 (tenantId, userId) 命名空间隔离，查询与删除都必须
   * 携带**被治理用户**的标识（契约参数 MemoryUserId）；租户仍只由已验签 JWT 决定。
   */
  {
    file: 'src/api/memories.ts',
    shapes: [/userId\??: string/, /userId=\$\{encodeURIComponent\(userId\)\}/],
  },
];

/**
 * 取出 `import { a, type B } from '<spec>'` 里的说明符名（跨行声明一并覆盖，`type` 前缀剥掉）。
 */
function importSpecifiers(text: string, specPattern: RegExp): string[] {
  const names: string[] = [];
  for (const match of text.matchAll(/import\s*(?:type\s*)?\{([^}]+)\}\s*from\s*(['"])([^'"]+)\2/g)) {
    if (!specPattern.test(match[3] ?? '')) {
      continue;
    }
    for (const raw of (match[1] ?? '').split(',')) {
      const name = raw.trim().replace(/^type\s+/, '').trim();
      if (name !== '') {
        names.push(name);
      }
    }
  }
  return names;
}

/** `src/api/http.ts` 导出的传输动词；模块路径可能是 `./http` 或 `../api/http`。 */
const HTTP_SPEC = /(^|\/)http$/;
const HTTP_VERBS = ['get', 'post', 'postWithTrace', 'postFormWithTrace', 'postNoContent', 'put', 'patch', 'deleteNoContent'];

/** 某个文件从 http 层引用的动词，排序后返回。 */
function verbsUsedFrom(file: string): string[] {
  const verbs = importSpecifiers(readFileSync(join(ROOT, file), 'utf8'), HTTP_SPEC).filter((name) =>
    HTTP_VERBS.includes(name),
  );
  return [...verbs].sort();
}

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const absolute = join(dir, entry);
    if (statSync(absolute).isDirectory()) {
      return sourceFiles(absolute);
    }
    return /\.(ts|vue)$/.test(entry) ? [absolute] : [];
  });
}

function toPosix(absolute: string): string {
  return relative(ROOT, absolute).split(sep).join('/');
}

function hits(files: string[], needles: string[]): string[] {
  return files.flatMap((file) => {
    const text = readFileSync(file, 'utf8');
    return needles.filter((needle) => text.includes(needle)).map((needle) => `${toPosix(file)} -> ${needle}`);
  });
}

/**
 * D-218 的判据来源：dev 服务器只按 `BACKENDS` 的 context 前缀匹配请求，少一条不报错、
 * 而是走 SPA fallback 返回 `index.html`，页面于是拿到 HTML 却只表现为「请求失败」。
 * 所以这里要的不是「代理里有哪些前缀」，而是「前端调用过哪些前缀」——后者只能从调用点导出。
 */
const API_DIR = 'src/api/';

/** `API_BASE_URL` 本身是 `/api/v1`，它是两条通道共用的前缀常量而不是调用路径，按第一段排除。 */
const NON_PATH_SEGMENTS = ['api'];

/** 注释里提到的路径不是调用点：`errors.ts` 的文档块写过 `/ask`，`http.ts` 的注释写过 `/api/v1` 与 `/auth/refresh`。 */
function stripComments(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');
}

/** `src/api` 生产文件里每一处引号起始的 `/xxx` 字面量的第一段，去重后补成完整前缀并排序。 */
function usedApiPrefixes(files: string[]): string[] {
  const segments = new Set<string>();
  for (const file of files) {
    const body = stripComments(readFileSync(file, 'utf8'));
    for (const match of body.matchAll(/[`']\/([a-z][a-z0-9-]*)/g)) {
      const segment = match[1];
      if (segment !== undefined && !NON_PATH_SEGMENTS.includes(segment)) {
        segments.add(segment);
      }
    }
  }
  return [...segments].map((segment) => `/api/v1/${segment}`).sort();
}

const allFiles = sourceFiles(SRC);
const productionFiles = allFiles.filter((file) => !file.endsWith('.spec.ts'));

describe('红线扫描', () => {
  it('covers a non-trivial set of sources', () => {
    expect(allFiles.length).toBeGreaterThanOrEqual(10);
  });

  it('keeps markup injection out of every source file', () => {
    expect(hits(allFiles, RENDER_INJECTION)).toEqual([]);
  });

  it('keeps absolute service addresses and the Python surface out of production sources', () => {
    expect(hits(productionFiles, ADDRESS_LEAKS)).toEqual([]);
  });

  it('keeps production sources free of browser logging', () => {
    expect(hits(productionFiles, NEVER_LOG)).toEqual([]);
  });

  it('confines browser storage to the single theme owner file', () => {
    // 必须整体匹配 (local|session)Storage：写成 /local|sessionStorage/ 会把注释里的「local」也算成使用者。
    const users = productionFiles
      .filter((file) => /\b(?:local|session)Storage\b/.test(readFileSync(file, 'utf8')))
      .map(toPosix)
      .sort();
    expect(users).toEqual([BROWSER_STORAGE_OWNER]);
  });

  it('pins the owner file to one key and the three read/write APIs', () => {
    const owner = readFileSync(join(ROOT, BROWSER_STORAGE_OWNER), 'utf8');
    const methods = [...owner.matchAll(/localStorage\.(\w+)/g)].map((match) => match[1]);
    expect([...new Set(methods)].sort()).toEqual(BROWSER_STORAGE_METHODS);

    // 键名只能来自那一个常量：新增第二把 key 会同时改变下面两条断言的结果。
    const argumentsUsed = [...owner.matchAll(/localStorage\.\w+\(\s*([^),\n]+)/g)]
      .map((match) => (match[1] ?? '').trim())
      .sort();
    expect([...new Set(argumentsUsed)]).toEqual(['THEME_STORAGE_KEY']);
    const declaredKeys = [...owner.matchAll(/([A-Z0-9_]*KEY)\s*=\s*'([^']+)'/g)].map((match) => `${match[1]}=${match[2]}`);
    expect(declaredKeys).toEqual([`THEME_STORAGE_KEY=${THEME_STORAGE_KEY}`]);
    expect(THEME_STORAGE_KEY).toBe('docqa_theme');
  });

  it('keeps credential-flavoured literals out of the storage owner file', () => {
    // 主题偏好是唯一被放行的状态；一旦这个文件里出现凭据类字样，就说明有人开始往同一处塞别的东西。
    const owner = readFileSync(join(ROOT, BROWSER_STORAGE_OWNER), 'utf8').toLowerCase();
    expect(CREDENTIAL_LITERALS.filter((word) => owner.includes(word))).toEqual([]);
  });

  it('keeps index.html free of inline scripts and pins the theme bootstrap first', () => {
    // CSP 是 script-src 'self'：首帧主题只能靠独立模块，不能靠内联脚本（D-184）。
    const html = readFileSync(join(ROOT, 'index.html'), 'utf8');
    const inline = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)]
      .map((match) => (match[1] ?? '').trim())
      .filter((body) => body !== '');
    expect(inline).toEqual([]);

    const tags = [...html.matchAll(/<script\b([^>]*)>/g)].map((match) => match[1] ?? '');
    // CT-7：config.js 是唯一允许的非 module 脚本（运行时配置必须在应用代码之前注入），
    // 且用相对地址引用，才能随相对 base 挂在任意子路径。它不执行任何内联代码，
    // 只设置 window.__APP_CONFIG__，因此不违反「禁止内联脚本 / script-src 'self'」这两条红线。
    const runtimeTags = tags.filter((attrs) => !attrs.includes('type="module"'));
    expect(runtimeTags).toHaveLength(1);
    expect(runtimeTags[0]).toMatch(/src="\.\/config\.js"/);
    const moduleTags = tags.filter((attrs) => attrs.includes('type="module"'));
    expect(moduleTags.length).toBeGreaterThanOrEqual(2);
    for (const attrs of moduleTags) {
      expect(attrs).toMatch(/src="\/src\/[\w./-]+"/);
    }
    expect(html.indexOf('config.js')).toBeLessThan(html.indexOf('src/main.ts'));
    expect(/src="\/src\/theme-bootstrap\.ts"/.test(html)).toBe(true);
    expect(html.indexOf('theme-bootstrap.ts')).toBeLessThan(html.indexOf('src/main.ts'));

    const cspMeta = /<meta[^>]*Content-Security-Policy[^>]*>/i.exec(html)?.[0] ?? '';
    const csp = /content="([^"]*)"/.exec(cspMeta)?.[1] ?? '';
    expect(csp).toContain("script-src 'self'");
    expect(/script-src[^;]*unsafe-inline/.test(csp)).toBe(false);
    // CT-5：style-src 的 unsafe-inline 与 connect-src 的两个 dev HMR websocket 白名单已移除。
    expect(/style-src[^;]*unsafe-inline/.test(csp)).toBe(false);
    expect(/connect-src[^;]*ws:/.test(csp)).toBe(false);
  });

  it('delegates frame-ancestors, nosniff and HSTS to the hosting layer (CT-5)', () => {
    // meta 形式不支持 frame-ancestors / HSTS，必须由响应头下发；这里对账样例的实际内容，
    // 而不是只断言文件存在——否则「加了空文件」也能骗过这条红线。
    const sample = readFileSync(join(ROOT, 'nginx.conf.example'), 'utf8');
    const headers = readFileSync(join(ROOT, 'security-headers.conf.example'), 'utf8');
    const hosting = `${sample}\n${headers}`;
    expect(hosting).toContain("frame-ancestors 'none'");
    expect(hosting).toContain('X-Content-Type-Options');
    expect(hosting).toContain('nosniff');
    expect(hosting).toContain('Strict-Transport-Security');
  });

  it('keeps the storage bucket out of build-time constants (CT-7)', () => {
    const documents = readFileSync(join(ROOT, 'src/api/documents.ts'), 'utf8');
    expect(documents).not.toContain('import.meta.env.VITE_MINIO_BUCKET');
    const runtime = readFileSync(join(ROOT, 'src/config/runtime.ts'), 'utf8');
    expect(runtime).toContain('__APP_CONFIG__');
    expect(runtime).toContain('storagePrefix');
    expect(readFileSync(join(ROOT, 'public/config.js'), 'utf8')).toContain('storageBucket');
  });

  it('injects build.base and pins the no-store / immutable hosting policy (CT-8)', () => {
    const config = readFileSync(join(ROOT, 'vite.config.ts'), 'utf8');
    expect(config).toContain('VITE_APP_BASE');
    const sample = readFileSync(join(ROOT, 'nginx.conf.example'), 'utf8');
    expect(sample).toContain('no-store');
    expect(sample).toContain('immutable');
    expect(sample).toContain('config.js');
    expect(sample).toContain('/assets/');
  });

  it('keeps the dev proxy pointed only at the four Java services and the object storage', () => {
    const config = readFileSync(join(ROOT, 'vite.config.ts'), 'utf8');
    expect(config).not.toContain(':8000');
    expect(config).not.toContain('/agent/');
    expect(config).toContain('127.0.0.1:8081');
    expect(config).toContain('127.0.0.1:8082');
    expect(config).toContain('127.0.0.1:8083');
    expect(config).toContain('127.0.0.1:8080');
    expect(config).toContain('127.0.0.1:9000');
  });

  it('keeps one dev proxy prefix for exactly every API prefix the client calls (D-218)', () => {
    const contexts = [...readFileSync(join(ROOT, 'vite.config.ts'), 'utf8')
      .matchAll(/context: '([^']+)'/g)]
      .map((match) => match[1] ?? '')
      .sort();
    const used = usedApiPrefixes(productionFiles.filter((file) => toPosix(file).startsWith(API_DIR)));
    // 两条都必须是真集合：解析器一旦失效就会让两侧同时变空并「相等」，所以先自证非空。
    expect(used.length).toBeGreaterThan(0);
    expect(contexts.length).toBe(used.length);
    expect(contexts).toEqual(used);
  });

  it('keeps the dev proxy, runtime default and shipped config on the same bucket', () => {
    // CT-7：默认桶名不再烘焙进产物，改为「运行时配置 + dev 代理」两处声明。
    // 三方（vite dev 代理 / runtime.ts 默认 / public/config.js 默认值）必须逐字一致，
    // 否则本地开发的分片 PUT 会被白名单判为非法、而生产却正常——这类偏差只会在 dev 暴露。
    const config = readFileSync(join(ROOT, 'vite.config.ts'), 'utf8');
    const runtime = readFileSync(join(ROOT, 'src/config/runtime.ts'), 'utf8');
    const shipped = readFileSync(join(ROOT, 'public/config.js'), 'utf8');
    const proxyBucket = /MINIO_BUCKET = '([^']+)';/.exec(config)?.[1];
    const defaultBucket = /DEFAULT_BUCKET = '([^']+)';/.exec(runtime)?.[1];
    const shippedBucket = /storageBucket: '([^']+)'/.exec(shipped)?.[1];

    expect(proxyBucket).toBeDefined();
    expect(defaultBucket).toBeDefined();
    expect(shippedBucket).toBeDefined();
    expect(defaultBucket).toBe(proxyBucket);
    expect(shippedBucket).toBe(proxyBucket);
  });

  it.each(SINGLE_SOURCE)('keeps the literal %s in exactly one place', ({ token, owners }) => {
    const found = productionFiles
      .filter((file) => readFileSync(file, 'utf8').includes(token))
      .map(toPosix)
      .sort();
    expect(found).toEqual([...owners].sort());
  });

  it('never lets the request layer carry an identity of its own', () => {
    const apiFiles = productionFiles.filter((file) => toPosix(file).startsWith('src/api/'));
    expect(apiFiles.length).toBeGreaterThanOrEqual(2);
    const exempt = CONTRACT_TARGET_IDENTITY.map((entry) => entry.file);
    expect(hits(apiFiles.filter((file) => !exempt.includes(toPosix(file))), IDENTITY_NEEDLES)).toEqual([]);

    // 例外只放行契约点名的那几种行形态：被管理对象的 id，而不是调用方自己的。
    for (const entry of CONTRACT_TARGET_IDENTITY) {
      const file = join(ROOT, entry.file);
      expect(hits([file], ['user_id', 'X-User-Id'])).toEqual([]);
      const lines = readFileSync(file, 'utf8')
        .split(/\r?\n/)
        .filter((line) => line.includes('userId'));
      expect(lines.length, `${entry.file} 若已不携带身份参数，就该从例外清单里删掉`).toBeGreaterThan(0);
      expect(
        lines
          .filter((line) => !entry.shapes.some((shape) => shape.test(line)))
          .map((line) => line.trim()),
      ).toEqual([]);
    }
  });

  it('keeps the reminder and notification surfaces on the contract verbs', () => {
    // 契约 2.5.0 在这两个模块上只给三种动词：看板与通知列表 GET，创建/课表预览导入/标记已读 POST，
    // 状态机 PATCH（该操作面自 2.3.0 引入状态机以来未变）。没有改标题（PUT）也没有删除（DELETE），
    // 因此这两个出口一旦出现在下面两张表里就是越界。
    const apiVerbs: Record<string, string[]> = {
      'src/api/reminders.ts': ['get', 'patch', 'postFormWithTrace', 'postWithTrace'],
      'src/api/notifications.ts': ['get', 'post'],
    };
    for (const [file, expected] of Object.entries(apiVerbs)) {
      expect(verbsUsedFrom(file), `${file} 使用的传输动词与契约给出的操作面不符`).toEqual(
        [...expected].sort(),
      );
    }

    // 视图、组件与 store 一律经 api 层发请求：绕过它就会绕过「幂等键绑定 reminderId|status|version」这一处实现。
    const consumers = [
      'src/stores/reminders.ts',
      'src/stores/notifications.ts',
      'src/stores/scheduleImport.ts',
      'src/components/ScheduleImportPanel.vue',
      'src/components/NotificationPanel.vue',
      'src/views/RemindersView.vue',
    ];
    const offenders = consumers
      .filter((file) => verbsUsedFrom(file).length > 0)
      .map((file) => `${file} -> ${verbsUsedFrom(file).join(', ')}`);
    expect(offenders, `这些模块直接引用了传输动词，请求应当只经 src/api/ 发出：${offenders.join(' | ')}`).toEqual([]);
  });

  it('leaves recurrence expansion to the Java calculator', () => {
    // 前端只做输入格式预检，不重实现 RRULE 展开，也不因此多一个依赖。
    expect(readFileSync(join(ROOT, 'package.json'), 'utf8')).not.toContain('rrule');
    const store = readFileSync(join(ROOT, 'src/stores/reminders.ts'), 'utf8');
    expect(store).not.toContain('UNTIL');
  });
});
