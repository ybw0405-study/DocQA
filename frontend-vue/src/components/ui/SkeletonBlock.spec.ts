import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import SkeletonBlock from './SkeletonBlock.vue';

function mountSkeleton(props: { label?: string; rows?: number } = {}) {
  return mount(SkeletonBlock, { props: { label: '会话列表加载中', ...props } });
}

describe('SkeletonBlock', () => {
  it('declares the busy state and names what is loading', () => {
    const block = mountSkeleton().get('div.skeleton');

    expect(block.attributes('role')).toBe('status');
    expect(block.attributes('aria-busy')).toBe('true');
    expect(block.attributes('aria-label')).toBe('会话列表加载中');
  });

  it('draws three rows by default and honours an explicit count', () => {
    expect(mountSkeleton().findAll('.skeleton__row')).toHaveLength(3);
    expect(mountSkeleton({ rows: 4 }).findAll('.skeleton__row')).toHaveLength(4);
  });

  it('hides every row from the accessibility tree', () => {
    for (const row of mountSkeleton({ rows: 2 }).findAll('.skeleton__row')) {
      expect(row.attributes('aria-hidden')).toBe('true');
    }
  });

  it('carries no digits so a loading placeholder cannot be read as a count', () => {
    const wrapper = mountSkeleton({ rows: 5 });

    expect(wrapper.text()).toBe('');
    expect(wrapper.get('div.skeleton').attributes('aria-label')).not.toMatch(/\d/);
  });
});
