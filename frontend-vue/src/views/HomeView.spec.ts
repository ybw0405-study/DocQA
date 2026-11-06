import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter, type RouteRecordRaw } from 'vue-router';
import { HttpResponse, http as mswHttp } from 'msw';
import HomeView from './HomeView.vue';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import { PASSWORD_PATTERN } from '../types/api';
import {
  DEMO_TOKEN,
  DEMO_USER,
  DEMO_USER_ID,
  callLog,
  problem,
  refreshHandler,
} from '../../tests/fixtures';
import { server } from '../../tests/server';
import {
  IDENTITY_ROLES_NOTE,
  IDENTITY_STATUS_NOTE,
  IDENTITY_TOKEN_TTL_NOTE,
} from '../utils/display';

const sandbox = { render: () => null };

// 只关心 HomeView 改密成功后交给 router.replace 的目标，因此用沙箱路由表避开真实 catch-all 的重定向链。
const testRoutes: RouteRecordRaw[] = [
  { path: '/', name: 'home', component: sandbox },
  { path: '/login', name: 'login', component: sandbox },
  { path: '/:pathMatch(.*)*', name: 'sandbox', component: sandbox },
];

async function mountView(options: { identity?: boolean } = {}) {
  const auth = useAuthStore();
  auth.token = DEMO_TOKEN;
  if (options.identity !== false) {
    await auth.loadCurrentUser();
  }
  const router = createRouter({ history: createMemoryHistory(), routes: testRoutes });
  await router.push('/');
  await router.isReady();
  const replace = vi.spyOn(router, 'replace');
  const wrapper = mount(HomeView, { global: { plugins: [router] } });
  return { wrapper, replace, auth };
}

type ViewWrapper = Awaited<ReturnType<typeof mountView>>['wrapper'];

async function submitPasswords(view: ViewWrapper, current: string, next: string): Promise<void> {
  await view.find('[data-testid="current-password"]').setValue(current);
  await view.find('[data-testid="new-password"]').setValue(next);
  await view.find('form').trigger('submit');
  await flushPromises();
}

function passwordFields(view: ViewWrapper) {
  return {
    current: view.find('[data-testid="current-password"]').element as HTMLInputElement,
    next: view.find('[data-testid="new-password"]').element as HTMLInputElement,
  };
}

function submitButton(view: ViewWrapper) {
  return view.find('button[type="submit"]');
}

/** `BaseField` 只在该字段有错时才渲染 `.field-error`，所以按标签取而不是按序号取。 */
function fieldError(view: ViewWrapper, label: string): string | null {
  const field = view.findAll('label.field').find((node) => node.text().includes(label));
  return field?.find('.field-error').exists() === true ? (field?.find('.field-error').text() ?? null) : null;
}

/** 视图的事件处理器不会被 await：给在途请求一个宏任务窗口，「零请求」这条断言才有意义。 */
async function flush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

function identityReadFails(): void {
  server.use(
    mswHttp.get('/api/v1/auth/me', () =>
      HttpResponse.json(
        problem({
          status: 503,
          code: 'COM-INTERNAL-001',
          detail: '身份读取暂时不可用。',
          instance: '/api/v1/auth/me',
        }),
        { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  );
}

/** 把等待窗改短：倒数按秒观察，用服务端的 42 秒就永远看不到它归零。 */
function rateLimitedFor(seconds: number): void {
  server.use(
    mswHttp.post('/api/v1/auth/password', () =>
      HttpResponse.json(
        problem({
          status: 429,
          code: 'USR-RATE_LIMIT-001',
          detail: '登录失败次数过多，请稍后再试。',
          instance: '/api/v1/auth/password',
        }),
        {
          status: 429,
          headers: { 'Content-Type': 'application/problem+json', 'Retry-After': String(seconds) },
        },
      ),
    ),
  );
}

describe('HomeView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
  });

  it('shows the five facts the server returned and nothing it inferred itself', async () => {
    const { wrapper: view } = await mountView();

    expect(view.findAll('.admin-state dt').map((node) => node.text())).toEqual([
      '用户标识',
      '用户名',
      '账号状态',
      '角色',
      '本令牌剩余有效期',
    ]);
    expect(view.findAll('.admin-state dd').map((node) => node.text())).toEqual([
      DEMO_USER_ID,
      DEMO_USER.username,
      '正常',
      '普通用户、管理员',
      `剩余 ${String(DEMO_USER.accessTokenExpiresIn)} 秒`,
    ]);
    // 三条口径注脚各自单点取自 display.ts：契约对这三个字段的陈旧性承诺并不相同（D-212）。
    expect(view.text()).toContain(IDENTITY_STATUS_NOTE);
    expect(view.text()).toContain(IDENTITY_ROLES_NOTE);
    expect(view.text()).toContain(IDENTITY_TOKEN_TTL_NOTE);
  });

  it('names which facts were never read instead of leaving a blank cell', async () => {
    const { wrapper: view } = await mountView({ identity: false });

    expect(view.findAll('.admin-state dd').map((node) => node.text())).toEqual([
      '未取到，请退出后重新登录。',
      '未取到，请退出后重新登录。',
      '未读取到',
      '未回显角色',
      '未读取到',
    ]);
  });

  it('re-reads the identity on demand and shows the server detail without a stack', async () => {
    const { wrapper: view } = await mountView();
    identityReadFails();

    await view.findAll('button').find((button) => button.text() === '重新读取')?.trigger('click');
    await vi.waitFor(() => expect(view.find('[role="alert"]').exists()).toBe(true));

    expect(view.find('[role="alert"]').text()).toContain('身份读取暂时不可用。');
    expect(view.find('[role="alert"]').text()).toContain('（code COM-INTERNAL-001）');
    expect(view.find('[role="alert"]').text()).not.toContain('at com.docqa');
  });

  it('mirrors the two password fields’ different contract bounds onto the inputs', async () => {
    const { wrapper: view } = await mountView();
    const current = view.find('[data-testid="current-password"]');
    const next = view.find('[data-testid="new-password"]');

    // 契约上 currentPassword 只有 minLength 1 / maxLength 72 且没有 pattern：本地补一条强度规则就会把「旧口令打错了」
    // 误报成「格式不符」（D-216），所以这里刻意不出现 minlength 与 pattern。
    expect(current.attributes('type')).toBe('password');
    expect(current.attributes('maxlength')).toBe('72');
    expect(current.attributes('minlength')).toBeUndefined();
    expect(current.attributes('pattern')).toBeUndefined();
    expect(current.attributes('autocomplete')).toBe('current-password');

    expect(next.attributes('type')).toBe('password');
    expect(next.attributes('maxlength')).toBe('72');
    expect(next.attributes('minlength')).toBe('10');
    expect(next.attributes('pattern')).toBe(PASSWORD_PATTERN.source);
    expect(next.attributes('autocomplete')).toBe('new-password');
  });

  it('blocks a weak new password and a repeat of the current one without spending a request', async () => {
    const { wrapper: view } = await mountView();

    await submitPasswords(view, 'rightpass123', 'short');
    expect(fieldError(view, '新口令')).toBe('新口令至少 10 位，且同时包含字母与数字。');
    expect(fieldError(view, '当前口令')).toBeNull();

    await submitPasswords(view, 'samevalue123', 'samevalue123');
    expect(fieldError(view, '新口令')).toBe('新口令不能与当前口令相同。');

    await flush();
    expect(callLog.passwordKeys).toEqual([]);
    expect(view.find('[role="alert"]').exists()).toBe(false);
  });

  it('surfaces the server’s policy rejection on the field it belongs to', async () => {
    const { wrapper: view } = await mountView();
    await submitPasswords(view, 'policy-rejects', 'brandnewpass1');
    await vi.waitFor(() => expect(view.find('[role="alert"]').exists()).toBe(true));

    // 服务端给出 newPassword 的字段违规时，明细贴到输入项旁，告警条就不再重复报 code（D-190）。
    expect(fieldError(view, '新口令')).toBe('must contain at least one letter and one digit');
    expect(view.find('[role="alert"]').text()).toContain('口令强度或授权变更不满足策略要求。');
    expect(view.find('[role="alert"]').text()).not.toContain('code USR-VALIDATION-001');
    expect(callLog.passwordKeys).toHaveLength(1);
  });

  it('counts down the server’s own backoff after the shared login gate fills up', async () => {
    const { wrapper: view } = await mountView();
    await submitPasswords(view, 'rate-limited', 'brandnewpass1');
    await vi.waitFor(() => expect(view.text()).toContain('限流等待'));

    // 窗口一开显示的就是服务端给的 42 秒、一秒不多：`now` 必须在开窗那一刻重取，
    // 否则用的是 composable 建立时的旧读数去减，42 会显成 43（S10-7e 注入实测）。
    expect(view.text()).toContain('限流等待 42 秒。');
    // 秒数来自服务端 `Retry-After: 42`，前端不自造等待时间；这条 429 也计入登录失败限流。
    expect(view.find('[role="alert"]').text()).toContain('code USR-RATE_LIMIT-001');
    expect(submitButton(view).attributes('disabled')).toBeDefined();
    expect(passwordFields(view).next.value).toBe('brandnewpass1');
  });

  it('ticks the wait down and releases the submit gate when the window elapses', async () => {
    rateLimitedFor(2);
    const { wrapper: view } = await mountView();
    await submitPasswords(view, 'rate-limited', 'brandnewpass1');
    await vi.waitFor(() => expect(view.text()).toContain('限流等待 2 秒。'));
    expect(submitButton(view).attributes('disabled')).toBeDefined();

    // 倒数不是摆设：定时器每秒重取时钟，归零后这条 429 的门控必须自己松开，否则页面只能靠重挂载解锁。
    await new Promise((resolve) => setTimeout(resolve, 1100));
    await flush();
    expect(view.text()).toContain('限流等待 1 秒。');

    await new Promise((resolve) => setTimeout(resolve, 1100));
    await flush();
    expect(view.text()).not.toContain('限流等待');
    expect(submitButton(view).attributes('disabled')).toBeUndefined();
  }, 8000);

  it('treats a wrong current password as a credential problem, not a lost session', async () => {
    server.use(refreshHandler(DEMO_TOKEN));
    const { wrapper: view, auth } = await mountView();

    await submitPasswords(view, 'wrong-password', 'brandnewpass1');
    await vi.waitFor(() => expect(view.find('[role="alert"]').exists()).toBe(true));

    // 契约上这个 401 说的是「请求体里的当前口令不对」，与服务端一起计入登录失败限流：
    // 拿它去续期并重放会把一次笔误记成两次失败（60 秒窗口内 5 次就锁），重放本身也是第二次失败。
    expect(callLog.passwordKeys).toHaveLength(1);
    expect(view.find('[role="alert"]').text()).toContain('用户名或密码错误。');
    expect(view.find('[role="alert"]').text()).toContain('（code USR-AUTH-001）');
    expect(auth.token).toBe(DEMO_TOKEN);
    expect(auth.userId).toBe(DEMO_USER_ID);
  });

  it('keeps the session when the password rejection arrives with an unrenewable session', async () => {
    const { wrapper: view, auth } = await mountView();

    await submitPasswords(view, 'wrong-password', 'brandnewpass1');
    await vi.waitFor(() => expect(view.find('[role="alert"]').exists()).toBe(true));

    // 续期失败也不该把「旧口令打错了」升级成「你已被登出」：这条 401 与会话有效性无关。
    expect(callLog.passwordKeys).toHaveLength(1);
    expect(auth.token).toBe(DEMO_TOKEN);
  });

  it('signs the session out and lands on the login page with the fixed notice key', async () => {
    const { wrapper: view, replace, auth } = await mountView();

    await submitPasswords(view, 'rightpass123', 'brandnewpass1');
    await vi.waitFor(() => expect(replace).toHaveBeenCalled());

    // 204 不带任何令牌，服务端已吊销该用户全部刷新家族并推进吊销水位：本地会话必须随即清空（D-206）。
    expect(replace).toHaveBeenCalledWith({ name: 'login', query: { notice: 'password-changed' } });
    expect(auth.token).toBeNull();
    expect(auth.userId).toBeNull();
    expect(callLog.passwordKeys).toHaveLength(1);
    expect(callLog.passwordKeys[0]).not.toBeNull();
  });

  it('wipes both password fields and leaves nothing of them in browser storage', async () => {
    const { wrapper: view } = await mountView();
    await submitPasswords(view, 'rightpass123', 'brandnewpass1');
    await vi.waitFor(() => expect(useAuthStore().token).toBeNull());

    const fields = passwordFields(view);
    expect(fields.current.value).toBe('');
    expect(fields.next.value).toBe('');
    // 口令在契约上是 writeOnly：它不进响应、不进日志，也不进 localStorage。
    expect(localStorage.length).toBe(0);
  });
});
