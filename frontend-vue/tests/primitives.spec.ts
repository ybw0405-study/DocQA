import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

import { describe, expect, it } from 'vitest';

const ROOT = process.cwd();
const SRC = join(ROOT, 'src');
const UI_DIR = join(SRC, 'components', 'ui');

/**
 *  的单点判据：每个原语独占它负责的那个标签/属性，视图只允许引用组件名。
 * 扫描范围刻意包含测试文件之外的一切生产源码，出现第二处就说明有人绕开原语手写了同构标记。
 */
const SINGLE_OWNER = [
  { token: '<button', owners: ['src/components/ui/BaseButton.vue'] },
  { token: 'class="field"', owners: ['src/components/ui/BaseField.vue'] },
  { token: '<section class="card"', owners: ['src/components/ui/BaseCard.vue'] },
  { token: 'role="alert"', owners: ['src/components/ui/AppAlert.vue'] },
  { token: 'class="skeleton"', owners: ['src/components/ui/SkeletonBlock.vue'] },
];

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

const allFiles = sourceFiles(SRC);
const productionFiles = allFiles.filter((file) => !file.endsWith('.spec.ts'));
const texts = new Map(productionFiles.map((file) => [toPosix(file), readFileSync(file, 'utf8')]));

function ownersOf(token: string): string[] {
  return [...texts.entries()].filter(([, text]) => text.includes(token)).map(([file]) => file).sort();
}

describe('组件原语单点', () => {
  it('covers a non-trivial set of sources', () => {
    expect(productionFiles.length).toBeGreaterThanOrEqual(20);
    expect(readdirSync(UI_DIR).filter((entry) => entry.endsWith('.vue'))).toHaveLength(6);
  });

  it.each(SINGLE_OWNER)('keeps %s inside its own primitive', ({ token, owners }) => {
    expect(ownersOf(token)).toEqual([...owners].sort());
  });

  it('keeps every primitive consumed by a host', () => {
    // 没被任何视图/面板引用的原语只会变成第二个「看起来统一、实际没人用」的层。
    const idle = readdirSync(UI_DIR)
      .filter((entry) => entry.endsWith('.vue'))
      .map((entry) => entry.replace(/\.vue$/, ''))
      .filter((name) => [...texts.entries()].every(([file, text]) => file.endsWith(`/ui/${name}.vue`) || !text.includes(`${name}.vue`)))
      .sort();

    expect(idle).toEqual([]);
  });

  it('binds every skeleton to a loading flag and to first-load semantics', () => {
    // 骨架只能出现在「首次加载中」。宿主只要另有同名 `*loaded` 标志，就说明它已有过内容，
    // 此时骨架条件必须把那个标志取反，否则每次刷新都会闪一块空壳、把已有数据顶掉。
    const offenders: string[] = [];
    for (const [file, text] of texts) {
      if (file.endsWith('/ui/SkeletonBlock.vue')) {
        continue;
      }
      for (const match of text.matchAll(/<SkeletonBlock\b([\s\S]*?)\/>/g)) {
        const condition = /v-(?:else-)?if="([^"]*)"/.exec(match[1] ?? '')?.[1] ?? '';
        const bound = /(\w+)\.(\w*[Ll]oading)\b/.exec(condition);
        if (bound === null) {
          offenders.push(`${file} -> ${condition || '(无条件)'} 未绑定 loading 标志`);
          continue;
        }
        const loaded = `${bound[1]}.${(bound[2] ?? '').replace(/[Ll]oading$/, '')}loaded`;
        if (text.includes(loaded) && !condition.includes(`!${loaded}`)) {
          offenders.push(`${file} -> ${condition} 缺少 !${loaded}`);
        }
      }
    }

    expect(offenders, `这些骨架不满足首屏判据：${offenders.join(' | ')}`).toEqual([]);
  });
});
