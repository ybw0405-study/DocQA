import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

import { describe, expect, it } from 'vitest';

const ROOT = process.cwd();
const SRC = join(ROOT, 'src');
const STYLES = join(SRC, 'styles');

/** 各宿主里逐字相同的限流倒数节点，用来定位它外层挂了什么角色（宿主清单见下面那条守卫）。 */
const COUNTDOWN = '限流等待 {{ secondsLeft }} 秒。';

/** 断点字面量：只许出现在 `@media` 条件里，见下面那条守卫的理由说明。 */
const BREAKPOINTS = [639, 1024, 1025];

/** `role="status"` 的合法宿主：一次性动作结果 + 加载占位，别处出现就说明有人把常驻文本做成了播报区。 */
const STATUS_OWNERS = [
  'src/components/NotificationPanel.vue',
  'src/components/ScheduleImportPanel.vue',
  'src/components/ui/SkeletonBlock.vue',
  'src/views/LoginView.vue',
  'src/views/RemindersView.vue',
  'src/views/admin/AdminControlsView.vue',
  'src/views/admin/AdminModelConnectionsView.vue',
  'src/views/admin/AdminQuotasView.vue',
  'src/views/admin/AdminTenantControlsView.vue',
  'src/views/admin/AdminUsersView.vue',
];

function walk(dir: string, pattern: RegExp): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const absolute = join(dir, entry);
    if (statSync(absolute).isDirectory()) {
      return walk(absolute, pattern);
    }
    return pattern.test(entry) ? [absolute] : [];
  });
}

function toPosix(absolute: string): string {
  return relative(ROOT, absolute).split(sep).join('/');
}

const productionTexts = new Map<string, string>(
  walk(SRC, /\.(ts|vue)$/)
    .filter((file) => !file.endsWith('.spec.ts'))
    .map((file) => [toPosix(file), readFileSync(file, 'utf8')]),
);
const cssTexts = new Map<string, string>(
  walk(STYLES, /\.css$/).map((file) => [toPosix(file), readFileSync(file, 'utf8')]),
);

/** 生产源码与样式层都按 posix 路径取原文，守卫的两类判据共用这一个入口。 */
function read(posixPath: string): string {
  const text = productionTexts.get(posixPath) ?? cssTexts.get(posixPath);
  if (text === undefined) {
    throw new Error(`找不到被守卫的文件 ${posixPath}`);
  }
  return text;
}

/** `@media` 的条件串，不含规则体。 */
function mediaConditions(text: string): string[] {
  return [...text.matchAll(/@media([^{]*)\{/g)].map((match) => (match[1] ?? '').trim());
}

/** 条件里的宽度阈值，按出现顺序返回。 */
function tierWidths(condition: string): number[] {
  return [...condition.matchAll(/(?:max-width|min-width):\s*(\d+)px/g)].map((match) => Number(match[1]));
}

function ownersOf(token: string): string[] {
  return [...productionTexts.entries()].filter(([, text]) => text.includes(token)).map(([file]) => file).sort();
}

/** 取包含 `needle` 的那个开始标签，用来检查「这段文字外层挂了什么属性」。 */
function openingTagOf(text: string, needle: string): string {
  const at = text.indexOf(needle);
  if (at === -1) {
    return '';
  }
  const end = text.indexOf('>', at);
  return text.slice(text.lastIndexOf('<', at), end === -1 ? undefined : end + 1);
}

describe('响应式三档断点（D-192）', () => {
  it('keeps the prerequisite in index.html', () => {
    // 没有 device-width 视口声明，浏览器按假想宽度排版，三档断点在手机上一次都不会求值。
    const html = readFileSync(join(ROOT, 'index.html'), 'utf8');

    expect(/<meta name="viewport" content="width=device-width/.test(html)).toBe(true);
    expect(/<html lang="zh-CN">/.test(html)).toBe(true);
  });

  it('keeps three contiguous tiers for the app chrome', () => {
    // 逐档阈值写死在此：639→640、1024→1025 必须首尾相接，否则中间会留下无人服务的宽度。
    const tiers = mediaConditions(read('src/styles/base.css'))
      .filter((condition) => condition.includes('width'))
      .map(tierWidths);

    expect(tiers).toEqual([[639], [640, 1024], [1025]]);
  });

  it('narrows the document detail grid in the two lower tiers', () => {
    const conditions = mediaConditions(read('src/styles/components.css'))
      .filter((condition) => condition.includes('width'))
      .map(tierWidths);

    expect(conditions).toEqual([[1024], [639]]);
    expect(read('src/styles/components.css')).toContain('.document-table__detail');
  });

  it('keeps breakpoint literals inside @media conditions', () => {
    // 断点数值不能进 tokens.css：媒体查询的条件读不到自定义属性，写成令牌就是没有消费点的死令牌（S10-2 实测）。
    const offenders: string[] = [];
    for (const [file, text] of cssTexts) {
      for (const line of text.split(/\r?\n/)) {
        const hit = BREAKPOINTS.some((breakpoint) => line.includes(`${breakpoint}px`));
        if (hit && !line.trim().startsWith('@media')) {
          offenders.push(`${file}: ${line.trim()}`);
        }
      }
    }

    expect(offenders, `断点字面量只能出现在 @media 条件里：${offenders.join(' | ')}`).toEqual([]);
    expect([...cssTexts.values()].join('')).not.toContain('--breakpoint');
  });

  it('scrolls the seven wide tables instead of restacking their rows', () => {
    // 窄屏把 td 竖排成卡片会丢掉 table/row/cell 角色，D-192 不能用 D-193 换。
    const text = read('src/styles/components.css');

    expect(text).toContain('.table-scroll');
    expect(ownersOf('class="table-scroll"').sort()).toEqual([
      'src/components/ScheduleImportPanel.vue',
      'src/views/DocumentsView.vue',
      'src/views/admin/AdminAuditView.vue',
      'src/views/admin/AdminModelConnectionsView.vue',
      'src/views/admin/AdminQuotasView.vue',
      'src/views/admin/AdminUsageView.vue',
      'src/views/admin/AdminUsersView.vue',
    ]);
  });
});

describe('可见焦点与键盘可达（D-193）', () => {
  it('defines the focus ring in exactly one place', () => {
    // 焦点环多处定义必然互相覆盖，最后总有一层会被某条 border/outline 规则吃掉。
    const ringFiles = [...cssTexts.entries()]
      .filter(([, text]) => /(^|\s)outline:/.test(text))
      .map(([file]) => file)
      .sort();

    expect(ringFiles).toEqual(['src/styles/base.css']);
    expect((read('src/styles/base.css').match(/outline:/g) ?? [])).toHaveLength(1);
    expect([...cssTexts.values()].join('')).not.toMatch(/:focus(?!-visible)/);
  });

  it('never puts a positive tabindex on an element', () => {
    // 只许 tabindex="-1"：让 skip link 的目标能被程序移交焦点，同时不进 Tab 序列。
    const offenders = [...productionTexts.entries()]
      .filter(([, text]) => /tabindex="\s*[1-9]/.test(text))
      .map(([file]) => file);

    expect(offenders, `正数 tabindex 会打乱键盘顺序：${offenders.join(' | ')}`).toEqual([]);
    expect(ownersOf('tabindex=')).toEqual(['src/App.vue']);
    expect(read('src/App.vue')).toContain('tabindex="-1"');
  });
});

describe('表格结构与标题（D-193）', () => {
  const tableFiles = [...productionTexts.entries()].filter(([, text]) => text.includes('<table'));

  it('finds exactly the seven data tables', () => {
    expect(tableFiles.map(([file]) => file).sort()).toEqual([
      'src/components/ScheduleImportPanel.vue',
      'src/views/DocumentsView.vue',
      'src/views/admin/AdminAuditView.vue',
      'src/views/admin/AdminModelConnectionsView.vue',
      'src/views/admin/AdminQuotasView.vue',
      'src/views/admin/AdminUsageView.vue',
      'src/views/admin/AdminUsersView.vue',
    ]);
  });

  it.each(tableFiles.map(([file]) => [file] as [string]))('%s has a caption and column scopes', (file) => {
    const text = read(file);
    const caption = /<caption>([\s\S]*?)<\/caption>/.exec(text)?.[1] ?? '';

    expect(caption.trim().length, `${file} 的 caption 不能为空`).toBeGreaterThan(0);
    const headers = (text.match(/<th\b/g) ?? []).length;
    const scoped = (text.match(/<th\s+scope="col"/g) ?? []).length;
    expect(headers, `${file} 至少要有表头`).toBeGreaterThan(0);
    expect(scoped, `${file} 有 ${headers} 个表头但只有 ${scoped} 个标了 scope="col"`).toBe(headers);
  });
});

describe('live region 范围（D-193）', () => {
  it('keeps role="status" on one-shot results and the loading placeholder', () => {
    expect(ownersOf('role="status"')).toEqual(STATUS_OWNERS);
    expect(openingTagOf(read('src/components/ScheduleImportPanel.vue'), 'data-testid="ics-result"')).toContain('role="status"');
    expect(openingTagOf(read('src/components/NotificationPanel.vue'), 'data-testid="notifications-note"')).toContain(
      'role="status"',
    );
    expect(openingTagOf(read('src/views/RemindersView.vue'), 'class="reminders__created"')).toContain('role="status"');
    expect(openingTagOf(read('src/views/LoginView.vue'), 'data-testid="login-notice"')).toContain('role="status"');
    expect(openingTagOf(read('src/views/admin/AdminControlsView.vue'), 'class="admin-controls__result"')).toContain(
      'role="status"',
    );
    expect(openingTagOf(read('src/views/admin/AdminQuotasView.vue'), 'class="admin-quotas__result"')).toContain(
      'role="status"',
    );
    expect(openingTagOf(read('src/views/admin/AdminTenantControlsView.vue'), 'class="admin-controls__result"')).toContain(
      'role="status"',
    );
    expect(openingTagOf(read('src/views/admin/AdminUsersView.vue'), 'class="admin-users__result"')).toContain(
      'role="status"',
    );
  });

  it('keeps the per-second rate-limit countdown out of any live region', () => {
    // 倒数每秒重绘一次，进了 live region 就会被反复播报；AppAlert 的 role="alert" 同理不能包住它。
    const hosts: string[] = [];
    for (const [file, text] of productionTexts) {
      const occurrences = text.split(COUNTDOWN).length - 1;
      if (occurrences === 0) {
        continue;
      }
      expect(occurrences, `${file} 的倒数节点应当唯一，否则守卫会只看第一处`).toBe(1);
      hosts.push(file);
      expect(openingTagOf(text, COUNTDOWN), `${file} 的限流倒数不该是 live region`).not.toContain('role=');
    }

    expect(hosts.sort()).toEqual([
      'src/components/ScheduleImportPanel.vue',
      'src/views/AskView.vue',
      'src/views/HomeView.vue',
      'src/views/RemindersView.vue',
    ]);
  });
});

describe('skip link（D-193）', () => {
  it('points at an anchor that exists exactly once', () => {
    const app = read('src/App.vue');
    const href = /class="skip-link"[^>]*href="#([^"]+)"/.exec(app)?.[1] ?? '';

    expect(href).toBe('main-content');
    expect(app).toContain(`id="${href}"`);
    expect(ownersOf(`id="${href}"`)).toEqual(['src/App.vue']);
  });

  it('comes before the header so it is the first stop on Tab', () => {
    const app = read('src/App.vue');

    expect(app.indexOf('class="skip-link"')).toBeLessThan(app.indexOf('<header'));
    expect(app.indexOf('class="skip-link"')).toBeLessThan(app.indexOf('class="app-header__nav"'));
  });

  it('has a style that reveals it on focus', () => {
    // 只用 visually-hidden 而不复位的话，键盘用户会得到一个「按了没反应」的链接。
    const css = read('src/styles/components.css');

    expect(/(^|\n)\.skip-link\s*\{/.test(css), '缺少 .skip-link 规则').toBe(true);
    expect(/\.skip-link:focus-visible\s*\{[^}]*top:/.test(css)).toBe(true);
  });
});
