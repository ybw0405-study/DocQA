import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import GovernanceBadge from './GovernanceBadge.vue';
import { DEMO_TRACE_ID, askData } from '../../tests/fixtures';
import type { AskData } from '../types/conversations';

function mountBadge(governance: AskData | null, tokenCount: number | null = 128) {
  return mount(GovernanceBadge, {
    props: { governance, tokenCount, traceId: DEMO_TRACE_ID },
  });
}

describe('GovernanceBadge', () => {
  it('keeps every governance field of the contract answer visible', () => {
    const wrapper = mountBadge(askData());

    expect(wrapper.text()).toContain('正常回答');
    expect(wrapper.text()).toContain('置信度 0.87');
    expect(wrapper.text()).toContain('token 1260');
    expect(wrapper.text()).toContain('工具调用 1');
    expect(wrapper.text()).toContain('轮次 2');
    expect(wrapper.text()).toContain('重新检索');
    expect(wrapper.text()).toContain(`trace ${DEMO_TRACE_ID}`);
    expect(wrapper.find('.governance__chip--ok').exists()).toBe(true);
  });

  it('marks a budget or confidence degraded answer with the warning tone and the replay flag', () => {
    const wrapper = mountBadge(
      askData({ budgetStatus: 'LOW_CONFIDENCE', confidence: 0.31, cacheHit: true, idempotentReplay: true }),
    );

    expect(wrapper.text()).toContain('证据不足');
    expect(wrapper.text()).toContain('命中缓存');
    expect(wrapper.text()).toContain('幂等重放');
    expect(wrapper.find('.governance__chip--warn').exists()).toBe(true);
    expect(wrapper.find('.governance__chip--ok').exists()).toBe(false);
  });

  it('says plainly that a history row has no governance fields instead of faking them', () => {
    const wrapper = mountBadge(null);

    expect(wrapper.text()).toContain('历史消息');
    expect(wrapper.text()).toContain('消息 token 128');
    expect(wrapper.text()).toContain('治理字段只在提问当次返回');
    expect(wrapper.text()).not.toContain('置信度');
    expect(wrapper.text()).not.toContain('轮次');
    expect(wrapper.find('.governance__chip--plain').exists()).toBe(true);
  });

  it('does not invent a token count when the row has none', () => {
    const wrapper = mount(GovernanceBadge, {
      props: { governance: null, tokenCount: null, traceId: null },
    });

    expect(wrapper.text()).toContain('消息 token —');
    expect(wrapper.text()).not.toContain('trace');
  });

  it('explains what the low-confidence chip actually means', () => {
    const wrapper = mountBadge(askData({ budgetStatus: 'LOW_CONFIDENCE' }));

    expect(wrapper.text()).toContain('证据不足');
    expect(wrapper.text()).toContain('检索到了内容，但证据与问题的匹配不足。');
  });

  it('keeps the explanation off a normal answer and off a history row', () => {
    expect(mountBadge(askData()).text()).not.toContain('匹配不足');
    expect(mountBadge(null).text()).not.toContain('匹配不足');
  });
});
