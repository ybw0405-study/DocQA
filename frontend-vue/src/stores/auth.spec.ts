import { beforeEach, describe, expect, it } from 'vitest';
import { HttpResponse, http as mswHttp } from 'msw';
import { createPinia, setActivePinia } from 'pinia';
import { useAuthStore } from './auth';
import { bindSessionToAuthStore } from './sessionBridge';
import { DEMO_TOKEN, DEMO_USER, envelope, refreshHandler } from '../../tests/fixtures';
import { server } from '../../tests/server';

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
  });

  it('keeps the access token in memory only', async () => {
    const auth = useAuthStore();
    await auth.signIn('demo', 'right-password');

    expect(auth.token).toBe(DEMO_TOKEN);
    expect(auth.userId).toBe(DEMO_USER.userId);
    expect(auth.username).toBe('demo');
    expect(auth.status).toBe('ACTIVE');
    expect(auth.roles).toEqual(['USER', 'ADMIN']);
    expect(auth.isAdmin).toBe(true);
    expect(auth.isAuthenticated).toBe(true);
    expect(auth.resolved).toBe(true);
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(document.cookie).toBe('');
  });

  it('keeps no session when the credentials are rejected', async () => {
    const auth = useAuthStore();
    await expect(auth.signIn('demo', 'bad-password')).rejects.toMatchObject({ status: 401 });

    expect(auth.token).toBeNull();
    expect(auth.isAuthenticated).toBe(false);
    expect(auth.resolved).toBe(false);
  });

  it('opens a session right after registration', async () => {
    const auth = useAuthStore();
    await auth.signUp('zhang.wei', 'correct-horse-9');

    expect(auth.token).toBe(DEMO_TOKEN);
    expect(auth.userId).toBe(DEMO_USER.userId);
    expect(auth.resolved).toBe(true);
  });

  it('hydrates only through the refresh cookie', async () => {
    server.use(refreshHandler());
    const auth = useAuthStore();
    await auth.hydrate();

    expect(auth.token).toBe(DEMO_TOKEN);
    expect(auth.userId).toBe(DEMO_USER.userId);
    expect(auth.resolved).toBe(true);
  });

  it('stays anonymous when the refresh cookie is missing or stale', async () => {
    const auth = useAuthStore();
    await auth.hydrate();

    expect(auth.token).toBeNull();
    expect(auth.resolved).toBe(true);
    expect(auth.isAuthenticated).toBe(false);
  });

  it('drops the session when /auth/me rejects the refreshed token', async () => {
    server.use(refreshHandler('stale-token'));
    const auth = useAuthStore();
    await auth.hydrate();

    expect(auth.token).toBeNull();
    expect(auth.resolved).toBe(true);
  });

  it('shares a single refresh request across concurrent callers', async () => {
    let calls = 0;
    server.use(
      mswHttp.post('/api/v1/auth/refresh', () => {
        calls += 1;
        return HttpResponse.json(envelope({ accessToken: DEMO_TOKEN, tokenType: 'Bearer', expiresIn: 900 }));
      }),
    );
    const auth = useAuthStore();

    const [first, second] = await Promise.all([
      auth.refreshAccessToken(),
      auth.refreshAccessToken(),
    ]);

    expect(first).toBe(DEMO_TOKEN);
    expect(second).toBe(DEMO_TOKEN);
    expect(calls).toBe(1);
  });

  it('reports a failed refresh as no session instead of throwing', async () => {
    const auth = useAuthStore();

    await expect(auth.refreshAccessToken()).resolves.toBeNull();
    expect(auth.token).toBeNull();
  });

  it('sends the current access token with logout before dropping it', async () => {
    let authorization: string | null = null;
    server.use(
      mswHttp.post('/api/v1/auth/logout', ({ request }) => {
        authorization = request.headers.get('Authorization');
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const auth = useAuthStore();
    auth.$patch({ token: DEMO_TOKEN, userId: DEMO_USER.userId });

    await auth.signOut();

    expect(authorization).toBe(`Bearer ${DEMO_TOKEN}`);
    expect(auth.token).toBeNull();
    expect(auth.userId).toBeNull();
  });

  it('clears local state even when logout cannot reach the server', async () => {
    server.use(mswHttp.post('/api/v1/auth/logout', () => HttpResponse.error()));
    const auth = useAuthStore();
    auth.$patch({ token: DEMO_TOKEN, userId: DEMO_USER.userId, username: 'demo' });

    await auth.signOut();

    expect(auth.token).toBeNull();
    expect(auth.userId).toBeNull();
    expect(auth.username).toBeNull();
    expect(auth.isAuthenticated).toBe(false);
  });
});
