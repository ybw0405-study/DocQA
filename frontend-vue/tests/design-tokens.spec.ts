import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

import { describe, expect, it } from 'vitest';

const ROOT = process.cwd();
const SRC = join(ROOT, 'src');
const STYLES = join(SRC, 'styles');
const TOKENS = 'src/styles/tokens.css';

/** 全局样式只有这四层，且必须按这个顺序被 main.ts 引入：后一层覆盖前一层，工具类收尾。 */
const LAYERS = ['tokens.css', 'base.css', 'components.css', 'utilities.css'];

/** 原始色阶族：组件层禁止直接消费，只能通过语义别名间接取用。 */
const RAW_FAMILIES = ['--gray-', '--blue-', '--teal-', '--red-'];

/**
 * AA 下限统一按正文 4.5:1 收，非文本的 3.0:1 放宽在本层没有需要放行的组合：
 * 卡片与胶囊的装饰性边框刻意停在 3:1 以下、不承担信息，因此不参与断言，也不许被拉到达标。
 */
const AA_TEXT = 4.5;

const HEX = /^#[0-9a-f]{6}$/i;
const VAR_REFERENCE = /var\(\s*(--[a-z0-9-]+)/g;
const VAR_WRAP = /^var\(\s*(--[a-z0-9-]+)\s*\)$/;
const DECLARATION = /(--[a-z0-9-]+)\s*:\s*([^;{}]+);/g;

/** 对比度断言的取值来源：语义前景必须落在它真正使用的那个底色上。 */
const CONTRAST_PAIRS: readonly { fg: string; bg: string; why: string }[] = [
  { fg: '--color-text', bg: '--color-bg', why: '正文落在页面底' },
  { fg: '--color-text', bg: '--color-surface', why: '正文落在卡片' },
  { fg: '--color-text-muted', bg: '--color-bg', why: '次要文字落在页面底' },
  { fg: '--color-text-muted', bg: '--color-surface', why: '次要文字落在卡片' },
  { fg: '--color-primary', bg: '--color-bg', why: '主色文字落在页面底' },
  { fg: '--color-primary', bg: '--color-surface', why: '主色文字落在卡片' },
  { fg: '--color-danger', bg: '--color-bg', why: '错误文字落在页面底' },
  { fg: '--color-danger', bg: '--color-surface', why: '错误文字落在卡片' },
  { fg: '--color-on-primary', bg: '--color-primary', why: '主按钮前景压主色填充' },
  { fg: '--color-on-danger', bg: '--color-danger', why: '危险按钮前景压危险填充' },
  { fg: '--color-ok-text', bg: '--color-ok-bg', why: '治理徽章' },
  { fg: '--color-warn-text', bg: '--color-warn-bg', why: '课表冲突徽章' },
];

function read(path: string): string {
  return readFileSync(path, 'utf8');
}

function toPosix(absolute: string): string {
  return relative(ROOT, absolute).split(sep).join('/');
}

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const absolute = join(dir, entry);
    if (statSync(absolute).isDirectory()) {
      return sourceFiles(absolute);
    }
    // 守卫自己的字面量（令牌名、阈值）不是被扫描对象。
    return /\.(css|vue|ts)$/.test(entry) && !entry.endsWith('.spec.ts') ? [absolute] : [];
  });
}

/** 注释里的选择器文本与色值讨论都不参与解析。 */
function stripComments(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\//g, '');
}

/** 按花括号配对取出某个选择器的规则体，只覆盖本仓库令牌层的扁平写法。 */
function ruleBody(css: string, selector: string): string {
  const start = css.indexOf(selector);
  if (start < 0) {
    throw new Error(`令牌层缺少选择器 ${selector}`);
  }
  const open = css.indexOf('{', start);
  let depth = 0;
  for (let index = open; index < css.length; index += 1) {
    if (css[index] === '{') {
      depth += 1;
    } else if (css[index] === '}') {
      depth -= 1;
      if (depth === 0) {
        return css.slice(open + 1, index);
      }
    }
  }
  throw new Error(`选择器 ${selector} 的规则体没有闭合`);
}

function declarations(body: string, label: string): Map<string, string> {
  const map = new Map<string, string>();
  for (const match of body.matchAll(DECLARATION)) {
    const name = match[1] ?? '';
    if (map.has(name)) {
      throw new Error(`${label} 里 ${name} 被定义了两次`);
    }
    map.set(name, (match[2] ?? '').trim());
  }
  return map;
}

const tokens = stripComments(read(join(ROOT, TOKENS)));
const rootDeclarations = declarations(ruleBody(tokens, ':root'), ':root');
const darkDeclarations = declarations(ruleBody(tokens, "html[data-theme='dark']"), "html[data-theme='dark']");
const autoDarkDeclarations = declarations(ruleBody(tokens, 'html:not([data-theme])'), 'html:not([data-theme])');

/** 沿 var() 链解析到最终的十六进制值；语义别名在暗色块覆盖，原始色阶只在 :root。 */
function hexValue(map: Map<string, string>, name: string, seen: readonly string[] = []): string {
  const value = map.get(name) ?? rootDeclarations.get(name);
  if (value === undefined) {
    throw new Error(`令牌 ${name} 没有定义（链路：${[...seen, name].join(' -> ')}）`);
  }
  const reference = VAR_WRAP.exec(value);
  if (!reference) {
    if (!HEX.test(value)) {
      throw new Error(`令牌 ${name} 的取值 ${value} 不是六位十六进制色值`);
    }
    return value;
  }
  const target = reference[1] ?? '';
  if (seen.includes(target)) {
    throw new Error(`令牌 ${name} 的取值出现循环引用：${[...seen, target].join(' -> ')}`);
  }
  return hexValue(map, target, [...seen, name]);
}

function channel(pair: string): number {
  const value = Number.parseInt(pair, 16) / 255;
  return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
}

/** WCAG 2.1 相对亮度：用纯函数自证，不为一条断言引入调色板依赖。 */
function relativeLuminance(hex: string): number {
  const body = hex.slice(1);
  return (
    0.2126 * channel(body.slice(0, 2)) +
    0.7152 * channel(body.slice(2, 4)) +
    0.0722 * channel(body.slice(4, 6))
  );
}

function contrastRatio(foreground: string, background: string): number {
  const first = relativeLuminance(foreground);
  const second = relativeLuminance(background);
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}

function referencesIn(file: string): string[] {
  return [...stripComments(read(file)).matchAll(VAR_REFERENCE)].map((match) => match[1] ?? '');
}

function bareColorsIn(text: string): string[] {
  return [
    ...[...text.matchAll(/#[0-9a-fA-F]{3,8}\b/g)].map((match) => match[0]),
    ...[...text.matchAll(/\b(?:rgb|rgba|hsl|hsla)\(/g)].map((match) => match[0]),
  ];
}

const productionSources = sourceFiles(SRC);
const styleLayers = productionSources.filter((file) => toPosix(file).startsWith('src/styles/'));
const semanticColors = [...rootDeclarations.keys()].filter((name) => name.startsWith('--color-'));

describe('设计令牌守卫', () => {
  it('keeps exactly the four style layers and imports them in cascade order', () => {
    expect(readdirSync(STYLES).sort()).toEqual([...LAYERS].sort());

    const main = read(join(SRC, 'main.ts'));
    const imported = [...main.matchAll(/import\s+['"]\.\/styles\/([a-z-]+)\.css['"]/g)].map((match) => match[1] ?? '');
    expect(imported).toEqual(LAYERS.map((layer) => layer.replace('.css', '')));

    // 第二处入口就能自行决定顺序，四层只能由 main.ts 一处引入。
    const consumers = productionSources
      .filter((file) => toPosix(file) !== 'src/main.ts')
      .filter((file) => /styles\/(?:tokens|base|components|utilities)\.css/.test(read(file)))
      .map(toPosix);
    expect(consumers).toEqual([]);
  });

  it('keeps every color literal in the token layer and in one notation', () => {
    const offenders = productionSources
      .filter((file) => toPosix(file) !== TOKENS)
      .map((file) => ({ path: toPosix(file), found: bareColorsIn(stripComments(read(file))) }))
      .filter((entry) => entry.found.length > 0)
      .map((entry) => `${entry.path} -> ${entry.found.join(', ')}`);
    // index.html 也在一处写死颜色就等于开了第二个落点。
    const page = bareColorsIn(stripComments(read(join(ROOT, 'index.html'))));

    expect(offenders).toEqual([]);
    expect(page).toEqual([]);
    expect([...tokens.matchAll(/\b(?:rgb|rgba|hsl|hsla)\(/g)].map((match) => match[0])).toEqual([]);

    const rawPalette = [...rootDeclarations].filter(([name]) => RAW_FAMILIES.some((prefix) => name.startsWith(prefix)));
    expect(rawPalette.length).toBeGreaterThan(20);
    expect(rawPalette.filter(([, value]) => !HEX.test(value)).map(([name]) => name)).toEqual([]);
  });

  it('keeps custom property definitions in the token layer alone', () => {
    const definers = styleLayers
      .filter((file) => toPosix(file) !== TOKENS)
      .filter((file) => /(--[a-z0-9-]+)\s*:/.test(stripComments(read(file))))
      .map(toPosix);
    expect(definers).toEqual([]);
  });

  it('reconciles var() references with declarations in both directions', () => {
    const references = new Set(styleLayers.flatMap((file) => referencesIn(file)));

    // 悬空引用（历史上的 --font-mono 就是这么悬空的）与无人消费的死令牌都要当场抓住。
    const dangling = [...references].filter((name) => !rootDeclarations.has(name)).sort();
    const dead = [...rootDeclarations.keys()].filter((name) => !references.has(name)).sort();

    expect(dangling).toEqual([]);
    expect(dead).toEqual([]);
  });

  it('keeps the raw palette behind the semantic aliases', () => {
    const leaks = productionSources
      .filter((file) => toPosix(file) !== TOKENS)
      .flatMap((file) =>
        referencesIn(file)
          .filter((name) => RAW_FAMILIES.some((prefix) => name.startsWith(prefix)))
          .map((name) => `${toPosix(file)} -> ${name}`),
      );
    expect(leaks).toEqual([]);

    const consumed = new Set(
      styleLayers
        .filter((file) => toPosix(file) !== TOKENS)
        .flatMap((file) => referencesIn(file)),
    );
    expect(semanticColors.length).toBeGreaterThanOrEqual(15);
    expect(semanticColors.filter((name) => !consumed.has(name))).toEqual([]);
  });

  it('keeps the two dark overrides identical and complete', () => {
    const sorted = (map: Map<string, string>) =>
      [...map.entries()].sort((a, b) => a[0].localeCompare(b[0]));
    const light = new Set(semanticColors);

    // 手动态（属性选择器）与 auto（媒体查询）是同一份暗色值的两个落点，改一处漏一处立刻红。
    expect(new Set(darkDeclarations.keys())).toEqual(light);
    expect(new Set(autoDarkDeclarations.keys())).toEqual(light);
    expect(sorted(darkDeclarations)).toEqual(sorted(autoDarkDeclarations));
  });

  it.each(
    CONTRAST_PAIRS.flatMap((pair) =>
      (['light', 'dark'] as const).map((theme) => ({ theme, ...pair, map: theme === 'light' ? rootDeclarations : darkDeclarations })),
    ),
  )('$theme 主题：$why 达到 AA 正文下限', ({ theme, map, fg, bg, why }) => {
    const ratio = contrastRatio(hexValue(map, fg), hexValue(map, bg));
    expect(ratio, `${theme} 主题 ${why}：${fg} / ${bg} 实测 ${ratio.toFixed(3)}:1，低于 ${AA_TEXT}:1`).toBeGreaterThanOrEqual(
      AA_TEXT,
    );
  });
});
