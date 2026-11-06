import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import { h } from 'vue';

import BaseField from './BaseField.vue';

type FieldProps = { error?: string | null; label?: string };

function mountField(props: FieldProps = {}, withHint = true) {
  return mount(BaseField, {
    props: { label: '用户名', ...props },
    slots: {
      default: () => h('input', { name: 'username' }),
      ...(withHint ? { hint: () => h('p', { class: 'caption' }, '至少 8 位') } : {}),
    },
  });
}

/** 直接读元素子节点：插槽给的是字符串时会变成文本节点，顺序判据就落空了。 */
function childTags(wrapper: ReturnType<typeof mountField>): string[] {
  return [...wrapper.get('label').element.children].map((node) => node.tagName.toLowerCase());
}

describe('BaseField', () => {
  it('wraps the control in a label so the caption stays clickable', () => {
    const wrapper = mountField();

    expect(wrapper.get('label').classes()).toContain('field');
    expect(wrapper.get('span').text()).toBe('用户名');
    expect(wrapper.get('input').attributes('name')).toBe('username');
  });

  it('keeps the caption, the control, the hint and the error in that reading order', () => {
    expect(childTags(mountField())).toEqual(['span', 'input', 'p']);
    expect(childTags(mountField({ error: '太短了' }))).toEqual(['span', 'input', 'p', 'span']);
  });

  it('leaves the hint slot out when the host has nothing to explain', () => {
    expect(childTags(mountField({}, false))).toEqual(['span', 'input']);
  });

  it('shows the field error only when the host has one', () => {
    expect(mountField().find('.field-error').exists()).toBe(false);
    expect(mountField({ error: '' }).find('.field-error').exists()).toBe(false);
    expect(mountField({ error: '密码至少 8 位' }).get('.field-error').text()).toBe('密码至少 8 位');
  });

  it('renders an untrusted server message as text, never as markup', () => {
    const wrapper = mountField({ error: '<img src=x onerror=alert(1)>' });

    expect(wrapper.get('.field-error').text()).toBe('<img src=x onerror=alert(1)>');
    expect(wrapper.find('img').exists()).toBe(false);
  });
});
