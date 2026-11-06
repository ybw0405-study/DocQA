import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { configureSession, deleteNoContent, get, patch, post, put } from './http';
import { ApiError, NETWORK_ERROR_CODE, UNKNOWN_ERROR_CODE } from './errors';
import { DEMO_TOKEN, echoHandlers, envelope, problem, type Echo } from '../../tests/fixtures';
import { server } from '../../tests/server';

const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9._:-]+$/;
const FRESH_TOKEN = 'fresh-access-token';

function unauthorized() {
  return HttpResponse.json(
    problem({ status: 401, detail: '访问令牌已过期。', code: 'USR-AUTH-002' }),
    { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

describe('http layer', () => {
  let token: string | null;
  let refreshCalls: number;
  let refreshOutcome: string | null;
  let unauthorizedCalls: number;

  /** 会话钩子的行为由用例显式给定，避免断言 axios 内部对象。 */
  function installSession(initial: string | null = null, outcome: string | null = null): void {
    token = initial;
    refreshOutcome = outcome;
    configureSession({
      accessToken: () => token,
      refreshAccessToken: async () => {
        refreshCalls += 1;
        token = refreshOutcome;
        return refreshOutcome;
      },
      onUnauthorized: () => {
        unauthorizedCalls += 1;
      },
    });
  }

  beforeEach(() => {
    refreshCalls = 0;
    unauthorizedCalls = 0;
    installSession();
    server.use(...echoHandlers());
  });

  afterEach(() => {
    installSession();
  });

  it('returns only the data payload of a successful envelope', async () => {
    await expect(get<Echo>('/echo')).resolves.toMatchObject({ authorization: null });
  });

  it('attaches the bearer token supplied by the session hook', async () => {
    installSession(DEMO_TOKEN);
    await expect(get<Echo>('/echo')).resolves.toMatchObject({ authorization: `Bearer ${DEMO_TOKEN}` });
  });

  it('sends no idempotency key on GET', async () => {
    await expect(get<Echo>('/echo')).resolves.toMatchObject({ idempotencyKey: null });
  });

  it('generates a contract-shaped idempotency key for POST', async () => {
    const echo = await post<Echo>('/echo', { ping: true });
    expect(echo.idempotencyKey).not.toBeNull();
    expect(echo.idempotencyKey).toMatch(IDEMPOTENCY_KEY_PATTERN);
    expect((echo.idempotencyKey as string).length).toBeLessThanOrEqual(64);
  });

  it('keeps the idempotency key chosen by the caller', async () => {
    const echo = await post<Echo>('/echo', { ping: true }, 'ask:fixed-key-0001');
    expect(echo.idempotencyKey).toBe('ask:fixed-key-0001');
  });

  it('treats PUT and PATCH as writes that need a key of their own', async () => {
    const replaced = await put<Echo>('/echo', { tags: [] });
    const renamed = await patch<Echo>('/echo', { title: '新标题' }, 'rename:fixed-key-0002');

    expect(replaced.idempotencyKey).toMatch(IDEMPOTENCY_KEY_PATTERN);
    expect(renamed.idempotencyKey).toBe('rename:fixed-key-0002');
  });

  it('resolves a bodyless 204 and still maps its error envelope', async () => {
    await expect(deleteNoContent('/echo')).resolves.toBeUndefined();
    await expect(deleteNoContent('/echo', 'delete:fixed-key')).resolves.toBeUndefined();

    server.use(
      mswHttp.delete('/api/v1/echo', () =>
        HttpResponse.json(problem({ status: 409, code: 'DOC-CONFLICT-001', detail: '解析进行中，暂不可删除。' }), {
          status: 409,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      ),
    );
    const error = await deleteNoContent('/echo').catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('DOC-CONFLICT-001');
  });

  it('still issues a unique idempotency key when randomUUID is unavailable', async () => {
    const original = Object.getOwnPropertyDescriptor(crypto, 'randomUUID');
    Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true, writable: true });
    try {
      const first = await post<Echo>('/echo', { ping: 1 });
      const second = await post<Echo>('/echo', { ping: 2 });
      expect(first.idempotencyKey).toMatch(IDEMPOTENCY_KEY_PATTERN);
      expect(first.idempotencyKey).toMatch(/^[0-9a-f]{32}$/);
      expect(second.idempotencyKey).not.toBe(first.idempotencyKey);
    } finally {
      if (original === undefined) {
        delete (crypto as unknown as { randomUUID?: unknown }).randomUUID;
      } else {
        Object.defineProperty(crypto, 'randomUUID', original);
      }
    }
    const restored = await post<Echo>('/echo', { ping: 3 });
    expect(restored.idempotencyKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  });

  it('maps a ProblemDetail body onto ApiError including field errors', async () => {
    server.use(
      mswHttp.get('/api/v1/boom', () =>
        HttpResponse.json(
          problem({ status: 422, code: 'REM-VALIDATION-001', detail: '字段校验未通过。', errors: [{ field: 'title', message: '不得为空' }] }),
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const error = await get('/boom').catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(422);
    expect(apiError.code).toBe('REM-VALIDATION-001');
    expect(apiError.detail).toBe('字段校验未通过。');
    expect(apiError.traceId).toBe('4bf92f3577b34da6a3ce929d0e0e4736');
    expect(apiError.fieldMessage('title')).toBe('不得为空');
    expect(unauthorizedCalls).toBe(0);
    expect(refreshCalls).toBe(0);
  });

  it('signals the session hook on 401 without touching it on 409', async () => {
    server.use(
      mswHttp.get('/api/v1/gone', () => unauthorized()),
      mswHttp.get('/api/v1/conflict', () =>
        HttpResponse.json(problem({ status: 409, detail: '已有执行中的请求。', code: 'AGT-CONFLICT-001' }), { status: 409 }),
      ),
    );

    await expect(get('/gone')).rejects.toMatchObject({ status: 401 });
    expect(unauthorizedCalls).toBe(1);
    await expect(get('/conflict')).rejects.toMatchObject({ status: 409, code: 'AGT-CONFLICT-001' });
    expect(unauthorizedCalls).toBe(1);
  });

  it('treats a rejected login as a credential problem, not a session ending', async () => {
    server.use(mswHttp.post('/api/v1/auth/login', () => unauthorized()));

    await expect(post('/auth/login', { username: 'demo', password: 'nope' })).rejects.toMatchObject({ status: 401 });
    expect(refreshCalls).toBe(0);
    expect(unauthorizedCalls).toBe(0);
  });

  /**
   * 改密的 401 与登录同族：它报的是请求体里的旧口令不对，会话本身仍然有效。
   * 服务端每收到一个这种 401 就记一次登录失败，并在失败时清掉幂等记录，
   * 所以续期重放不是重放，而是把一次笔误记成两次失败。
   */
  it('treats a rejected password change as a credential problem, not a session ending', async () => {
    installSession(DEMO_TOKEN, FRESH_TOKEN);
    server.use(mswHttp.post('/api/v1/auth/password', () => unauthorized()));

    await expect(
      post('/auth/password', { currentPassword: 'wrong-password', newPassword: 'brandnewpass1' }),
    ).rejects.toMatchObject({ status: 401 });

    expect(refreshCalls).toBe(0);
    expect(unauthorizedCalls).toBe(0);
    expect(token).toBe(DEMO_TOKEN);
  });

  it('renews the access token once on 401 and replays the request with it', async () => {
    installSession('stale-token', FRESH_TOKEN);
    const seen: (string | null)[] = [];
    server.use(
      mswHttp.get('/api/v1/guarded', ({ request }) => {
        const authorization = request.headers.get('Authorization');
        seen.push(authorization);
        if (authorization !== `Bearer ${FRESH_TOKEN}`) {
          return unauthorized();
        }
        return HttpResponse.json(envelope({ authorization, idempotencyKey: null }));
      }),
    );

    await expect(get<Echo>('/guarded')).resolves.toMatchObject({ authorization: `Bearer ${FRESH_TOKEN}` });
    expect(seen).toEqual(['Bearer stale-token', `Bearer ${FRESH_TOKEN}`]);
    expect(refreshCalls).toBe(1);
    expect(unauthorizedCalls).toBe(0);
  });

  it('ends the session when renewal fails and never retries twice', async () => {
    installSession('stale-token', null);
    let hits = 0;
    server.use(
      mswHttp.get('/api/v1/guarded', () => {
        hits += 1;
        return unauthorized();
      }),
    );

    await expect(get('/guarded')).rejects.toMatchObject({ status: 401 });
    expect(hits).toBe(1);
    expect(refreshCalls).toBe(1);
    expect(unauthorizedCalls).toBe(1);
  });

  it('does not renew when there is no in-memory token', async () => {
    server.use(mswHttp.get('/api/v1/guarded', () => unauthorized()));

    await expect(get('/guarded')).rejects.toMatchObject({ status: 401 });
    expect(refreshCalls).toBe(0);
    expect(unauthorizedCalls).toBe(1);
  });

  it('never renews or tears down the session from the renewal endpoint itself', async () => {
    installSession('stale-token', FRESH_TOKEN);
    server.use(mswHttp.post('/api/v1/auth/refresh', () => unauthorized()));

    await expect(post('/auth/refresh')).rejects.toMatchObject({ status: 401 });
    expect(refreshCalls).toBe(0);
    // 续期端点自身返回 401 时，会话结束由 hydrate 的调用方决定，拦截器不越位。
    expect(unauthorizedCalls).toBe(0);
  });

  it('reports a transport failure as a client-side network error', async () => {
    server.use(mswHttp.get('/api/v1/down', () => HttpResponse.error()));
    const error = await get('/down').catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(0);
    expect(apiError.code).toBe(NETWORK_ERROR_CODE);
    expect(apiError.isNetworkFailure).toBe(true);
    expect(unauthorizedCalls).toBe(0);
  });

  it('falls back to an unknown code when the error body is not a ProblemDetail', async () => {
    server.use(mswHttp.get('/api/v1/proxy', () => new HttpResponse('<html>Bad Gateway</html>', { status: 502 })));
    const error = await get('/proxy').catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(502);
    expect(apiError.code).toBe(UNKNOWN_ERROR_CODE);
    expect(apiError.traceId).toBeNull();
    expect(apiError.detail).toBe('服务返回了无法解析的错误（HTTP 502）。');
    expect(apiError.detail).not.toContain('Bad Gateway');
  });
});
