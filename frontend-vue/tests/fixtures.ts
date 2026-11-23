import { HttpResponse, http as mswHttp, type RequestHandler } from 'msw';
import type { ApiEnvelope, ProblemDetail, UserRole, UserStatus } from '../src/types/api';
import type { AskData } from '../src/types/conversations';
import { REMINDER_STATUSES, type ReminderListItemData, type ReminderStatus } from '../src/types/reminders';

export const DEMO_TOKEN = 'demo-access-token';
export const DEMO_USER_ID = '00000000-0000-0000-0000-000000000001';
export const DEMO_TRACE_ID = '4bf92f3577b34da6a3ce929d0e0e4736';

export function envelope<T>(data: T): ApiEnvelope<T> {
  return { code: 'OK', msg: 'success', data, traceId: DEMO_TRACE_ID };
}

export function problem(overrides: Partial<ProblemDetail> = {}): ProblemDetail {
  return {
    type: 'https://docqa.example/problems/validation',
    title: 'Validation failed',
    status: 400,
    detail: '请求参数不合法。',
    instance: '/api/v1/demo',
    code: 'COM-VALIDATION-001',
    traceId: DEMO_TRACE_ID,
    ...overrides,
  };
}

function unauthorizedProblem(): ProblemDetail {
  return problem({
    type: 'https://docqa.example/problems/auth',
    title: 'Authentication required',
    status: 401,
    detail: '用户名或密码错误。',
    instance: '/api/v1/auth/login',
    code: 'USR-AUTH-001',
  });
}

function validationProblem(instance: string, detail: string): Response {
  return HttpResponse.json(
    problem({ status: 400, code: 'COM-VALIDATION-001', detail, instance }),
    { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

export const DEMO_USER = {
  userId: DEMO_USER_ID,
  username: 'demo',
  status: 'ACTIVE' as const,
  roles: ['USER', 'ADMIN'] as const,
  accessTokenExpiresIn: 843,
};

/** 刷新令牌只存在于 HttpOnly Cookie，测试里没有浏览器 Cookie 罐，默认按「没有 Cookie」返回 401。 */
export const authHandlers = [
  mswHttp.post('/api/v1/auth/login', async ({ request }) => {
    const body = (await request.json()) as { username?: string; password?: string };
    if (body.password === 'bad-password') {
      return HttpResponse.json(unauthorizedProblem(), { status: 401, headers: { 'Content-Type': 'application/problem+json' } });
    }
    return HttpResponse.json(envelope({ accessToken: DEMO_TOKEN, tokenType: 'Bearer', expiresIn: 900 }));
  }),
  mswHttp.post('/api/v1/auth/register', async ({ request }) => {
    const body = (await request.json()) as { username?: string; password?: string };
    if (body.username === 'taken') {
      return HttpResponse.json(
        problem({ status: 409, code: 'USR-CONFLICT-001', detail: '用户名已被占用。', instance: '/api/v1/auth/register' }),
        { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }
    if (typeof body.password === 'string' && body.password.length < 10) {
      return HttpResponse.json(
        problem({
          status: 422,
          code: 'USR-VALIDATION-001',
          detail: '口令强度不足。',
          instance: '/api/v1/auth/register',
          errors: [{ field: 'password', message: '至少 10 个字符且同时含字母与数字。' }],
        }),
        { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }
    return HttpResponse.json(
      envelope({ accessToken: DEMO_TOKEN, tokenType: 'Bearer', expiresIn: 900 }),
      { status: 201 },
    );
  }),
  mswHttp.post('/api/v1/auth/refresh', () =>
    HttpResponse.json(
      problem({
        status: 401,
        code: 'USR-REFRESH-001',
        detail: '会话已失效，请重新登录。',
        instance: '/api/v1/auth/refresh',
      }),
      { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  ),
  mswHttp.post('/api/v1/auth/logout', () => new HttpResponse(null, { status: 204 })),
  /**
   * 改密桩。默认 204 且无响应体——契约上这是本组接口唯一的 204，成功后所有凭据失效，
   * 所以调用方必须清会话，这条断言留给视图用例。分支靠哨兵值触发，桩态里**不留下口令本身**：
   * 只记录 `Idempotency-Key`，口令字段在契约上是 writeOnly，测试日志也不是它的去处。
   */
  mswHttp.post('/api/v1/auth/password', async ({ request }) => {
    callLog.passwordKeys.push(request.headers.get('Idempotency-Key'));
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
    const current = typeof body?.currentPassword === 'string' ? body.currentPassword : '';
    const next = typeof body?.newPassword === 'string' ? body.newPassword : '';
    const instance = '/api/v1/auth/password';
    if (current === 'wrong-password') {
      return statusProblem(401, 'USR-AUTH-001', '用户名或密码错误。', instance);
    }
    if (current === 'rate-limited') {
      return HttpResponse.json(
        problem({ status: 429, code: 'USR-RATE_LIMIT-001', detail: '登录失败次数过多，请稍后再试。', instance }),
        { status: 429, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '42' } },
      );
    }
    if (current === 'policy-rejects') {
      return HttpResponse.json(
        problem({
          status: 422,
          code: 'USR-VALIDATION-001',
          detail: '口令强度或授权变更不满足策略要求。',
          instance,
          errors: [{ field: 'newPassword', message: 'must contain at least one letter and one digit' }],
        }),
        { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }
    if (next !== '' && current === next) {
      return HttpResponse.json(
        problem({
          status: 422,
          code: 'USR-VALIDATION-001',
          detail: '新口令不能与当前口令相同。',
          instance,
          errors: [{ field: 'newPassword', message: 'must differ from the current password' }],
        }),
        { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }
    return new HttpResponse(null, { status: 204 });
  }),
  mswHttp.get('/api/v1/auth/me', ({ request }) => {
    if (request.headers.get('Authorization') !== `Bearer ${DEMO_TOKEN}`) {
      return HttpResponse.json(
        problem({ status: 401, detail: '令牌无效。', code: 'USR-AUTH-001', instance: '/api/v1/auth/me' }),
        { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }
    return HttpResponse.json(envelope(DEMO_USER));
  }),
];

/** 让 `/auth/refresh` 像「带有效 Cookie」一样返回指定令牌，供水合与静默续期用例按需挂载。 */
export function refreshHandler(token: string = DEMO_TOKEN): RequestHandler {
  return mswHttp.post('/api/v1/auth/refresh', () =>
    HttpResponse.json(envelope({ accessToken: token, tokenType: 'Bearer', expiresIn: 900 })),
  );
}

/**
 * 覆盖 `/auth/me` 的 roles。`requiresAdmin` 守卫有两个分支，而默认桩回显的是带 ADMIN 的 DEMO_USER，
 * 不给一个「只有 USER」的会话就无法证明非管理员会被弹回。令牌本身仍然沿用 `refreshHandler` 那一条，
 * 两个覆盖点合起来才是一次「刷新成功、但不是管理员」的真实水合。
 */
export function currentUserHandler(roles: UserRole[]): RequestHandler {
  return mswHttp.get('/api/v1/auth/me', () =>
    HttpResponse.json(envelope({ ...DEMO_USER, roles })),
  );
}

export interface Echo {
  authorization: string | null;
  idempotencyKey: string | null;
}

/** 把服务端实际收到的请求头回显进响应体，用于断言拦截器行为而不是断言 axios 内部对象。 */
export function echoHandlers(): RequestHandler[] {
  return [
    mswHttp.get('/api/v1/echo', ({ request }) => HttpResponse.json(envelope(echoOf(request)))),
    mswHttp.post('/api/v1/echo', ({ request }) => HttpResponse.json(envelope(echoOf(request)))),
    mswHttp.put('/api/v1/echo', ({ request }) => HttpResponse.json(envelope(echoOf(request)))),
    mswHttp.patch('/api/v1/echo', ({ request }) => HttpResponse.json(envelope(echoOf(request)))),
    /** 204 没有信封可解包，只能验证「不炸」；请求头由 documents 桩另行记录。 */
    mswHttp.delete('/api/v1/echo', () => new HttpResponse(null, { status: 204 })),
  ];
}

function echoOf(request: Request): Echo {
  return {
    authorization: request.headers.get('Authorization'),
    idempotencyKey: request.headers.get('Idempotency-Key'),
  };
}

export const DEMO_DOCUMENT_ID = '00000000-0000-0000-0000-0000000000a1';
export const DEMO_UPLOAD_ID = DEMO_DOCUMENT_ID;

/** 服务端生成的预签名地址；path 段就是代理要匹配的桶前缀。 */
export const PART_OBJECT_PATH = '/docqa-documents/documents/demo-owner/demo-doc/source';
export const INSTANT_UPLOAD_MD5 = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
const PART_SIZE = 5_242_880;

export function documentListItem(index: number) {
  return {
    documentId: `00000000-0000-0000-0000-00000000000${index}`,
    fileName: `note-${index}.pdf`,
    // 第 2 行留空标题，用于覆盖 `title ?? fileName` 这条展示回退口径。
    title: index === 2 ? null : `笔记 ${index}`,
    tags: index === 1 ? ['考研数学'] : [],
    contentType: 'application/pdf',
    sizeBytes: 1024 + index,
    status: index === 3 ? 'FAILED' : 'INDEXED',
    retryCount: index === 3 ? 3 : 0,
    maxRetry: 3,
    errorCode: index === 3 ? 'DOC-VALIDATION-003' : null,
    indexVersion: 'v1',
    chunkCount: index === 3 ? 0 : 42,
    indexedAt: index === 3 ? null : '2026-10-20T10:17:12Z',
    createdAt: `2026-10-2${index}T10:15:50.000Z`,
    updatedAt: `2026-10-2${index}T10:17:12Z`,
  };
}

/**
 * 契约 `DocumentStatusData` 的 18 个字段全部给齐：状态视图是「键恒在」镜像的前提，
 * 缺一个键就会让前端的 `null` 分支在测试里永远走不到。默认取「已可检索且两侧行数一致」。
 */
export function documentStatusData(documentId: string, overrides: Record<string, unknown> = {}) {
  return {
    documentId,
    fileName: 'calculus-notes.pdf',
    title: '高等数学·第三章笔记',
    tags: ['考研数学'],
    status: 'INDEXED',
    retryCount: 0,
    maxRetry: 3,
    errorCode: null,
    errorMessage: null,
    indexVersion: 'v1',
    pendingIndexVersion: null,
    chunkCount: 42,
    vectorRows: 42,
    vectorConfirmedAt: '2026-10-20T10:17:12Z',
    vectorConsistency: 'MATCHED',
    indexedAt: '2026-10-20T10:17:12Z',
    createdAt: '2026-10-20T10:15:50Z',
    updatedAt: '2026-10-20T10:17:12Z',
    ...overrides,
  };
}

/** 失败态的向量侧从未确认过，因此一致性是 `UNKNOWN` 而不是 `DIVERGED`。 */
export function failedStatusData(documentId: string) {
  return documentStatusData(documentId, {
    title: null,
    tags: [],
    status: 'FAILED',
    retryCount: 3,
    errorCode: 'DOC-VALIDATION-003',
    errorMessage: 'PDF 已加密，无法提取文本。',
    chunkCount: 0,
    vectorRows: null,
    vectorConfirmedAt: null,
    vectorConsistency: 'UNKNOWN',
    indexedAt: null,
  });
}

/** keyset 分页假实现：首页给 1、2 并签发游标，游标页给 3 并到末页，陌生游标按服务端口径 400。 */
export const documentListHandlers = [
  mswHttp.get('/api/v1/documents', ({ request }) => {
    const params = new URL(request.url).searchParams;
    const cursor = params.get('cursor');
    if (cursor === null) {
      return HttpResponse.json(envelope({ items: [documentListItem(1), documentListItem(2)], nextCursor: 'PAGE2', limit: 20 }));
    }
    if (cursor === 'PAGE2') {
      return HttpResponse.json(envelope({ items: [documentListItem(3)], nextCursor: null, limit: 20 }));
    }
    return HttpResponse.json(
      problem({ status: 400, code: 'COM-VALIDATION-001', detail: '请求参数校验失败。', instance: '/api/v1/documents' }),
      { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
    );
  }),
];

export const uploadHandlers = [
  mswHttp.post('/api/v1/documents/upload/init', async ({ request }) => {
    const body = (await request.json()) as { fileName?: string; sizeBytes?: number; contentType?: string; md5?: string };
    const sizeBytes = body.sizeBytes ?? 0;
    const partCount = body.md5 === INSTANT_UPLOAD_MD5 ? 0 : Math.max(1, Math.ceil(sizeBytes / PART_SIZE));
    return HttpResponse.json(
      envelope({
        documentId: DEMO_DOCUMENT_ID,
        uploadId: DEMO_UPLOAD_ID,
        status: body.md5 === INSTANT_UPLOAD_MD5 ? 'INDEXED' : 'UPLOADED',
        instantUpload: body.md5 === INSTANT_UPLOAD_MD5,
        partSize: PART_SIZE,
        partCount,
        expiresAt: body.md5 === INSTANT_UPLOAD_MD5 ? null : '2026-10-20T10:30:00Z',
        parts: Array.from({ length: partCount }, (_, i) => ({
          partNumber: i + 1,
          url: `http://127.0.0.1:9000${PART_OBJECT_PATH}?partNumber=${i + 1}&uploadId=${DEMO_UPLOAD_ID}&X-Amz-Signature=deadbeef`,
          expiresAt: '2026-10-20T10:30:00Z',
        })),
        idempotentReplay: false,
      }),
      { status: 201 },
    );
  }),
  mswHttp.post('/api/v1/documents/:uploadId/complete', async ({ request }) => {
    const body = (await request.json()) as { parts?: { partNumber: number }[] };
    const parts = body.parts ?? [];
    const ascending = parts.every((part, index) => index === 0 || parts[index - 1].partNumber < part.partNumber);
    if (parts.length === 0 || !ascending) {
      return HttpResponse.json(
        problem({
          status: 422,
          code: 'DOC-VALIDATION-004',
          detail: '合并后的对象大小或 MD5 与初始化信息不一致。',
          instance: '/api/v1/documents/upload/complete',
        }),
        { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }
    return HttpResponse.json(
      envelope({
        documentId: DEMO_DOCUMENT_ID,
        uploadId: DEMO_UPLOAD_ID,
        status: 'PARSING',
        parseRequestedAt: '2026-10-20T10:16:00Z',
        retryCount: 0,
        idempotentReplay: false,
      }),
      { status: 202 },
    );
  }),
  mswHttp.get('/api/v1/documents/:documentId/status', ({ params }) =>
    HttpResponse.json(envelope(documentStatusData(String(params.documentId)))),
  ),
  /** 分片 PUT 的目标是对象存储而不是 Java 服务；这里只回一个 ETag，供 complete 提交。 */
  mswHttp.put(PART_OBJECT_PATH, () => new HttpResponse(null, { status: 200, headers: { ETag: '"part-etag"' } })),
];

/**
 * 2.1.0 的五个文档写接口。桩按契约校验入参形态并回显结果，
 * 状态前置条件（retry 只允许 FAILED/DEAD、reindex 只允许 INDEXED）不在这里重实现——
 * 那是服务端的判定，需要 409 的用例各自 `server.use` 覆盖。
 */
export const documentWriteHandlers = [
  mswHttp.patch('/api/v1/documents/:documentId', async ({ request, params }) => {
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
    recordDocumentOp('PATCH', request, body);
    const title = typeof body?.title === 'string' ? body.title.trim() : '';
    const extraKeys = Object.keys(body ?? {}).filter((key) => key !== 'title');
    if (title.length === 0 || title.length > 120 || extraKeys.length > 0) {
      return validationProblem(`/api/v1/documents/${String(params.documentId)}`, '标题不能为空、不超过 120 字，且只接受 title。');
    }
    return HttpResponse.json(envelope(documentStatusData(String(params.documentId), { title })));
  }),
  mswHttp.delete('/api/v1/documents/:documentId', ({ request }) => {
    recordDocumentOp('DELETE', request, null);
    // 204 没有响应体：契约上这里只承诺 MySQL 侧已不可见。
    return new HttpResponse(null, { status: 204 });
  }),
  mswHttp.put('/api/v1/documents/:documentId/tags', async ({ request, params }) => {
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
    recordDocumentOp('PUT', request, body);
    const raw = body?.tags;
    const documentId = String(params.documentId);
    if (!Array.isArray(raw) || raw.length > 20 || raw.some((tag) => typeof tag !== 'string')) {
      return validationProblem(`/api/v1/documents/${documentId}/tags`, '标签必须是至多 20 项的字符串数组。');
    }
    const normalized = (raw as string[]).map((tag) => tag.trim().replace(/\s+/g, ' ').toLowerCase());
    if (normalized.some((tag) => tag.length === 0 || tag.length > 24)) {
      return validationProblem(`/api/v1/documents/${documentId}/tags`, '单个标签归一化后长度必须是 1–24。');
    }
    if (new Set(normalized).size !== normalized.length) {
      return validationProblem(`/api/v1/documents/${documentId}/tags`, '归一化后存在重复标签，不会静默去重。');
    }
    return HttpResponse.json(envelope(documentStatusData(documentId, { tags: normalized })));
  }),
  mswHttp.post('/api/v1/documents/:documentId/retry', ({ request, params }) => {
    recordDocumentOp('POST', request, null);
    const documentId = String(params.documentId);
    return HttpResponse.json(
      envelope(
        documentStatusData(documentId, {
          status: 'PARSING',
          retryCount: 0,
          tags: [],
          errorCode: null,
          errorMessage: null,
          chunkCount: 0,
          vectorRows: null,
          vectorConfirmedAt: null,
          vectorConsistency: 'UNKNOWN',
          indexedAt: null,
        }),
      ),
      { status: 202 },
    );
  }),
  mswHttp.post('/api/v1/documents/:documentId/reindex', ({ request, params }) => {
    recordDocumentOp('POST', request, null);
    // 重建期间 status 保持 INDEXED，在途与否只看 pendingIndexVersion 是否为空。
    return HttpResponse.json(
      envelope(documentStatusData(String(params.documentId), { pendingIndexVersion: 'v2' })),
      { status: 202 },
    );
  }),
];

export const documentHandlers = [...documentListHandlers, ...uploadHandlers, ...documentWriteHandlers];

export const DEMO_CONVERSATION_ID = '00000000-0000-0000-0000-0000000000c1';

/** 桩记录到的实际请求，用于断言「失败重试复用同一键」这类跨请求事实；每个用例前清空。 */
export interface DocumentOpCall {
  method: string;
  path: string;
  key: string | null;
  body: unknown;
}

/** multipart 的 Part 形态只有服务端侧能看到，本地断言必须读真实请求而不是 axios 内部对象。 */
export interface SchedulePartCall {
  name: string;
  /** `string` 表示浏览器按普通表单字段发送（不产生 Part 级 Content-Type），`file` 表示带类型的文件 Part。 */
  kind: 'string' | 'file';
  contentType: string | null;
  value: string;
  sizeBytes: number | null;
}

export interface ScheduleFormCall {
  path: string;
  key: string | null;
  parts: SchedulePartCall[];
}

/** 状态 PATCH 实际收到的请求，用于断言「同三元组复用同键、版本一变就换新键」。 */
export interface StatusUpdateCall {
  reminderId: string;
  key: string | null;
  body: { status?: string; version?: number };
}

/** 管理面操作实际收到的请求：路径、查询串、幂等键头与请求体都按服务端视角记录。 */
export interface AdminOpCall {
  method: string;
  path: string;
  search: string;
  key: string | null;
  body: unknown;
}

export const callLog = {
  askedKeys: [] as string[],
  askedBodies: [] as Record<string, unknown>[],
  createdKeys: [] as string[],
  reminderKeys: [] as string[],
  reminderBodies: [] as Record<string, unknown>[],
  documentOps: [] as DocumentOpCall[],
  scheduleForms: [] as ScheduleFormCall[],
  statusCalls: [] as StatusUpdateCall[],
  notificationReads: [] as Record<string, unknown>[],
  adminOps: [] as AdminOpCall[],
  userOps: [] as AdminOpCall[],
  /** 改密只留幂等键：口令是契约上的 writeOnly 字段，测试账本也不是它的落点。 */
  passwordKeys: [] as (string | null)[],
};

export function clearCallLog(): void {
  callLog.askedKeys.length = 0;
  callLog.askedBodies.length = 0;
  callLog.createdKeys.length = 0;
  callLog.reminderKeys.length = 0;
  callLog.reminderBodies.length = 0;
  callLog.documentOps.length = 0;
  callLog.scheduleForms.length = 0;
  callLog.statusCalls.length = 0;
  callLog.notificationReads.length = 0;
  callLog.adminOps.length = 0;
  callLog.userOps.length = 0;
  callLog.passwordKeys.length = 0;
  clearStubState();
}

function recordDocumentOp(method: string, request: Request, body: unknown): void {
  callLog.documentOps.push({
    method,
    path: new URL(request.url).pathname,
    key: request.headers.get('Idempotency-Key'),
    body,
  });
}

function recordAdminOp(method: string, request: Request, body: unknown): void {
  const url = new URL(request.url);
  callLog.adminOps.push({
    method,
    path: url.pathname,
    search: url.search,
    key: request.headers.get('Idempotency-Key'),
    body,
  });
}

export function conversationListItem(index: number, lastMessageAt: string | null = '2026-10-20T10:17:12Z') {
  return {
    conversationId: `00000000-0000-0000-0000-0000000000c${index}`,
    title: `会话 ${index}`,
    documentScopeIds: [],
    lastMessageAt,
    createdAt: `2026-10-2${index}T10:15:50.000Z`,
  };
}

export function citation(index: number) {
  return {
    documentId: documentListItem(index).documentId,
    chunkIndex: 12 + index,
    score: 0.87,
    snippet: `片段 ${index}`,
    fileName: documentListItem(index).fileName,
  };
}

export function askData(overrides: Record<string, unknown> = {}): AskData {
  return {
    conversationId: DEMO_CONVERSATION_ID,
    messageId: '00000000-0000-0000-0000-0000000000d1',
    answer: '梯度下降沿损失函数的负梯度方向更新参数，学习率决定每步步长。',
    citations: [citation(1)],
    confidence: 0.87,
    budgetStatus: 'OK',
    tokenUsed: 1260,
    toolCallsMade: 1,
    roundsUsed: 2,
    cacheHit: false,
    idempotentReplay: false,
    createdAt: '2026-10-20T10:15:02.200Z',
    ...overrides,
  } as AskData;
}

/** 契约 `AskStreamMeta.data`：行键与 traceId 在流首就固定，本地行因此从第一刻起可对账。 */
export function askStreamMeta(overrides: Record<string, unknown> = {}) {
  return {
    conversationId: DEMO_CONVERSATION_ID,
    messageId: askData().messageId,
    traceId: DEMO_TRACE_ID,
    cacheHit: false,
    ...overrides,
  };
}

/** 契约 `AskStreamDone.data` = `AskData` 全字段 + `traceId`；字段真源以它为准。 */
export function askStreamDone(overrides: Record<string, unknown> = {}) {
  return { ...askData(), traceId: DEMO_TRACE_ID, ...overrides };
}

/** 一帧 SSE 的线上形态：`data` 给对象就 JSON 序列化，给字符串就原样写（用来摆畸形载荷）。 */
export function sseFrame(event: string, data: unknown): string {
  const payload = typeof data === 'string' ? data : JSON.stringify(data);
  return `event: ${event}\r\ndata: ${payload}\r\n\r\n`;
}

/** `meta` + n 条 `delta` + `done` 的成功帧序。 */
export function askStreamFrames(deltas: string[], overrides: Record<string, unknown> = {}): string[] {
  return [
    sseFrame('meta', askStreamMeta()),
    ...deltas.map((text) => sseFrame('delta', { text })),
    sseFrame('done', askStreamDone(overrides)),
  ];
}

export function messageHistoryItem(index: number, role: 'user' | 'assistant') {
  return {
    messageId: `00000000-0000-0000-0000-0000000000e${index}`,
    role,
    content: role === 'user' ? `问题 ${index}` : `回答 ${index}`,
    tokenCount: 24 * index,
    traceId: DEMO_TRACE_ID,
    createdAt: `2026-10-20T10:2${index}:00.000Z`,
  };
}

export const conversationHandlers = [
  mswHttp.get('/api/v1/conversations', ({ request }) => {
    const cursor = new URL(request.url).searchParams.get('cursor');
    if (cursor === null) {
      return HttpResponse.json(
        envelope({ items: [conversationListItem(1), conversationListItem(2)], nextCursor: 'PAGE2', limit: 20 }),
      );
    }
    if (cursor === 'PAGE2') {
      return HttpResponse.json(envelope({ items: [conversationListItem(3, null)], nextCursor: null, limit: 20 }));
    }
    return HttpResponse.json(
      problem({ status: 400, code: 'COM-VALIDATION-001', detail: '游标非法。', instance: '/api/v1/conversations' }),
      { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
    );
  }),
  mswHttp.post('/api/v1/conversations', async ({ request }) => {
    callLog.createdKeys.push(request.headers.get('Idempotency-Key') ?? '');
    const body = (await request.json()) as { title?: string };
    return HttpResponse.json(
      envelope({
        conversationId: DEMO_CONVERSATION_ID,
        title: body.title ?? '新会话',
        documentScopeIds: [],
        idempotentReplay: false,
        createdAt: '2026-10-26T09:00:00Z',
      }),
      { status: 201 },
    );
  }),
  mswHttp.get('/api/v1/conversations/:conversationId/messages', ({ request }) => {
    const params = new URL(request.url).searchParams;
    if (params.get('cursor') === null) {
      return HttpResponse.json(
        envelope({
          conversationId: DEMO_CONVERSATION_ID,
          items: [messageHistoryItem(2, 'assistant'), messageHistoryItem(1, 'user')],
          nextCursor: 'OLDER',
          limit: 20,
        }),
      );
    }
    if (params.get('cursor') === 'OLDER') {
      return HttpResponse.json(
        envelope({
          conversationId: DEMO_CONVERSATION_ID,
          items: [messageHistoryItem(0, 'user')],
          nextCursor: null,
          limit: 20,
        }),
      );
    }
    return HttpResponse.json(
      problem({ status: 400, code: 'COM-VALIDATION-001', detail: '游标非法。', instance: '/api/v1/conversations/messages' }),
      { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
    );
  }),
  mswHttp.post('/api/v1/conversations/:conversationId/ask', async ({ request }) => {
    callLog.askedKeys.push(request.headers.get('Idempotency-Key') ?? '');
    callLog.askedBodies.push((await request.json()) as Record<string, unknown>);
    return HttpResponse.json(envelope(askData()), { status: 201 });
  }),
];

export const DEMO_REMINDER_ID = '00000000-0000-0000-0000-0000000000f1';
export const DEMO_COURSE_REMINDER_ID = '00000000-0000-0000-0000-0000000000fa';

/**
 * 第 2 行是**暂停**样本：契约 D-59 规定暂停只改状态、`nextTriggerAt` 保留原计划值，
 * 所以这一行带着一个未来的时刻却不会响——前端若按「时刻是否为空」分组就会把它说成待触发。
 * 第 3 行只在游标页出现。
 */
export function reminderListItem(index: number): ReminderListItemData {
  const paused = index === 2;
  return {
    reminderId: `00000000-0000-0000-0000-0000000000f${index}`,
    kind: index === 1 ? 'DEADLINE' : 'CUSTOM',
    status: paused ? 'PAUSED' : 'ACTIVE',
    title: `提醒 ${index}`,
    description: index === 1 ? '第三章习题' : null,
    rrule: index === 1 ? 'FREQ=DAILY;COUNT=1' : 'FREQ=WEEKLY;BYDAY=MO;UNTIL=20261231T160000Z',
    dtstart: '2026-10-25T02:00:00Z',
    timezone: 'Asia/Shanghai',
    exdates: paused ? ['2026-11-02T02:00:00Z'] : [],
    // 契约：`DEADLINE` 与 `CUSTOM` 行的提前量已经并进 dtstart，读侧恒为 0。
    leadMinutes: 0,
    nextTriggerAt: `2026-10-2${index}T13:59:59Z`,
    lastTriggeredAt: paused ? '2026-10-19T02:05:00Z' : null,
    version: index,
    createdAt: `2026-10-2${index}T10:15:50.000Z`,
    updatedAt: `2026-10-2${index}T10:17:12Z`,
  };
}

/**
 * 唯一的终态样本：`nextTriggerAt` 为 null 是终态的**结果**而不是判据，
 * 它承载「没有下次触发就显示破折号而不是编一个时刻」与「终态行没有按钮」两条断言。
 */
export function terminalReminderListItem(overrides: Record<string, unknown> = {}) {
  return {
    ...reminderListItem(9),
    reminderId: '00000000-0000-0000-0000-0000000000f9',
    kind: 'DEADLINE',
    status: 'COMPLETED',
    title: '提交实验报告',
    nextTriggerAt: null,
    lastTriggeredAt: '2026-10-20T12:00:00Z',
    ...overrides,
  };
}

/**
 * 唯一带非零提前量的行：只有 `COURSE` 携带真实 `leadMinutes`，
 * 「上课时刻 = 触发时刻 + 提前量」这条显示层换算（D-72）必须有它才测得到。
 */
export function courseReminderListItem(overrides: Record<string, unknown> = {}) {
  return {
    ...reminderListItem(4),
    reminderId: DEMO_COURSE_REMINDER_ID,
    kind: 'COURSE',
    title: '线性代数',
    description: '教学楼 A-203',
    rrule: 'FREQ=WEEKLY;BYDAY=MO;UNTIL=20270110T155959Z',
    exdates: [],
    leadMinutes: 15,
    nextTriggerAt: '2026-10-26T00:50:00Z',
    lastTriggeredAt: '2026-10-19T00:50:00Z',
    status: 'ACTIVE',
    ...overrides,
  };
}

/**
 * 创建响应按请求体回算：`DEADLINE` 的 `nextTriggerAt` 等于 `dueAt` 减 `leadMinutes`，
 * 与契约描述一致；`CUSTOM` 用 `dtstart` 充当样本下一次触发时间，桩不重实现 RRULE 展开。
 */
export function reminderCreateData(body: Record<string, unknown>) {
  const kind = body.kind === 'CUSTOM' ? 'CUSTOM' : 'DEADLINE';
  const leadMinutes = typeof body.leadMinutes === 'number' ? body.leadMinutes : 0;
  const next = kind === 'DEADLINE'
    ? Date.parse(String(body.dueAt)) - leadMinutes * 60_000
    : Date.parse(String(body.dtstart));
  return {
    reminderId: DEMO_REMINDER_ID,
    status: 'ACTIVE',
    kind,
    title: String(body.title ?? ''),
    rrule: kind === 'DEADLINE' ? 'FREQ=DAILY;COUNT=1' : String(body.rrule ?? ''),
    dtstart: kind === 'DEADLINE' ? new Date(next).toISOString() : String(body.dtstart ?? ''),
    timezone: String(body.timezone ?? 'UTC'),
    exdates: [],
    nextTriggerAt: new Date(next).toISOString(),
    version: 0,
    idempotentReplay: false,
    createdAt: '2026-10-28T10:00:00Z',
    updatedAt: '2026-10-28T10:00:00Z',
  };
}

/** 桩的提醒会话态：PATCH 写在这里、GET 读穿它，两条读出来的行因此不会互相矛盾。 */
interface ReminderStubState {
  status: ReminderStatus;
  version: number;
  nextTriggerAt: string | null;
  updatedAt: string;
}

const reminderOverrides = new Map<string, ReminderStubState>();
const statusKeyRegistry = new Map<string, { fingerprint: string; body: Record<string, unknown> }>();
/**
 * 「算不出未来 occurrence」的替身：桩不重实现 RRULE 展开，
 * 用这张表把恢复分支的第四个成因（409 `REM-CONFLICT-004`）显式摆出来。
 */
export const exhaustedReminders = new Set<string>();

function clearStubState(): void {
  reminderOverrides.clear();
  statusKeyRegistry.clear();
  exhaustedReminders.clear();
  notificationReads.clear();
  adminStubState.killSwitch = { active: false, updatedAt: null, updatedBy: null };
  adminStubState.tenantKillSwitch = { active: false, updatedAt: null, updatedBy: null };
  adminStubState.controlsAuditFails = false;
  adminStubState.tenantControlsAuditFails = false;
  adminStubState.limitsKnown = true;
  adminStubState.quotaOverrides.clear();
  adminStubState.missingQuotaUsers.clear();
  adminStubState.langfuseBaseUrl = '';
  usersStubState.roleOverrides.clear();
  usersStubState.statusOverrides.clear();
  modelConnectionsStubState.rows = [];
  modelConnectionsStubState.probeOk = true;
  modelConnectionsStubState.probeFailureReason = 'HTTP_4XX';
  modelConnectionsStubState.presetsFail = false;
  modelConnectionsStubState.nextId = 1;
}

/** 状态机判定只用到看板行的这四个字段。 */
interface StubReminderRow {
  reminderId: string;
  status: string;
  version: number;
  nextTriggerAt: string | null;
  updatedAt: string;
}

/** 桩里的一条看板行：夹具值叠加 PATCH 成功后的覆盖值。 */
function reminderStubRow(reminderId: string): StubReminderRow | null {
  const rows = [reminderListItem(1), reminderListItem(2), reminderListItem(3), courseReminderListItem()];
  const base = rows.find((item) => item.reminderId === reminderId);
  if (base === undefined) {
    return null;
  }
  const override = reminderOverrides.get(reminderId);
  return {
    reminderId,
    status: override?.status ?? base.status,
    version: override?.version ?? base.version,
    nextTriggerAt: override?.nextTriggerAt ?? base.nextTriggerAt,
    updatedAt: override?.updatedAt ?? base.updatedAt,
  };
}

type DashboardRow = ReturnType<typeof reminderListItem>;

/** 读穿桩态：状态变更成功后，看板重读必须看到新状态与新版本。 */
function applyReminderOverride(item: DashboardRow): DashboardRow {
  const override = reminderOverrides.get(item.reminderId);
  if (override === undefined) {
    return item;
  }
  return {
    ...item,
    status: override.status,
    version: override.version,
    nextTriggerAt: override.nextTriggerAt,
    updatedAt: override.updatedAt,
  };
}

function statusProblem(status: number, code: string, detail: string, instance: string) {
  return HttpResponse.json(problem({ status, code, detail, instance }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

/**
 * 判定顺序与 `ReminderStatusService.transition()` 逐条对齐：幂等守卫 → 404 → 同态 no-op →
 * 非法边 409 → 恢复重算无结果 409 → CAS 0 行 409。五种结果互不重叠，客户端才能按错误码分流。
 * `no-op` 回显当前行的真实版本且 `idempotentReplay` 为 false——这与后端的构造调用一致。
 */
const reminderStatusHandlers = [
  mswHttp.patch('/api/v1/reminders/:reminderId', async ({ request, params }) => {
    const reminderId = String(params.reminderId);
    const instance = `/api/v1/reminders/${reminderId}`;
    const key = request.headers.get('Idempotency-Key');
    const body = (await request.json()) as { status?: string; version?: number };
    callLog.statusCalls.push({ reminderId, key, body });

    const fingerprint = `${reminderId}|${String(body.status)}|${String(body.version)}`;
    const recorded = key === null ? undefined : statusKeyRegistry.get(key);
    if (recorded !== undefined) {
      if (recorded.fingerprint !== fingerprint) {
        return statusProblem(409, 'REM-CONFLICT-001', '同一 Idempotency-Key 已用于不同请求。', instance);
      }
      return HttpResponse.json(envelope({ ...recorded.body, idempotentReplay: true }));
    }

    const current = reminderStubRow(reminderId);
    if (current === null) {
      return statusProblem(404, 'REM-NOT_FOUND-001', '提醒不存在。', instance);
    }
    const target = body.status;
    if (current.status === target) {
      return HttpResponse.json(
        envelope({
          reminderId,
          status: current.status,
          nextTriggerAt: current.nextTriggerAt,
          version: current.version,
          updatedAt: current.updatedAt,
          idempotentReplay: false,
        }),
      );
    }
    if (current.status === 'COMPLETED' || current.status === 'CANCELLED') {
      return statusProblem(409, 'REM-CONFLICT-004', '该提醒当前状态不允许此变更。', instance);
    }

    let nextTriggerAt: string | null = null;
    if (target === 'ACTIVE') {
      if (exhaustedReminders.has(reminderId)) {
        // 后端在这里传的是英文 detail，中文动作说明由界面补上（cr-log 观察项）。
        return statusProblem(
          409,
          'REM-CONFLICT-004',
          'the rule has no occurrence at or after now, so the reminder cannot be resumed',
          instance,
        );
      }
      // 恢复是「按当前时刻重算」，绝不能沿用暂停期间保留的那个旧计划值——桩不重实现 RRULE 展开，
      // 只保证返回值来自服务端且与保留值不同，界面才有「时刻确实被重排过」可断言。
      nextTriggerAt = '2026-12-31T01:00:00.000Z';
    } else if (target === 'PAUSED') {
      nextTriggerAt = current.nextTriggerAt;
    }

    if (body.version !== current.version) {
      return statusProblem(409, 'REM-CONFLICT-003', '提醒已被更新，请刷新后重试。', instance);
    }

    const updatedAt = '2026-10-31T12:00:00.000Z';
    const data = {
      reminderId,
      status: target,
      nextTriggerAt,
      version: Number(current.version) + 1,
      updatedAt,
      idempotentReplay: false,
    };
    reminderOverrides.set(reminderId, {
      status: (target ?? current.status) as ReminderStatus,
      version: data.version,
      nextTriggerAt,
      updatedAt,
    });
    if (key !== null) {
      statusKeyRegistry.set(key, { fingerprint, body: data });
    }
    return HttpResponse.json(envelope(data));
  }),
];

export const reminderHandlers = [
  mswHttp.get('/api/v1/reminders', ({ request }) => {
    const params = new URL(request.url).searchParams;
    const status = params.get('status');
    if (status !== null && !REMINDER_STATUSES.includes(status as (typeof REMINDER_STATUSES)[number])) {
      return statusProblem(400, 'COM-VALIDATION-001', '请求参数校验失败。', '/api/v1/reminders');
    }
    const cursor = params.get('cursor');
    // 过滤与分页都在服务端做：先读穿桩态，再按 status 收敛，游标结构保持不变。
    const page = (cursor === null ? [reminderListItem(1), reminderListItem(2)] : [reminderListItem(3)])
      .map(applyReminderOverride)
      .filter((item) => status === null || item.status === status);
    if (cursor === null) {
      return HttpResponse.json(envelope({ items: page, nextCursor: 'PAGE2', limit: 20 }));
    }
    if (cursor === 'PAGE2') {
      return HttpResponse.json(envelope({ items: page, nextCursor: null, limit: 20 }));
    }
    return statusProblem(400, 'COM-VALIDATION-001', '游标非法。', '/api/v1/reminders');
  }),
  mswHttp.post('/api/v1/reminders', async ({ request }) => {
    callLog.reminderKeys.push(request.headers.get('Idempotency-Key') ?? '');
    const body = (await request.json()) as Record<string, unknown>;
    callLog.reminderBodies.push(body);
    return HttpResponse.json(envelope(reminderCreateData(body)), { status: 201 });
  }),
  ...reminderStatusHandlers,
];

export const DEMO_SCHEDULE_ECHO = { fallbackTimezone: 'Asia/Shanghai', leadMinutes: 10 };

/** 逐事件判定样本取契约示例的「待新建」形态：预览里 `CREATE` 的 `reminderId` 必须是 null。 */
export function scheduleItem(overrides: Record<string, unknown> = {}) {
  return {
    uid: 'linear-algebra-2026-fall-mon-0800',
    summary: '线性代数',
    sequence: 3,
    currentSequence: null,
    action: 'CREATE',
    dtstart: '2026-09-07T00:00:00Z',
    timezone: 'Asia/Shanghai',
    rrule: 'FREQ=WEEKLY;BYDAY=MO;UNTIL=20270110T155959Z',
    exdates: ['2026-10-05'],
    nextTriggerAt: '2026-09-06T23:50:00Z',
    followingTriggerAt: '2026-09-13T23:50:00Z',
    reminderId: null,
    description: null,
    ...overrides,
  };
}

/** 文件版本比库内更旧：导入跳过该事件，但既不报错也不回滚整批。 */
export function staleScheduleItem() {
  return scheduleItem({
    uid: 'discrete-math-wed-1000',
    summary: '离散数学',
    sequence: 1,
    currentSequence: 4,
    action: 'STALE',
    dtstart: '2026-09-09T02:00:00Z',
    rrule: 'FREQ=WEEKLY;BYDAY=WE',
    exdates: [],
    nextTriggerAt: '2026-09-09T01:50:00Z',
    followingTriggerAt: '2026-09-16T01:50:00Z',
    reminderId: '0192f3a4-7b2c-7d10-9e8f-1234567890ad',
  });
}

/** 桩复刻契约不变量「`itemCount` 等于四类之和，也等于 `items` 长度」，而不是硬编码计数。 */
function scheduleCounts(items: Record<string, unknown>[]) {
  const countOf = (action: string) => items.filter((item) => item.action === action).length;
  return {
    itemCount: items.length,
    createCount: countOf('CREATE'),
    updateCount: countOf('UPDATE'),
    unchangedCount: countOf('UNCHANGED'),
    staleCount: countOf('STALE'),
  };
}

export function schedulePreviewData(
  echo: { fallbackTimezone: string; leadMinutes: number } = DEMO_SCHEDULE_ECHO,
  items: Record<string, unknown>[] = [scheduleItem(), staleScheduleItem()],
) {
  return { ...scheduleCounts(items), ...echo, items };
}

/**
 * `idempotentReplay` 为真时 `items` 恒为空数组而四类计数保持首次结果——
 * 这条只有桩复刻了，前端「重放没有明细、继续展示预览行」的回退分支才测得到。
 */
export function scheduleImportData(
  echo: { fallbackTimezone: string; leadMinutes: number } = DEMO_SCHEDULE_ECHO,
  items: Record<string, unknown>[] = [scheduleItem(), staleScheduleItem()],
  replay = false,
) {
  return { ...scheduleCounts(items), ...echo, idempotentReplay: replay, items: replay ? [] : items };
}

/**
 * 从请求 `Content-Type` 里取 boundary。契约上这个头只能由浏览器/axios 自己生成，
 * 所以 boundary 也就是「调用方没有手写 Content-Type」的证据。
 */
function multipartBoundary(contentType: string | null): string {
  const match = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType ?? '');
  const boundary = match?.[1] ?? match?.[2];
  if (boundary === undefined) {
    throw new Error(`请求头没有可用的 multipart boundary：${String(contentType)}`);
  }
  return boundary.trim();
}

/**
 * 按 boundary 切原始信封，而不是调 `request.formData()`：Node 侧 undici 会用 `webidl.is.File`
 * 做品牌检查，认不出 jsdom 的 `File`/`Blob` 实例，handler 会直接抛异常。
 * 解析字节还有一个好处——它断言的是服务端真正看到的 Part 头形态，而不是 JS 对象的类型。
 */
function parseMultipartParts(raw: string, boundary: string): SchedulePartCall[] {
  const parts: SchedulePartCall[] = [];
  for (const chunk of raw.split(`--${boundary}`)) {
    const segment = chunk.replace(/^\r\n/, '');
    // 首段是空前导、末段是闭合标记 `--`，两者都没有 Part 头。
    if (segment === '' || segment.startsWith('--')) {
      continue;
    }
    const headEnd = segment.indexOf('\r\n\r\n');
    if (headEnd < 0) {
      continue;
    }
    const headers = segment.slice(0, headEnd);
    const content = segment.slice(headEnd + 4).replace(/\r\n$/, '');
    const name = /Content-Disposition: form-data; name="([^"]*)"/i.exec(headers)?.[1];
    if (name === undefined) {
      continue;
    }
    const filename = /filename="([^"]*)"/i.exec(headers)?.[1];
    const contentType = /(?:^|\r\n)Content-Type: ([^\r\n]+)/i.exec(headers)?.[1]?.trim() ?? null;
    parts.push({
      name,
      kind: filename === undefined ? 'string' : 'file',
      contentType,
      value: filename ?? content,
      sizeBytes: filename === undefined ? null : new TextEncoder().encode(content).length,
    });
  }
  return parts;
}

export async function recordScheduleForm(request: Request): Promise<ScheduleFormCall> {
  const boundary = multipartBoundary(request.headers.get('content-type'));
  const parts = parseMultipartParts(new TextDecoder().decode(await request.arrayBuffer()), boundary);
  const call: ScheduleFormCall = {
    path: new URL(request.url).pathname,
    key: request.headers.get('Idempotency-Key'),
    parts,
  };
  callLog.scheduleForms.push(call);
  return call;
}

export function schedulePart(call: ScheduleFormCall, name: string): SchedulePartCall | undefined {
  return call.parts.find((part) => part.name === name);
}

/** 契约上响应要回显「本次判定实际使用的兜底时区」，因此这两个值取自提交的 Part 而不是写死。 */
function echoOfForm(call: ScheduleFormCall) {
  const minutes = Number(schedulePart(call, 'leadMinutes')?.value ?? '');
  return {
    fallbackTimezone: schedulePart(call, 'fallbackTimezone')?.value ?? '',
    leadMinutes: Number.isFinite(minutes) ? minutes : -1,
  };
}

export const scheduleHandlers = [
  mswHttp.post('/api/v1/reminders/ics/preview', async ({ request }) => {
    const call = await recordScheduleForm(request);
    return HttpResponse.json(envelope(schedulePreviewData(echoOfForm(call))));
  }),
  mswHttp.post('/api/v1/reminders/ics/import', async ({ request }) => {
    const call = await recordScheduleForm(request);
    return HttpResponse.json(envelope(scheduleImportData(echoOfForm(call))), { status: 201 });
  }),
];

export const DEMO_NOTIFICATION_READ_AT = '2026-10-31T12:00:00.000Z';

/**
 * 契约 `NotificationData` 的十个字段全部给齐：`description`/`nextTriggerAt`/`readAt` 是
 * 「键恒在、值可为 null」的镜像前提，缺键会让前端的 null 分支在测试里永远走不到。
 * 第 1 行是未读的课表提醒（配合 COURSE 行的 `leadMinutes` 测上课时刻换算），
 * 第 2 行是已读的截止提醒，第 3 行只在游标页出现。
 */
export function notificationData(index: number, overrides: Record<string, unknown> = {}) {
  const rows: Record<number, Record<string, unknown>> = {
    1: {
      notificationId: `00000000-0000-0000-0000-0000000000b${index}`,
      reminderId: DEMO_COURSE_REMINDER_ID,
      kind: 'COURSE',
      title: '线性代数',
      description: null,
      plannedTriggerAt: '2026-10-26T00:50:00Z',
      nextTriggerAt: '2026-10-27T00:50:00Z',
      deliveredAt: '2026-10-26T00:50:03Z',
      readAt: null,
      createdAt: '2026-10-26T00:50:03Z',
    },
    2: {
      notificationId: `00000000-0000-0000-0000-0000000000b${index}`,
      reminderId: reminderListItem(1).reminderId,
      kind: 'DEADLINE',
      title: '提交线性代数作业',
      description: '第三章习题',
      plannedTriggerAt: '2026-10-21T13:59:59Z',
      nextTriggerAt: null,
      deliveredAt: '2026-10-21T14:00:01Z',
      readAt: '2026-10-21T14:00:05Z',
      createdAt: '2026-10-21T14:00:01Z',
    },
    3: {
      notificationId: `00000000-0000-0000-0000-0000000000b${index}`,
      reminderId: reminderListItem(3).reminderId,
      kind: 'CUSTOM',
      title: '每周错题复盘',
      description: null,
      plannedTriggerAt: '2026-10-19T02:05:00Z',
      nextTriggerAt: '2026-10-26T02:00:00Z',
      deliveredAt: '2026-10-19T02:05:02Z',
      readAt: null,
      createdAt: '2026-10-19T02:05:02Z',
    },
  };
  return { ...(rows[index] ?? rows[1]), ...overrides };
}

/** 已读的桩态：`readAt` 由 null 变有只发生在这里，重复标记不会改写它。 */
const notificationReads = new Map<string, string>();

function readAtOf(notification: Record<string, unknown>): string | null {
  const stamped = notificationReads.get(String(notification.notificationId));
  if (stamped !== undefined) {
    return stamped;
  }
  return (notification.readAt as string | null) ?? null;
}

function notificationPage(items: Record<string, unknown>[], nextCursor: string | null, limit: number) {
  const rows = items.map((item) => ({ ...item, readAt: readAtOf(item) }));
  return HttpResponse.json(envelope({ items: rows, nextCursor, limit }));
}

export const notificationHandlers = [
  mswHttp.get('/api/v1/notifications', ({ request }) => {
    const params = new URL(request.url).searchParams;
    const unreadOnly = params.get('unreadOnly') === 'true';
    const all = [notificationData(1), notificationData(2), notificationData(3)];
    const visible = unreadOnly ? all.filter((item) => readAtOf(item) === null) : all;
    const limit = Number(params.get('limit') ?? '20');
    const cursor = params.get('cursor');
    if (cursor === null) {
      // 排序键是 `plannedTriggerAt DESC, id DESC`；前两行落在首页，第三行在游标页。
      const first = visible.slice(0, 2);
      return notificationPage(first, visible.length > 2 ? 'PAGE2' : null, limit);
    }
    if (cursor === 'PAGE2') {
      return notificationPage(visible.slice(2), null, limit);
    }
    return statusProblem(400, 'COM-VALIDATION-001', '游标非法。', '/api/v1/notifications');
  }),
  mswHttp.post('/api/v1/notifications/read', async ({ request }) => {
    const body = (await request.json().catch(() => null)) as { all?: boolean; ids?: string[] } | null;
    callLog.notificationReads.push(body ?? {});
    const instance = '/api/v1/notifications/read';
    const reject = (detail: string) => statusProblem(400, 'COM-VALIDATION-001', detail, instance);
    if (typeof body?.all !== 'boolean') {
      return reject('all 是必填布尔值。');
    }
    const ids = body.ids ?? [];
    if (body.all && body.ids !== undefined) {
      // 契约的 `minItems: 1` 让 `ids: []` 也一并被拒，两种形态都是 400。
      return reject('all=true 时不得同时提交 ids。');
    }
    if (!body.all && ids.length === 0) {
      return reject('all=false 时 ids 至少需要一项。');
    }
    if (ids.length > 500) {
      return reject('一次最多提交 500 个通知 id。');
    }

    const allRows = [notificationData(1), notificationData(2), notificationData(3)];
    const targets = body.all ? allRows : allRows.filter((item) => ids.includes(String(item.notificationId)));
    let updated = 0;
    for (const row of targets) {
      if (readAtOf(row) === null) {
        notificationReads.set(String(row.notificationId), DEMO_NOTIFICATION_READ_AT);
        updated += 1;
      }
    }
    // 越权与不存在的 id 不在集合内：不逐项报错、不返回 404，`updated` 只数真正由空变有的行。
    return HttpResponse.json(envelope({ updated, all: body.all }));
  }),
];

export const DEMO_TARGET_USER_ID = '0192f3a4-9d4e-7f32-8a01-1234567890ab';

/**
 * 管理面桩态：Kill Switch 的**事实**存在这里，PUT 写它、GET 读它，
 * 于是「写成功后必须重读才拿到新值」这条 store 纪律在测试里是真的，而不是靠夹具硬编码两遍。
 */
export const adminStubState = {
  /** 平台级一键全停键：`GET/PUT /admin/ai/controls` 读写它。 */
  killSwitch: { active: false, updatedAt: null as string | null, updatedBy: null as string | null },
  /**
   * 租户级键：`GET/PUT /admin/ai/controls/tenant` 读写它。与平台级键独立存在，
   * Java/Python 执行点按 OR 生效；本桩刻意分开两个事实源，才能验出「全局开着时
   * 关掉本租户键、有效状态仍为 true」这条最容易被界面撒谎掩盖的语义。
   */
  tenantKillSwitch: { active: false, updatedAt: null as string | null, updatedBy: null as string | null },
  /** 摆出 S9-5b 的形态：平台级开关已写入 Redis，随后审计失败 → 500 且 detail 说明状态已变更。 */
  controlsAuditFails: false,
  /** 租户级开关的同一形态：状态已变更、审计写入失败。 */
  tenantControlsAuditFails: false,
  /** Python 未发布上限（离线部署或启动晚于网关）时为 false，两个上限随之为 null。 */
  limitsKnown: true,
  quotaOverrides: new Map<string, { dailyQuestionLimit: number | null; dailyTokenLimit: number | null; updatedAt: string }>(),
  missingQuotaUsers: new Set<string>(),
  /** 可观测性入口：空串 = 未部署 LangFuse（默认形态），置值后审计页 traceId 渲染外链。 */
  langfuseBaseUrl: '' as string,
};

export const KILL_SWITCH_WRITE_AT = '2026-11-03T10:04:11.508Z';

/** 模型连接管理面的桩：行集合可变，便于验创建/生效/删除与「至多一条生效」。 */
export const modelConnectionsStubState = {
  rows: [] as Record<string, unknown>[],
  /** 探测结论：ok=false 时按 failureReason 返回，用于验向导的失败与降级分支。 */
  probeOk: true,
  probeFailureReason: 'HTTP_4XX' as string,
  /** 置真时预设目录返回 500，验「读取失败仍可走自定义端点」。 */
  presetsFail: false,
  nextId: 1,
};

export const MODEL_CONNECTION_WRITE_AT = '2026-11-22T10:15:42.010Z';

export function modelConnectionItem(overrides: Record<string, unknown> = {}) {
  return {
    id: '0192f3a4-9d4e-7f32-8a01-1234567890b1',
    name: 'DeepSeek 主连接',
    presetId: 'deepseek',
    baseUrl: 'https://api.deepseek.com/v1',
    model: 'deepseek-chat',
    enabled: true,
    active: false,
    hasCredential: true,
    probeStatus: 'OK',
    lastProbedAt: MODEL_CONNECTION_WRITE_AT,
    createdAt: '2026-11-22T10:12:03.004Z',
    updatedAt: MODEL_CONNECTION_WRITE_AT,
    updatedBy: DEMO_USER_ID,
    ...overrides,
  };
}

/** 预设目录桩：公开端点与模型别名，不含任何密钥。 */
export function modelPresetItems() {
  return [
    {
      id: 'deepseek',
      vendor: '深度求索',
      mark: 'DS',
      label: 'DeepSeek',
      model: 'deepseek-chat',
      models: ['deepseek-chat', 'deepseek-reasoner'],
      baseUrl: 'https://api.deepseek.com/v1',
      keyUrl: 'https://platform.deepseek.com',
      keyHint: 'DeepSeek 开放平台创建 API Key',
      group: 'recommended',
      tag: '热门',
      note: 'reasoner 为思考模式；别名会随版本升级，以控制台为准',
      noKey: false,
      needsServerOptIn: false,
    },
    {
      id: 'ollama',
      vendor: 'Ollama',
      mark: 'OL',
      label: '本地 Ollama',
      model: 'qwen2.5:7b',
      models: ['qwen2.5:7b', 'llama3.1:8b'],
      baseUrl: 'http://127.0.0.1:11434/v1',
      keyUrl: '',
      keyHint: '本地模型无需 API Key',
      group: 'local',
      tag: '需服务端放行',
      note: 'http + 回环地址默认被出网策略拦截，需服务端打开 DOCQA_LLM_ALLOW_PRIVATE',
      noKey: true,
      needsServerOptIn: true,
    },
  ];
}

export function adminControlsData(overrides: Record<string, unknown> = {}) {
  return {
    killSwitch: { ...adminStubState.killSwitch },
    experimentBudget: {
      callsUsed: 22,
      tokensUsed: 2096,
      callLimit: adminStubState.limitsKnown ? 200 : null,
      tokenLimit: adminStubState.limitsKnown ? 200000 : null,
      limitsKnown: adminStubState.limitsKnown,
    },
    ...overrides,
  };
}

/** 第 2 行的 token 维度为 null：契约上「该维度不设上限」必须在表格里可读，不能显示成 0。 */
export function aiQuotaItem(index: number) {
  const userId = index === 1 ? DEMO_TARGET_USER_ID : `0192f3a4-9d4e-7f32-8a01-1234567890a${index}`;
  const override = adminStubState.quotaOverrides.get(userId);
  const base = {
    userId,
    username: index === 3 ? null : index === 1 ? 'zhang.wei' : 'li.na',
    dailyQuestionLimit: index === 1 ? 30 : 100,
    dailyTokenLimit: index === 1 ? 60000 : null,
    updatedAt: `2026-11-0${4 - index}T09:20:0${index}.004Z`,
    updatedBy: DEMO_USER_ID,
  };
  return override === undefined ? base : { ...base, ...override };
}

/**
 * 三行都是契约示例与真实事件类型：`ADMIN_AI_QUOTA_CLEARED` 只在游标页出现，
 * 让「清除覆盖」这一动作在审计页有对应的行可查。payload 键全部取自三侧共同的 17 键白名单。
 */
export function auditEventItem(index: number, overrides: Record<string, unknown> = {}) {
  const rows: Record<number, Record<string, unknown>> = {
    1: {
      eventId: '0192f3a4-b2c3-7d44-8e02-1234567890ab',
      traceId: DEMO_TRACE_ID,
      userId: DEMO_TARGET_USER_ID,
      eventType: 'RAG_RETRIEVED',
      agentName: 'rag_retriever',
      source: 'PYTHON',
      severity: 'INFO',
      payload: { chunkCount: 6, durationMs: 182, topScore: 0.87 },
      occurredAt: '2026-11-03T10:00:01.120Z',
    },
    2: {
      eventId: '0192f3a4-b2c3-7d44-8e02-1234567890ac',
      traceId: DEMO_TRACE_ID,
      userId: DEMO_USER_ID,
      eventType: 'ADMIN_AI_QUOTA_UPDATED',
      agentName: 'admin_gateway',
      source: 'JAVA',
      severity: 'INFO',
      payload: { targetUserId: DEMO_TARGET_USER_ID },
      occurredAt: '2026-11-03T10:15:42.009Z',
    },
    3: {
      eventId: '0192f3a4-b2c3-7d44-8e02-1234567890ad',
      traceId: 'a1b2c3d4e5f60718293a4b5c6d7e8f90',
      userId: DEMO_USER_ID,
      eventType: 'ADMIN_AI_QUOTA_CLEARED',
      agentName: 'admin_gateway',
      source: 'JAVA',
      severity: 'WARN',
      payload: { targetUserId: DEMO_TARGET_USER_ID },
      occurredAt: '2026-11-02T18:41:55.271Z',
    },
  };
  return { ...(rows[index] ?? rows[1]), ...overrides };
}

export function usageSummaryData(overrides: Record<string, unknown> = {}) {
  return {
    from: '2026-11-02T09:00:00Z',
    to: '2026-11-03T09:00:00Z',
    bucket: 'day',
    totals: {
      questions: 128,
      success: 113,
      budgetExhausted: 6,
      failed: 9,
      tokens: 412882,
      toolCalls: 74,
      rounds: 301,
      cacheHits: 17,
      avgLatencyMs: 2140.5,
    },
    byDay: [
      { date: '2026-11-02', questions: 61, tokens: 198402, failed: 5 },
      { date: '2026-11-03', questions: 67, tokens: 214480, failed: 4 },
    ],
    // 契约的四值枚举里只有实际出现的状态会成行；缺 RUNNING 是正常形态，不是数据丢失。
    byStatus: [
      { status: 'SUCCESS', count: 113 },
      { status: 'BUDGET_EXHAUSTED', count: 6 },
      { status: 'FAILED', count: 9 },
    ],
    topUsers: [
      { userId: DEMO_TARGET_USER_ID, username: 'zhang.wei', questions: 44, tokens: 142731 },
      { userId: '0192f3a4-9d4e-7f32-8a01-1234567890ac', username: 'li.na', questions: 27, tokens: 88104 },
    ],
    ...overrides,
  };
}

function adminReject(instance: string, detail: string) {
  return statusProblem(400, 'COM-VALIDATION-001', detail, instance);
}

/** 契约参数 `TimeFrom`/`TimeTo` 的窗口判定：起点必须早于终点，跨度不超过 31 天。 */
function windowProblem(instance: string, from: string | null, to: string | null): Response | null {
  if (from === null || to === null) {
    return null;
  }
  const start = Date.parse(from);
  const end = Date.parse(to);
  if (Number.isNaN(start) || Number.isNaN(end) || start >= end) {
    return adminReject(instance, 'from 必须是可解析的时刻且早于 to。');
  }
  if (end - start > 31 * 24 * 60 * 60 * 1000) {
    return adminReject(instance, '时间窗跨度超过 31 天。');
  }
  return null;
}

function pageRows<T>(rows: T[], cursor: string | null, instance: string, pageSize: number, limit: number) {
  if (cursor === null) {
    const first = rows.slice(0, pageSize);
    return HttpResponse.json(envelope({ items: first, nextCursor: rows.length > pageSize ? 'PAGE2' : null, limit }));
  }
  if (cursor === 'PAGE2') {
    return HttpResponse.json(envelope({ items: rows.slice(pageSize), nextCursor: null, limit }));
  }
  return statusProblem(400, 'COM-VALIDATION-001', '游标非法或已失效。', instance);
}

/**
 * 管理面八操作（S9 六个平台级 + 2.0.0 两个租户级）。桩按契约校验入参形态
 * （`additionalProperties: false`、配额两字段全量、pattern、`limit` 上下限、31 天窗口、未知游标 400）
 * 并把写入结果落到桩态，这样前端「不乐观合入、成功后重读」的纪律才有可断言的服务端事实。
 * Redis 与 MySQL 的真实行为不在这里复刻——那是 S9 各服务与它们自己的测试的范围。
 *
 * 租户级两个操作刻意与平台级键分开存：`active` 是「全局 OR 本租户」的叠加结果，
 * 但 `updatedAt` / `updatedBy` 只来自租户键 meta（对齐 Java `getTenantKillSwitch`）。
 */
export const adminHandlers = [
  mswHttp.get('/api/v1/admin/ai/controls', ({ request }) => {
    recordAdminOp('GET', request, null);
    return HttpResponse.json(envelope(adminControlsData()));
  }),
  mswHttp.put('/api/v1/admin/ai/controls', async ({ request }) => {
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
    recordAdminOp('PUT', request, body);
    const extraKeys = Object.keys(body ?? {}).filter((key) => key !== 'killSwitchActive');
    if (typeof body?.killSwitchActive !== 'boolean' || extraKeys.length > 0) {
      // 契约上没有 version：多一个键就是 additionalProperties:false 的 400。
      return adminReject('/api/v1/admin/ai/controls', '请求体只接受 killSwitchActive 一个布尔字段。');
    }
    const active = body.killSwitchActive;
    adminStubState.killSwitch = { active, updatedAt: KILL_SWITCH_WRITE_AT, updatedBy: DEMO_USER_ID };
    if (adminStubState.controlsAuditFails) {
      return HttpResponse.json(
        problem({
          status: 500,
          code: 'COM-INTERNAL-001',
          detail: '开关状态已变更，但管理审计事件写入失败。',
          instance: '/api/v1/admin/ai/controls',
        }),
        { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }
    return HttpResponse.json(envelope(adminControlsData()));
  }),
  mswHttp.get('/api/v1/admin/ai/controls/tenant', ({ request }) => {
    recordAdminOp('GET', request, null);
    // 有效状态 = 平台级 OR 本租户；元数据只来自租户键 meta，与 Java getTenantKillSwitch 一致。
    return HttpResponse.json(envelope({
      active: adminStubState.killSwitch.active || adminStubState.tenantKillSwitch.active,
      updatedAt: adminStubState.tenantKillSwitch.updatedAt,
      updatedBy: adminStubState.tenantKillSwitch.updatedBy,
    }));
  }),
  mswHttp.put('/api/v1/admin/ai/controls/tenant', async ({ request }) => {
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
    recordAdminOp('PUT', request, body);
    const instance = '/api/v1/admin/ai/controls/tenant';
    const extraKeys = Object.keys(body ?? {}).filter((key) => key !== 'killSwitchActive');
    if (typeof body?.killSwitchActive !== 'boolean' || extraKeys.length > 0) {
      return adminReject(instance, '请求体只接受 killSwitchActive 一个布尔字段。');
    }
    const active = body.killSwitchActive;
    adminStubState.tenantKillSwitch = { active, updatedAt: KILL_SWITCH_WRITE_AT, updatedBy: DEMO_USER_ID };
    if (adminStubState.tenantControlsAuditFails) {
      return HttpResponse.json(
        problem({
          status: 500,
          code: 'COM-INTERNAL-001',
          detail: '租户 Kill Switch 状态已变更，但管理审计事件写入失败；请重新读取状态。',
          instance,
        }),
        { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }
    // 回显仍是 OR 叠加后的有效状态：平台级键开着时，写入 false 拿到的 active 依旧是 true。
    return HttpResponse.json(envelope({
      active: adminStubState.killSwitch.active || active,
      updatedAt: KILL_SWITCH_WRITE_AT,
      updatedBy: DEMO_USER_ID,
    }));
  }),
  mswHttp.get('/api/v1/admin/ai/quotas', ({ request }) => {
    recordAdminOp('GET', request, null);
    const params = new URL(request.url).searchParams;
    const instance = '/api/v1/admin/ai/quotas';
    const limit = Number(params.get('limit') ?? '50');
    if (!Number.isInteger(limit) || limit < 1 || limit > 200) {
      return adminReject(instance, 'limit 必须是 1–200 的整数。');
    }
    return pageRows(
      [aiQuotaItem(1), aiQuotaItem(2), aiQuotaItem(3)],
      params.get('cursor'),
      instance,
      2,
      limit,
    );
  }),
  mswHttp.put('/api/v1/admin/ai/quotas/:userId', async ({ request, params }) => {
    const userId = String(params.userId);
    const instance = `/api/v1/admin/ai/quotas/${userId}`;
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
    recordAdminOp('PUT', request, body);
    const payload = body ?? {};
    const keys = Object.keys(payload);
    // 契约要求两个字段都出现：省略其中一个不是「保持原值」，而是 400。
    if (keys.length !== 2 || !keys.includes('dailyQuestionLimit') || !keys.includes('dailyTokenLimit')) {
      return adminReject(instance, 'dailyQuestionLimit 与 dailyTokenLimit 必须同时出现，可为 null。');
    }
    const invalid = (key: string) => {
      const value = payload[key];
      if (value === null) {
        return false;
      }
      return typeof value !== 'number' || !Number.isInteger(value) || value < 1 || value > 4294967295;
    };
    if (invalid('dailyQuestionLimit') || invalid('dailyTokenLimit')) {
      return adminReject(instance, '上限必须是 1–4294967295 的整数或 null。');
    }
    if (adminStubState.missingQuotaUsers.has(userId)) {
      return statusProblem(404, 'USR-NOT_FOUND-001', '目标用户不存在。', instance);
    }
    adminStubState.quotaOverrides.set(userId, {
      dailyQuestionLimit: payload.dailyQuestionLimit as number | null,
      dailyTokenLimit: payload.dailyTokenLimit as number | null,
      updatedAt: KILL_SWITCH_WRITE_AT,
    });
    const row = aiQuotaItem(1);
    return HttpResponse.json(
      envelope({
        ...row,
        userId,
        username: userId === DEMO_TARGET_USER_ID ? 'zhang.wei' : null,
        dailyQuestionLimit: payload.dailyQuestionLimit as number | null,
        dailyTokenLimit: payload.dailyTokenLimit as number | null,
        updatedAt: KILL_SWITCH_WRITE_AT,
        updatedBy: DEMO_USER_ID,
      }),
    );
  }),
  mswHttp.get('/api/v1/admin/audit/events', ({ request }) => {
    recordAdminOp('GET', request, null);
    const params = new URL(request.url).searchParams;
    const instance = '/api/v1/admin/audit/events';
    const traceId = params.get('traceId');
    if (traceId !== null && !/^[0-9a-f]{32}$/.test(traceId)) {
      return adminReject(instance, 'traceId 必须是 32 位小写十六进制。');
    }
    const eventType = params.get('eventType');
    if (eventType !== null && !/^[A-Z][A-Z0-9_]{0,63}$/.test(eventType)) {
      return adminReject(instance, 'eventType 必须匹配事件类型正则。');
    }
    const limit = Number(params.get('limit') ?? '100');
    if (!Number.isInteger(limit) || limit < 1 || limit > 500) {
      return adminReject(instance, 'limit 必须是 1–500 的整数。');
    }
    const badWindow = windowProblem(instance, params.get('from'), params.get('to'));
    if (badWindow !== null) {
      return badWindow;
    }
    const userId = params.get('userId');
    const rows = [auditEventItem(1), auditEventItem(2), auditEventItem(3)].filter(
      (item) =>
        (traceId === null || item.traceId === traceId) &&
        (userId === null || item.userId === userId) &&
        (eventType === null || item.eventType === eventType),
    );
    return pageRows(rows, params.get('cursor'), instance, 2, limit);
  }),
  mswHttp.get('/api/v1/admin/observability', ({ request }) => {
    recordAdminOp('GET', request, null);
    return HttpResponse.json(envelope({ langfuseBaseUrl: adminStubState.langfuseBaseUrl }));
  }),
  mswHttp.get('/api/v1/admin/ai/usage', ({ request }) => {
    recordAdminOp('GET', request, null);
    const params = new URL(request.url).searchParams;
    const instance = '/api/v1/admin/ai/usage';
    const bucket = params.get('bucket');
    if (bucket !== null && bucket !== 'day') {
      return adminReject(instance, 'bucket 目前只接受 day。');
    }
    const topUsers = Number(params.get('topUsers') ?? '10');
    if (!Number.isInteger(topUsers) || topUsers < 1 || topUsers > 50) {
      return adminReject(instance, 'topUsers 必须是 1–50 的整数。');
    }
    const badWindow = windowProblem(instance, params.get('from'), params.get('to'));
    if (badWindow !== null) {
      return badWindow;
    }
    return HttpResponse.json(envelope(usageSummaryData()));
  }),
  mswHttp.get('/api/v1/admin/ai/model-connections/presets', ({ request }) => {
    recordAdminOp('GET', request, null);
    if (modelConnectionsStubState.presetsFail) {
      return statusProblem(500, 'COM-INTERNAL-001', '预设目录暂不可用。', '/api/v1/admin/ai/model-connections/presets');
    }
    return HttpResponse.json(envelope({ items: modelPresetItems() }));
  }),
  mswHttp.get('/api/v1/admin/ai/model-connections', ({ request }) => {
    recordAdminOp('GET', request, null);
    return HttpResponse.json(envelope({ items: modelConnectionsStubState.rows }));
  }),
  mswHttp.post('/api/v1/admin/ai/model-connections', async ({ request }) => {
    const instance = '/api/v1/admin/ai/model-connections';
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
    recordAdminOp('POST', request, body);
    const payload = body ?? {};
    const name = typeof payload.name === 'string' ? payload.name.trim() : '';
    const baseUrl = typeof payload.baseUrl === 'string' ? payload.baseUrl.trim() : '';
    const model = typeof payload.model === 'string' ? payload.model.trim() : '';
    if (!name || !baseUrl || !model) {
      return adminReject(instance, 'name、baseUrl、model 都必须出现且非空。');
    }
    if (!/^https?:\/\//.test(baseUrl)) {
      return adminReject(instance, 'baseUrl 只支持 http/https。');
    }
    if ('secret' in payload) {
      return adminReject(instance, '创建请求体不得携带 API Key，凭据走 credential 端点。');
    }
    const existing = modelConnectionsStubState.rows.find((row) => row.name === name);
    if (existing) {
      // 同名 upsert：更新配置但**不改变生效状态**，与契约一致。
      Object.assign(existing, {
        presetId: payload.presetId ?? null,
        baseUrl,
        model,
        enabled: payload.enabled ?? true,
        updatedAt: MODEL_CONNECTION_WRITE_AT,
      });
      return HttpResponse.json(envelope(existing));
    }
    const created = modelConnectionItem({
      id: `0192f3a4-9d4e-7f32-8a01-1234567890c${modelConnectionsStubState.nextId++}`,
      name,
      presetId: payload.presetId ?? null,
      baseUrl,
      model,
      enabled: payload.enabled ?? true,
      active: false,
      hasCredential: false,
      probeStatus: 'UNKNOWN',
      lastProbedAt: null,
    });
    modelConnectionsStubState.rows.push(created);
    return HttpResponse.json(envelope(created));
  }),
  mswHttp.put('/api/v1/admin/ai/model-connections/:id/credential', async ({ request, params }) => {
    const id = String(params.id);
    const instance = `/api/v1/admin/ai/model-connections/${id}/credential`;
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
    recordAdminOp('PUT', request, body);
    const row = modelConnectionsStubState.rows.find((item) => item.id === id);
    if (!row) {
      return statusProblem(404, 'COM-NOT_FOUND-001', '模型连接不存在。', instance);
    }
    const secret = typeof body?.secret === 'string' ? body.secret.trim() : '';
    if (!secret) {
      return adminReject(instance, 'secret 必须出现且非空。');
    }
    row.hasCredential = true;
    row.updatedAt = MODEL_CONNECTION_WRITE_AT;
    // 响应只回 hasCredential，绝不回显 secret。
    return HttpResponse.json(envelope(row));
  }),
  mswHttp.post('/api/v1/admin/ai/model-connections/:id/probe', ({ request, params }) => {
    const id = String(params.id);
    const instance = `/api/v1/admin/ai/model-connections/${id}/probe`;
    recordAdminOp('POST', request, null);
    const row = modelConnectionsStubState.rows.find((item) => item.id === id);
    if (!row) {
      return statusProblem(404, 'COM-NOT_FOUND-001', '模型连接不存在。', instance);
    }
    if (!row.hasCredential) {
      return HttpResponse.json(envelope({ ok: false, models: [], failureReason: 'CREDENTIAL_MISSING', detail: '这条连接还没有保存 API Key' }));
    }
    if (!modelConnectionsStubState.probeOk) {
      row.probeStatus = 'FAILED';
      return HttpResponse.json(envelope({
        ok: false,
        models: [],
        failureReason: modelConnectionsStubState.probeFailureReason,
        detail: '端点返回 HTTP 401',
      }));
    }
    row.probeStatus = 'OK';
    row.lastProbedAt = MODEL_CONNECTION_WRITE_AT;
    return HttpResponse.json(envelope({ ok: true, models: ['deepseek-chat', 'deepseek-reasoner'], failureReason: null, detail: null }));
  }),
  mswHttp.put('/api/v1/admin/ai/model-connections/:id/activation', async ({ request, params }) => {
    const id = String(params.id);
    const instance = `/api/v1/admin/ai/model-connections/${id}/activation`;
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
    recordAdminOp('PUT', request, body);
    const row = modelConnectionsStubState.rows.find((item) => item.id === id);
    if (!row) {
      return statusProblem(404, 'COM-NOT_FOUND-001', '模型连接不存在。', instance);
    }
    if (typeof body?.active !== 'boolean') {
      return adminReject(instance, 'active 必须显式出现且为布尔。');
    }
    if (body.active && row.enabled === false) {
      return adminReject(instance, '停用的连接不能设为生效。');
    }
    if (body.active) {
      for (const other of modelConnectionsStubState.rows) {
        other.active = false;
      }
    }
    row.active = body.active;
    row.updatedAt = MODEL_CONNECTION_WRITE_AT;
    return HttpResponse.json(envelope(row));
  }),
  mswHttp.delete('/api/v1/admin/ai/model-connections/:id', ({ request, params }) => {
    const id = String(params.id);
    const instance = `/api/v1/admin/ai/model-connections/${id}`;
    recordAdminOp('DELETE', request, null);
    const index = modelConnectionsStubState.rows.findIndex((item) => item.id === id);
    if (index < 0) {
      return statusProblem(404, 'COM-NOT_FOUND-001', '模型连接不存在。', instance);
    }
    modelConnectionsStubState.rows.splice(index, 1);
    return new HttpResponse(null, { status: 204 });
  }),
];

export const DEMO_SECOND_ADMIN_ID = '0192f3a4-9d4e-7f32-8a01-1234567890a3';
export const DEMO_DISABLED_USER_ID = '0192f3a4-9d4e-7f32-8a01-1234567890a4';

/**
 * 用户管理面的桩态：角色与状态都是「写进去才读得到」的事实，
 * 于是 store 那条「用写响应回显的整行就地合并」有可对照的服务端结果，而不是自说自话。
 */
export const usersStubState = {
  roleOverrides: new Map<string, UserRole[]>(),
  statusOverrides: new Map<string, UserStatus>(),
};

interface UserStubRow {
  userId: string;
  username: string;
  status: UserStatus;
  roles: UserRole[];
  createdAt: string;
  lastLoginAt: string | null;
}

/**
 * 四行按契约的 `created_at DESC` 摆放：第 1 行就是调用者自己（两条自锁护栏的靶子），
 * 第 4 行已停用且 `lastLoginAt` 为 null——契约上这个字段显式可空，界面必须能渲染「从未登录」。
 */
const USER_ROWS: UserStubRow[] = [
  {
    userId: DEMO_USER_ID,
    username: 'demo',
    status: 'ACTIVE',
    roles: ['USER', 'ADMIN'],
    createdAt: '2026-11-04T09:00:00.000Z',
    lastLoginAt: '2026-11-04T08:55:00.000Z',
  },
  {
    userId: DEMO_TARGET_USER_ID,
    username: 'zhang.wei',
    status: 'ACTIVE',
    roles: ['USER'],
    createdAt: '2026-11-03T09:00:00.000Z',
    lastLoginAt: '2026-11-03T12:00:00.000Z',
  },
  {
    userId: DEMO_SECOND_ADMIN_ID,
    username: 'li.na',
    status: 'ACTIVE',
    roles: ['USER', 'ADMIN'],
    createdAt: '2026-11-02T09:00:00.000Z',
    lastLoginAt: '2026-11-02T18:00:00.000Z',
  },
  {
    userId: DEMO_DISABLED_USER_ID,
    username: 'wang.lei',
    status: 'DISABLED',
    roles: ['USER'],
    createdAt: '2026-11-01T09:00:00.000Z',
    lastLoginAt: null,
  },
];

function mergedUserRow(row: UserStubRow): UserStubRow {
  return {
    ...row,
    roles: usersStubState.roleOverrides.get(row.userId) ?? row.roles,
    status: usersStubState.statusOverrides.get(row.userId) ?? row.status,
  };
}

export function userSummaryRow(index: number): UserStubRow {
  return mergedUserRow(USER_ROWS[index - 1]);
}

/** 桩里只有这四行：不在这个集合里的 id 就是 404，不需要再造一个「缺失名单」。 */
function userRowByUserId(userId: string): UserStubRow | undefined {
  const row = USER_ROWS.find((item) => item.userId === userId);
  return row === undefined ? undefined : mergedUserRow(row);
}

function recordUserOp(method: string, request: Request, body: unknown): void {
  const url = new URL(request.url);
  callLog.userOps.push({
    method,
    path: url.pathname,
    search: url.search,
    key: request.headers.get('Idempotency-Key'),
    body,
  });
}

/** 三条自锁护栏在契约里都归 422 `USR-VALIDATION-001` + 一个字段级 message，message 取 Java 侧原文。 */
function policyProblem(instance: string, field: string, message: string, detail: string) {
  return HttpResponse.json(
    problem({ status: 422, code: 'USR-VALIDATION-001', detail, instance, errors: [{ field, message }] }),
    { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/**
 * 用户管理面三操作（契约 2.5.0 的 `User` tag；第四个「按 id 读取」前端刻意不消费，见 D-214）。
 * 桩按契约校验入参形态：`limit` 上下限、两个枚举过滤、`additionalProperties: false`、
 * 写操作必须带 Idempotency-Key（Java 侧 `@RequestHeader` 为必填）、未知 id 一律 404；
 * 再把 `AdminUserService.rejectSelfLockout` 的三条策略原样落成 422。
 */
export const usersHandlers = [
  mswHttp.get('/api/v1/users', ({ request }) => {
    recordUserOp('GET', request, null);
    const params = new URL(request.url).searchParams;
    const instance = '/api/v1/users';
    const limit = Number(params.get('limit') ?? '20');
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
      return adminReject(instance, 'limit 必须是 1–100 的整数。');
    }
    const status = params.get('status');
    if (status !== null && status !== 'ACTIVE' && status !== 'DISABLED') {
      return adminReject(instance, 'status 只接受 ACTIVE 或 DISABLED。');
    }
    const role = params.get('role');
    if (role !== null && role !== 'USER' && role !== 'ADMIN') {
      return adminReject(instance, 'role 只接受 USER 或 ADMIN。');
    }
    const rows = USER_ROWS.map(mergedUserRow).filter(
      (item) =>
        (status === null || item.status === status) && (role === null || item.roles.includes(role as UserRole)),
    );
    return pageRows(rows, params.get('cursor'), instance, 2, limit);
  }),
  mswHttp.put('/api/v1/users/:userId/roles', async ({ request, params }) => {
    const userId = String(params.userId);
    const instance = `/api/v1/users/${userId}/roles`;
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
    recordUserOp('PUT', request, body);
    if (request.headers.get('Idempotency-Key') === null) {
      return adminReject(instance, '写操作必须携带 Idempotency-Key。');
    }
    const keys = Object.keys(body ?? {});
    if (keys.length !== 1 || keys[0] !== 'roles' || !Array.isArray(body?.roles)) {
      return adminReject(instance, '请求体只接受 roles 一个数组字段。');
    }
    const submitted = body.roles as UserRole[];
    if (submitted.some((item) => item !== 'USER' && item !== 'ADMIN')) {
      return adminReject(instance, 'roles 只能取 USER 与 ADMIN。');
    }
    const row = userRowByUserId(userId);
    if (row === undefined) {
      return statusProblem(404, 'USR-NOT_FOUND-001', '目标用户不存在。', instance);
    }
    const roles = [...new Set(submitted)];
    if (roles.length === 0) {
      return policyProblem(instance, 'roles', 'at least one role is required', '账号至少要保留一个角色。');
    }
    if (userId === DEMO_USER_ID && !roles.includes('ADMIN')) {
      return policyProblem(
        instance,
        'roles',
        'cannot remove ADMIN from the calling administrator',
        '不能移除当前调用者自身的 ADMIN 角色。',
      );
    }
    usersStubState.roleOverrides.set(userId, roles);
    return HttpResponse.json(envelope(userRowByUserId(userId)));
  }),
  mswHttp.patch('/api/v1/users/:userId/status', async ({ request, params }) => {
    const userId = String(params.userId);
    const instance = `/api/v1/users/${userId}/status`;
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
    recordUserOp('PATCH', request, body);
    if (request.headers.get('Idempotency-Key') === null) {
      return adminReject(instance, '写操作必须携带 Idempotency-Key。');
    }
    const keys = Object.keys(body ?? {});
    if (body === null || keys.length !== 1 || keys[0] !== 'status') {
      return adminReject(instance, '请求体只接受 status 一个字段。');
    }
    const status = body.status as UserStatus;
    if (status !== 'ACTIVE' && status !== 'DISABLED') {
      return adminReject(instance, 'status 只接受 ACTIVE 或 DISABLED。');
    }
    const row = userRowByUserId(userId);
    if (row === undefined) {
      return statusProblem(404, 'USR-NOT_FOUND-001', '目标用户不存在。', instance);
    }
    if (userId === DEMO_USER_ID && status === 'DISABLED') {
      return policyProblem(
        instance,
        'status',
        'cannot disable the calling administrator',
        '不能停用当前调用者自身的账号。',
      );
    }
    usersStubState.statusOverrides.set(userId, status);
    return HttpResponse.json(envelope(userRowByUserId(userId)));
  }),
];
