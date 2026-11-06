import { beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import { listNotifications, markNotificationsRead } from './notifications';
import { ApiError } from './errors';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import { DEMO_TOKEN, DEMO_TRACE_ID, envelope, notificationData, problem } from '../../tests/fixtures';
import { server } from '../../tests/server';

interface Captured {
  method: string;
  path: string;
  search: string;
  idempotencyKey: string | null;
  body: unknown;
}

const captured: Captured[] = [];

function record(request: Request, body: unknown): void {
  const url = new URL(request.url);
  captured.push({
    method: request.method,
    path: url.pathname,
    search: url.search,
    idempotencyKey: request.headers.get('Idempotency-Key'),
    body,
  });
}

async function expectApiError(action: Promise<unknown>): Promise<ApiError> {
  try {
    await action;
  } catch (error) {
    if (error instanceof ApiError) {
      return error;
    }
    throw error;
  }
  throw new Error('预期抛出 ApiError，但调用成功了');
}

describe('notifications api', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    captured.length = 0;
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
    server.use(
      mswHttp.get('/api/v1/notifications', async ({ request }) => {
        record(request, null);
        return HttpResponse.json(envelope({ items: [notificationData(1)], nextCursor: null, limit: 20 }));
      }),
      mswHttp.post('/api/v1/notifications/read', async ({ request }) => {
        record(request, await request.json());
        return HttpResponse.json(envelope({ updated: 1, all: false }));
      }),
    );
  });

  it('sends the read-only filter as an explicit boolean and omits it for the mixed view', async () => {
    await listNotifications({ unreadOnly: true, limit: 20 });
    await listNotifications({ unreadOnly: false, limit: 20 });
    await listNotifications({ unreadOnly: null, limit: 20 });

    // false 必须真发出去：契约把「缺省」与 false 同义处理，但 null（全部）走的是省略分支。
    expect(captured.map((entry) => entry.search)).toEqual([
      '?unreadOnly=true&limit=20',
      '?unreadOnly=false&limit=20',
      '?limit=20',
    ]);
    expect(captured.every((entry) => entry.idempotencyKey === null)).toBe(true);
  });

  it('carries the cursor without a status parameter because the notification list has none', async () => {
    await listNotifications({ unreadOnly: true, cursor: 'PAGE2', limit: 20 });

    expect(captured[0]?.path).toBe('/api/v1/notifications');
    expect(captured[0]?.search).toBe('?unreadOnly=true&cursor=PAGE2&limit=20');
  });

  it('posts ids without all-true and posts all without ids, never both', async () => {
    await markNotificationsRead({ all: false, ids: ['n-1', 'n-2'] });
    await markNotificationsRead({ all: true });

    // 契约的互斥是 `all=true` 时 ids 必须缺省，`ids: []` 也会被 minItems 拒成 400。
    expect(captured.map((entry) => entry.body)).toEqual([
      { all: false, ids: ['n-1', 'n-2'] },
      { all: true },
    ]);
    expect(captured.every((entry) => entry.method === 'POST')).toBe(true);
  });

  it('lets the interceptor mint a key on the read endpoint that never consumes one', async () => {
    await markNotificationsRead({ all: true });
    await markNotificationsRead({ all: true });

    // 契约上这里不要求幂等键；拦截器补的随机键只是统一形态，两次也不会被判成同键不同指纹。
    expect(captured[0]?.idempotencyKey).not.toBeNull();
    expect(captured[0]?.idempotencyKey).not.toBe(captured[1]?.idempotencyKey);
  });

  it('hands back the count of rows that actually flipped from unread', async () => {
    server.use(
      mswHttp.post('/api/v1/notifications/read', () => HttpResponse.json(envelope({ updated: 0, all: true }))),
    );

    const data = await markNotificationsRead({ all: true });

    // 0 是合法结果（全是已读或越权），不能当成失败处理。
    expect(data).toEqual({ updated: 0, all: true });
  });

  it.each([
    { status: 400, code: 'COM-VALIDATION-001', detail: 'all=true 时不得同时提交 ids。' },
    { status: 401, code: 'USR-AUTH-001', detail: '令牌无效。' },
  ])('keeps $code and the trace for an HTTP $status rejection', async ({ status, code, detail }) => {
    server.use(
      mswHttp.post('/api/v1/notifications/read', () =>
        HttpResponse.json(
          problem({ status, code, detail, instance: '/api/v1/notifications/read' }),
          { status, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const error = await expectApiError(markNotificationsRead({ all: false, ids: ['n-1'] }));

    expect(error.status).toBe(status);
    expect(error.code).toBe(code);
    expect(error.traceId).toBe(DEMO_TRACE_ID);
    expect(error.retryAfterSeconds).toBeNull();
  });
});
