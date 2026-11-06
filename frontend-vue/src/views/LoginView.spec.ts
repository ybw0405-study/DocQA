import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter, type RouteRecordRaw } from 'vue-router';
import { HttpResponse, http as mswHttp } from 'msw';
import LoginView from './LoginView.vue';
import { changePassword } from '../api/auth';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import { DEMO_TOKEN, problem } from '../../tests/fixtures';
import { server } from '../../tests/server';
import { PASSWORD_CHANGED_NOTICE } from '../utils/display';

const sandbox = { render: () => null };

// 只关心 LoginView 传给 replace 的目标是否安全，因此用沙箱路由表避免真实 catch-all 的重定向链。
const testRoutes: RouteRecordRaw[] = [
  { path: '/', name: 'home', component: sandbox },
  { path: '/login', name: 'login', component: sandbox },
  { path: '/:pathMatch(.*)*', name: 'sandbox', component: sandbox },
];

async function mountLoginWith(search: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: testRoutes });
  await router.push(`/login${search}`);
  await router.isReady();
  const replace = vi.spyOn(router, 'replace');
  const wrapper = mount(LoginView, { global: { plugins: [router] } });
  return { wrapper, replace };
}

async function mountLogin(redirect?: string) {
  return mountLoginWith(redirect === undefined ? '' : `?redirect=${encodeURIComponent(redirect)}`);
}

async function switchToRegister(wrapper: ReturnType<typeof mount>) {
  await wrapper.find('button.link').trigger('click');
}

async function submit(wrapper: ReturnType<typeof mount>, password: string, username = 'demo') {
  await wrapper.find('input[name="username"]').setValue(username);
  await wrapper.find('input[name="password"]').setValue(password);
  await wrapper.find('form').trigger('submit');
  await flushPromises();
}

describe('LoginView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
  });

  it('keeps the password masked and mirrors the login contract bounds', async () => {
    const { wrapper } = await mountLogin();

    const username = wrapper.find('input[name="username"]');
    const password = wrapper.find('input[name="password"]');
    expect(username.attributes('maxlength')).toBe('100');
    expect(username.attributes('pattern')).toBeUndefined();
    expect(password.attributes('type')).toBe('password');
    expect(password.attributes('maxlength')).toBe('72');
    expect(password.attributes('autocomplete')).toBe('current-password');
  });

  it('switches to the register form and applies the register contract bounds', async () => {
    const { wrapper } = await mountLogin();
    await switchToRegister(wrapper);

    expect(wrapper.find('h1').text()).toBe('注册');
    const username = wrapper.find('input[name="username"]');
    const password = wrapper.find('input[name="password"]');
    expect(username.attributes('maxlength')).toBe('32');
    expect(username.attributes('pattern')).toBe('^[A-Za-z0-9_.-]{3,32}$');
    expect(password.attributes('maxlength')).toBe('72');
    expect(password.attributes('minlength')).toBe('10');
    expect(password.attributes('autocomplete')).toBe('new-password');
  });

  it('disables submission until both fields have a value', async () => {
    const { wrapper } = await mountLogin();
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined();

    await wrapper.find('input[name="username"]').setValue('demo');
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined();
  });

  it('rejects weak registration input before the request leaves the browser', async () => {
    const { wrapper } = await mountLogin();
    await switchToRegister(wrapper);
    await submit(wrapper, 'short', 'ab');

    const errors = wrapper.findAll('.field-error');
    expect(errors).toHaveLength(2);
    expect(errors[0]?.text()).toContain('3–32');
    expect(errors[1]?.text()).toContain('至少 10 位');
    expect(useAuthStore().token).toBeNull();
  });

  it('registers, stores the session and continues to the requested in-site route', async () => {
    const { wrapper, replace } = await mountLogin('/documents');
    await switchToRegister(wrapper);
    await submit(wrapper, 'goodpass123', 'newuser');

    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith('/documents'));
    expect(useAuthStore().token).toBe(DEMO_TOKEN);
    expect((wrapper.find('input[name="password"]').element as HTMLInputElement).value).toBe('');
  });

  it('signs in, stores the session and continues to the requested in-site route', async () => {
    const { wrapper, replace } = await mountLogin('/ask');
    await submit(wrapper, 'right-password');

    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith('/ask'));
    expect(useAuthStore().token).toBe(DEMO_TOKEN);
    expect((wrapper.find('input[name="password"]').element as HTMLInputElement).value).toBe('');
  });

  it('refuses to continue to an out-of-site redirect target', async () => {
    const { wrapper, replace } = await mountLogin('//evil.example');
    await submit(wrapper, 'right-password');

    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith('/'));
  });

  it('renders the server detail as escaped text, never as markup', async () => {
    server.use(
      mswHttp.post('/api/v1/auth/login', () =>
        HttpResponse.json(
          problem({ status: 401, code: 'USR-AUTH-001', detail: '<img src=x onerror=alert(1)>', instance: '/api/v1/auth/login' }),
          { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const { wrapper } = await mountLogin();
    await submit(wrapper, 'whatever');
    await vi.waitFor(() => expect(wrapper.find('[role="alert"]').exists()).toBe(true));

    const alert = wrapper.find('[role="alert"]');
    expect(alert.text()).toContain('<img src=x onerror=alert(1)>');
    expect(alert.find('img').exists()).toBe(false);
    expect(alert.text()).toContain('trace 4bf92f3577b34da6a3ce929d0e0e4736');
    expect(useAuthStore().token).toBeNull();
  });

  it('maps ProblemDetail field errors onto the input they belong to', async () => {
    server.use(
      mswHttp.post('/api/v1/auth/login', () =>
        HttpResponse.json(
          problem({
            detail: '请求参数校验失败。',
            errors: [{ field: 'password', message: '不能超过 72 个 UTF-8 字节' }],
          }),
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const { wrapper } = await mountLogin();
    await submit(wrapper, 'whatever');
    await vi.waitFor(() => expect(wrapper.find('.field-error').exists()).toBe(true));

    const labels = wrapper.findAll('label.field');
    expect(labels).toHaveLength(2);
    expect(labels[0]?.find('.field-error').exists()).toBe(false);
    expect(labels[1]?.find('.field-error').text()).toBe('不能超过 72 个 UTF-8 字节');
    expect(wrapper.find('[role="alert"]').text()).toContain('请求参数校验失败。');
  });

  it('surfaces a taken username from the register endpoint', async () => {
    const { wrapper } = await mountLogin();
    await switchToRegister(wrapper);
    await submit(wrapper, 'goodpass123', 'taken');
    await vi.waitFor(() => expect(wrapper.find('[role="alert"]').exists()).toBe(true));

    expect(wrapper.find('[role="alert"]').text()).toContain('用户名已被占用。');
    expect(useAuthStore().token).toBeNull();
  });

  it('hands the account page’s fixed notice key over as a status line and still lets the user sign in', async () => {
    // D-206 的完整链路：改密 204 之后服务端已吊销该用户全部刷新家族，本地会话清空，
    // 落到登录页时 URL 里只有键名，句子本体由 display.ts 单点持有。
    await useAuthStore().signIn('demo', 'right-password');
    await changePassword({ currentPassword: 'right-password', newPassword: 'brandnewpass1' });
    await useAuthStore().signOut();

    const { wrapper, replace } = await mountLoginWith('?notice=password-changed');
    const notice = wrapper.get('[data-testid="login-notice"]');

    expect(notice.attributes('role')).toBe('status');
    expect(notice.text()).toBe(PASSWORD_CHANGED_NOTICE);

    await submit(wrapper, 'brandnewpass1');
    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith('/'));
    await vi.waitFor(() => expect(useAuthStore().token).toBe(DEMO_TOKEN));
    expect(wrapper.find('[data-testid="login-notice"]').exists()).toBe(false);
  });

  it('renders only the fixed notice key and never echoes whatever the query string carries', async () => {
    const { wrapper } = await mountLoginWith('?notice=%E4%BD%A0%E5%A5%BD&redirect=%2Fask');

    expect(wrapper.find('[data-testid="login-notice"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('你好');
    expect(wrapper.text()).not.toContain(PASSWORD_CHANGED_NOTICE);
  });
});
