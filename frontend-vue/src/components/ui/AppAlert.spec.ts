import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import { NETWORK_ERROR_CODE } from '../../api/errors';
import AppAlert from './AppAlert.vue';

type AlertProps = { code?: string | null; detail?: string | null; fieldsShown?: boolean; traceId?: string | null };

function mountAlert(props: AlertProps = {}, attrs: Record<string, unknown> = {}) {
  return mount(AppAlert, { props: { detail: null, ...props }, attrs });
}

/** `role="alert"` 的文本会被读屏整段播报，因此这里断言的是「使用者听到什么」，而不是 DOM 结构。 */
function said(wrapper: ReturnType<typeof mountAlert>): string {
  return wrapper.text().replace(/\s+/g, ' ');
}

describe('AppAlert', () => {
  it('renders nothing while the host has no failure to report', () => {
    const wrapper = mountAlert();

    expect(wrapper.find('p').exists()).toBe(false);
    expect(said(wrapper)).toBe('');
  });

  it('gives detail, code and trace together for a server-side failure', () => {
    const wrapper = mountAlert({ detail: '服务暂时不可用', code: 'AGT-001', traceId: 't-1' });

    expect(wrapper.get('p').classes()).toEqual(['error-text']);
    expect(wrapper.get('p').attributes('role')).toBe('alert');
    expect(said(wrapper)).toBe('服务暂时不可用 （code AGT-001）（trace t-1）');
    expect(wrapper.findAll('span').map((span) => span.text())).toEqual(['（code AGT-001）', '（trace t-1）']);
  });

  it('drops the code and trace markers the host does not have', () => {
    expect(said(mountAlert({ detail: '请求未被受理' }))).toBe('请求未被受理');
    expect(said(mountAlert({ detail: '缺少链路标识', code: 'DOC-002' }))).toBe('缺少链路标识 （code DOC-002）');
  });

  it('keeps the transport code out of the message because CLIENT-* is not something a user can act on', () => {
    const wrapper = mountAlert({ detail: '无法连接服务', code: NETWORK_ERROR_CODE, traceId: 't-2' });

    expect(said(wrapper)).toBe('无法连接服务');
    expect(said(wrapper)).not.toContain('CLIENT-');
    expect(wrapper.findAll('span')).toHaveLength(0);
  });

  it('leaves code and trace to the inputs once the host shows field errors', () => {
    const wrapper = mountAlert({
      detail: '提交的内容有问题',
      code: 'USER-VALIDATION-001',
      traceId: 't-3',
      fieldsShown: true,
    });

    expect(said(wrapper)).toBe('提交的内容有问题');
    expect(wrapper.findAll('span')).toHaveLength(0);
  });

  it('renders an untrusted detail as text, never as markup', () => {
    const wrapper = mountAlert({ detail: '<b>粗体</b>' });

    expect(said(wrapper)).toBe('<b>粗体</b>');
    expect(wrapper.find('b').exists()).toBe(false);
  });

  it('forwards the host test id onto the alert paragraph', () => {
    const wrapper = mountAlert({ detail: '失败' }, { 'data-testid': 'ics-error' });

    expect(wrapper.get('p').attributes('data-testid')).toBe('ics-error');
    expect(mountAlert({ detail: '失败' }).get('p').attributes('data-testid')).toBeUndefined();
  });
});
