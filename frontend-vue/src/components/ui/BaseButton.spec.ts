import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';

import BaseButton from './BaseButton.vue';

type ButtonProps = { disabled?: boolean; submit?: boolean; variant?: 'danger' | 'link' | 'primary' };

function mountButton(props: ButtonProps = {}, attrs: Record<string, unknown> = {}) {
  return mount(BaseButton, { props: { ...props }, attrs, slots: { default: '刷新' } });
}

describe('BaseButton', () => {
  it('renders a plain button by default because a native button inside a form would submit', () => {
    const button = mountButton().get('button');

    expect(button.attributes('type')).toBe('button');
    expect(button.text()).toBe('刷新');
    expect(button.classes()).toEqual([]);
  });

  it('maps each variant onto the class the stylesheets already own', () => {
    expect(mountButton({ variant: 'link' }).get('button').classes()).toContain('link');
    expect(mountButton({ variant: 'danger' }).get('button').classes()).toContain('danger');
    expect(mountButton({ variant: 'primary' }).get('button').classes()).toEqual([]);
  });

  it('keeps `submit` the only way to get a submitting button', () => {
    expect(mountButton({ submit: true }).get('button').attributes('type')).toBe('submit');
  });

  it('disables the native button when the host says so', () => {
    expect(mountButton({ disabled: true }).get('button').attributes('disabled')).toBeDefined();
  });

  it('forwards host attributes and listeners onto the native button', async () => {
    const onClick = vi.fn();
    const button = mountButton({ disabled: false }, { 'data-testid': 'submit', onClick }).get('button');

    expect(button.attributes('data-testid')).toBe('submit');
    await button.trigger('click');
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
