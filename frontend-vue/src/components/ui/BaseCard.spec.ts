import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import { h } from 'vue';

import BaseCard from './BaseCard.vue';

type CardProps = { level?: 1 | 2; title?: string | null };

function mountCard(props: CardProps = {}, attrs: Record<string, unknown> = {}) {
  return mount(BaseCard, {
    props: { title: '文档', ...props },
    attrs,
    slots: { default: () => h('p', { class: 'muted' }, '卡片内容') },
  });
}

describe('BaseCard', () => {
  it('defaults the heading to h2 so a card does not steal the page title', () => {
    const wrapper = mountCard();

    expect(wrapper.get('h2').text()).toBe('文档');
    expect(wrapper.find('h1').exists()).toBe(false);
    expect(wrapper.get('p.muted').text()).toBe('卡片内容');
  });

  it('renders h1 when the host declares the card as the page-level container', () => {
    expect(mountCard({ level: 1 }).get('h1').text()).toBe('文档');
  });

  it('leaves a title-less container without a heading instead of inventing one', () => {
    const wrapper = mountCard({ title: null });

    expect(wrapper.find('section.card').exists()).toBe(true);
    expect(wrapper.find('h1, h2').exists()).toBe(false);
  });

  it('merges the host class onto the same section so historical selectors keep resolving', () => {
    const classes = mountCard({ title: null }, { class: 'document-list' }).get('section').classes();

    expect(classes).toContain('card');
    expect(classes).toContain('document-list');
  });

  it('renders an untrusted title as text, never as markup', () => {
    const wrapper = mountCard({ title: '<h3>注入</h3>' });

    expect(wrapper.get('h2').text()).toBe('<h3>注入</h3>');
    expect(wrapper.find('h3').exists()).toBe(false);
  });
});
