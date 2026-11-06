import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import CitationList from './CitationList.vue';
import type { Citation } from '../types/conversations';

const KNOWN_ID = '00000000-0000-0000-0000-0000000000a1';
const UNKNOWN_ID = '00000000-0000-0000-0000-0000000000ff';

function mountList(citations: Citation[], knownFileNames: Record<string, string | undefined> = {}) {
  return mount(CitationList, { props: { citations, knownFileNames } });
}

describe('CitationList', () => {
  it('prefers the file name that came with the citation', () => {
    const wrapper = mountList(
      [{ documentId: KNOWN_ID, chunkIndex: 12, score: 0.87, snippet: '片段', fileName: '服务端名字.pdf' }],
      { [KNOWN_ID]: '列表名字.pdf' },
    );

    expect(wrapper.text()).toContain('服务端名字.pdf');
    expect(wrapper.text()).not.toContain('列表名字.pdf');
  });

  it('falls back to the loaded document list when the citation carries no name', () => {
    const wrapper = mountList(
      [{ documentId: KNOWN_ID, chunkIndex: 3, score: 0.5, snippet: '片段' }],
      { [KNOWN_ID]: '列表名字.pdf' },
    );

    expect(wrapper.text()).toContain('列表名字.pdf');
    expect(wrapper.text()).toContain('分块 3');
    expect(wrapper.text()).toContain('相关度 50.0%');
  });

  it('shows only the id prefix when neither the citation nor the list knows the document', () => {
    const wrapper = mountList([{ documentId: UNKNOWN_ID, chunkIndex: 0, score: 0, snippet: '片段' }], {});

    expect(wrapper.text()).toContain('文档 00000000');
    expect(wrapper.text()).not.toContain(UNKNOWN_ID);
  });

  it('renders an untrusted snippet and file name as escaped text, never as markup', () => {
    const wrapper = mountList([
      {
        documentId: KNOWN_ID,
        chunkIndex: 1,
        score: 1,
        snippet: '<img src=x onerror=alert(1)>',
        fileName: '<b>name</b>',
      },
    ]);

    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>');
    expect(wrapper.find('img').exists()).toBe(false);
    expect(wrapper.find('b').exists()).toBe(false);
  });

  it('says so when the answer came back without any citation', () => {
    const wrapper = mountList([]);

    expect(wrapper.find('ol').exists()).toBe(false);
    expect(wrapper.text()).toBe('本次回答没有可用引用。');
  });

  it('names a zero score as lexical recall instead of a zero-percent relevance', () => {
    const wrapper = mountList([
      { documentId: KNOWN_ID, chunkIndex: 4, score: 0, snippet: '片段' },
      { documentId: KNOWN_ID, chunkIndex: 5, score: 0.62, snippet: '片段' },
    ]);

    expect(wrapper.text()).toContain('词法召回（向量未命中）');
    expect(wrapper.text()).toContain('相关度 62.0%');
    expect(wrapper.text()).not.toContain('相关度 0.0%');
  });

  it('states the relevance scale once for the whole block', () => {
    const wrapper = mountList([
      { documentId: KNOWN_ID, chunkIndex: 1, score: 0.9, snippet: '甲' },
      { documentId: UNKNOWN_ID, chunkIndex: 2, score: 0.4, snippet: '乙' },
    ]);
    const notes = wrapper.findAll('.citations > p').filter((node) => node.text().includes('相对得分'));

    expect(notes).toHaveLength(1);
  });

  it('drops the scale note when nothing carries a percentage', () => {
    const wrapper = mountList([{ documentId: KNOWN_ID, chunkIndex: 0, score: 0, snippet: '片段' }]);

    expect(wrapper.findAll('.citations > p')).toHaveLength(0);
  });
});
