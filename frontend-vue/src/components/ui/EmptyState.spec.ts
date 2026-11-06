import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import { EMPTY_STATE_TEXT } from '../../utils/display';
import EmptyState from './EmptyState.vue';

const ROOT = process.cwd();
const SRC = join(ROOT, 'src');

/** 空态出现一次就要登记一次：这里的分母是模板里真实渲染的 `<EmptyState>` 元素。 */
function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const absolute = join(dir, entry);
    if (statSync(absolute).isDirectory()) {
      return sourceFiles(absolute);
    }
    return /\.(ts|vue)$/.test(entry) && !entry.endsWith('.spec.ts') ? [absolute] : [];
  });
}

function emptyStateBlocks(): { file: string; binding: string }[] {
  return sourceFiles(SRC).flatMap((file) => {
    const text = readFileSync(file, 'utf8');
    return [...text.matchAll(/<EmptyState\b([\s\S]*?)\/>/g)].map((match) => ({
      file: relative(ROOT, file).split(sep).join('/'),
      binding: /:text="([^"]*)"/.exec(match[1] ?? '')?.[1] ?? '',
    }));
  });
}

describe('EmptyState', () => {
  it('renders the copy as a muted paragraph and nothing else', () => {
    const wrapper = mount(EmptyState, { props: { text: '还没有上传任务。' } });

    expect(wrapper.get('p').classes()).toEqual(['muted']);
    expect(wrapper.get('p').text()).toBe('还没有上传任务。');
    expect(wrapper.text()).toBe('还没有上传任务。');
  });

  it('keeps an untrusted copy as text instead of markup', () => {
    const wrapper = mount(EmptyState, { props: { text: '<b>粗体</b>' } });

    expect(wrapper.get('p').text()).toBe('<b>粗体</b>');
    expect(wrapper.find('b').exists()).toBe(false);
  });
});

describe('空态文案单点（D-191）', () => {
  const blocks = emptyStateBlocks();

  it('finds every empty state in the sources', () => {
    expect(blocks.length).toBeGreaterThanOrEqual(8);
  });

  it('binds each one to the table instead of an inline copy', () => {
    for (const block of blocks) {
      expect(block.binding, `${block.file} 的 <EmptyState> 缺少 :text 绑定`).not.toBe('');
      expect(block.binding, `${block.file} 的文案没有引用 EMPTY_STATE_TEXT`).toContain('EMPTY_STATE_TEXT.');
      // 只允许「取键 + 三元」两种组合：出现引号就说明有人把文案写回了视图里。
      expect(block.binding, `${block.file} 的 :text 内联了字面量`).not.toMatch(/['`]/);
    }
  });

  it('binds exactly the declared set of keys, with no dead copy and no unregistered one', () => {
    const used = new Set(
      blocks.flatMap((block) => [...block.binding.matchAll(/EMPTY_STATE_TEXT\.([A-Za-z0-9_]+)/g)].map((m) => m[1])),
    );
    const declared = new Set(Object.keys(EMPTY_STATE_TEXT));

    for (const block of blocks) {
      for (const match of block.binding.matchAll(/EMPTY_STATE_TEXT\.([A-Za-z0-9_]+)/g)) {
        expect(declared.has(match[1] ?? ''), `引用了未登记的键 ${match[1]}`).toBe(true);
      }
    }
    // 双向对账：既不允许引用不存在的键，也不允许留下没人用的文案。
    expect([...used].sort()).toEqual([...declared].sort());
  });
});
