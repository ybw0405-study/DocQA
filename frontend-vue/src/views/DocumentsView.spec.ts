import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';
import { HttpResponse, http as mswHttp } from 'msw';
import DocumentsView from './DocumentsView.vue';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import { useDocumentsStore } from '../stores/documents';
import { useUploadsStore } from '../stores/uploads';
import { DEMO_TOKEN, callLog, documentListItem, documentStatusData, envelope, problem } from '../../tests/fixtures';
import { server } from '../../tests/server';
import type { DocumentsFilter } from '../stores/documents';

const FIRST_ID = documentListItem(1).documentId;
const FAILED_ID = documentListItem(3).documentId;

let mounted: ViewWrapper | null = null;

/** `loaded` 在 store 的 finally 里置真，成功与失败路径都等到它才算首屏稳定。 */
async function mountView() {
  const wrapper = mount(DocumentsView, { global: { stubs: { UploadPanel: true } } });
  mounted = wrapper;
  await vi.waitFor(() => expect(useDocumentsStore().loaded).toBe(true));
  return wrapper;
}

type ViewWrapper = Awaited<ReturnType<typeof mountView>>;

/** 记录每次列表请求的游标，并固定返回单页末页数据。 */
function countingList(items: unknown[]): string[] {
  const cursors: string[] = [];
  server.use(
    mswHttp.get('/api/v1/documents', ({ request }) => {
      cursors.push(new URL(request.url).searchParams.get('cursor') ?? 'first');
      return HttpResponse.json(envelope({ items, nextCursor: null, limit: 20 }));
    }),
  );
  return cursors;
}

/** 首行操作列里的按钮集合；状态相关按钮（重试/重建）只在该状态出现。 */
function rowActions(view: ViewWrapper, index = 0) {
  const cells = view.findAll('tbody tr')[index]?.findAll('td') ?? [];
  return cells[cells.length - 1]?.findAll('button') ?? [];
}

function byLabel<T extends { text(): string }>(items: T[], label: string): T | undefined {
  return items.find((item) => item.text() === label);
}

function anyButton(view: ViewWrapper) {
  return view.findAll('button');
}

describe('DocumentsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  // 视图的事件处理器不会被 await：收尾先把在途取数等完，再卸载，否则这条请求会打进下一条用例的桩里。
  afterEach(async () => {
    await vi.waitFor(() => expect(useDocumentsStore().loading).toBe(false));
    mounted?.unmount();
    mounted = null;
  });

  it('loads the first page on entry and renders it with display labels', async () => {
    const view = await mountView();
    const rows = view.findAll('tbody tr');

    expect(rows).toHaveLength(2);
    const cells = rows[0]?.findAll('td') ?? [];
    // 标题与原始文件名同行展示，两者都是纯文本插值。
    expect(cells[0]?.text()).toContain('笔记 1');
    expect(cells[0]?.text()).toContain('note-1.pdf');
    expect(cells.map((cell) => cell.text()).slice(1, 7)).toEqual([
      '1.0 KiB',
      '已可检索',
      '42',
      '考研数学',
      'v1',
      new Date(documentListItem(1).createdAt).toLocaleString('zh-CN', { hour12: false }),
    ]);
    expect(view.find('thead').text()).toContain('索引版本');
    expect(view.find('thead').text()).toContain('标签');
  });

  it('falls back to the file name and a dash when the title and tags are absent', async () => {
    const view = await mountView();
    const secondRow = view.findAll('tbody tr')[1];
    const cells = secondRow?.findAll('td') ?? [];

    expect(cells[0]?.text()).toBe('note-2.pdf');
    expect(cells[4]?.text()).toBe('—');
  });

  it('appends the next page and then stops offering more', async () => {
    const view = await mountView();

    await byLabel(anyButton(view), '加载更多')?.trigger('click');
    await vi.waitFor(() => expect(view.findAll('tbody tr')).toHaveLength(3));

    expect(view.text()).toContain('已显示全部 3 条。');
    expect(byLabel(anyButton(view), '加载更多')).toBeUndefined();
    const failed = view.findAll('tbody tr')[2];
    expect(failed?.text()).toContain('解析失败');
    expect(failed?.text()).toContain('DOC-VALIDATION-003');
  });

  it('keeps the loaded rows on screen and moves the loading copy into the button on refresh', async () => {
    const view = await mountView();
    const store = useDocumentsStore();
    const rows = view.findAll('tbody tr').length;

    // 直接翻标志位而不是点「刷新」：那条请求会在同一个 tick 里回来，断言会跟 msw 的应答速度赛跑。
    store.loading = true;
    await nextTick();

    expect(view.find('.skeleton').exists()).toBe(false);
    expect(view.findAll('tbody tr')).toHaveLength(rows);
    expect(byLabel(anyButton(view), '加载中…')).toBeDefined();
    expect(byLabel(anyButton(view), '刷新')).toBeUndefined();

    store.loading = false;
    await nextTick();
    expect(byLabel(anyButton(view), '刷新')).toBeDefined();
  });

  it('sends only status values inside the enum and maps 全部 to no filter', async () => {
    const view = await mountView();
    const setFilters = vi.spyOn(useDocumentsStore(), 'setFilters');
    const select = view.find('select');
    const expected: DocumentsFilter = { status: 'FAILED', q: '', tag: '' };

    await select.setValue('FAILED');
    // v-model 与 @change 都挂在原生 change 上，setValue 只改 DOM 值。
    await select.trigger('change');
    expect(setFilters).toHaveBeenCalledWith(expected);

    await select.setValue('');
    await select.trigger('change');
    expect(setFilters).toHaveBeenLastCalledWith({ status: null, q: '', tag: '' });
  });

  it('submits the search box and the tag filter as query conditions', async () => {
    const sent: URLSearchParams[] = [];
    server.use(
      mswHttp.get('/api/v1/documents', ({ request }) => {
        sent.push(new URL(request.url).searchParams);
        return HttpResponse.json(envelope({ items: [], nextCursor: null, limit: 20 }));
      }),
    );
    const view = await mountView();
    const search = view.find('input[type="search"]');
    const tagFilter = view.findAll('input[type="text"]')[0];

    await search.setValue('梯度下降');
    // 点号写法（`keyup.enter`）不会带上 `key`，Vue 的 `.enter` 守卫会静默丢掉事件，必须显式给 key。
    await search.trigger('keyup', { key: 'Enter' });
    await tagFilter?.setValue('考研数学');
    await tagFilter?.trigger('keyup', { key: 'Enter' });

    // 事件处理器不被 await，请求被拦截是异步的，先等三次都落地再核对查询串。
    await vi.waitFor(() => expect(sent).toHaveLength(3));
    // sent[0] 是首屏加载，两次条件提交才是断言对象。
    expect(sent[1]?.get('q')).toBe('梯度下降');
    expect(sent[1]?.has('tag')).toBe(false);
    expect(sent[2]?.get('tag')).toBe('考研数学');
    expect(sent[2]?.get('q')).toBe('梯度下降');
  });

  it('renders an untrusted file name as escaped text, never as markup', async () => {
    countingList([{ ...documentListItem(1), fileName: '<img src=x onerror=alert(1)>' }]);
    const view = await mountView();
    const cell = view.find('tbody tr td');

    expect(cell.text()).toContain('<img src=x onerror=alert(1)>');
    expect(view.find('img').exists()).toBe(false);
  });

  it('keeps the server detail and trace visible when the list cannot load', async () => {
    server.use(
      mswHttp.get('/api/v1/documents', () =>
        HttpResponse.json(
          problem({ status: 500, code: 'COM-INTERNAL-001', detail: '服务暂时不可用。', instance: '/api/v1/documents' }),
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );

    const view = await mountView();
    const alert = view.find('[role="alert"]');

    expect(view.find('table').exists()).toBe(false);
    expect(alert.text()).toContain('服务暂时不可用。');
    expect(alert.text()).toContain('trace 4bf92f3577b34da6a3ce929d0e0e4736');
    expect(view.text()).toContain('还没有文档');
  });

  it('opens the index details with the labels the contract enums map to', async () => {
    server.use(
      mswHttp.get('/api/v1/documents/:documentId/status', ({ params }) =>
        HttpResponse.json(
          envelope(
            documentStatusData(String(params.documentId), {
              vectorRows: 40,
              vectorConsistency: 'DIVERGED',
              pendingIndexVersion: 'v2',
            }),
          ),
        ),
      ),
    );
    const view = await mountView();

    await byLabel(rowActions(view), '详情')?.trigger('click');
    await vi.waitFor(() => expect(view.text()).toContain('行数待收敛'));

    expect(view.text()).toContain('42 / 40');
    expect(view.text()).toContain('v2');
    expect(byLabel(rowActions(view), '收起详情')).toBeDefined();
  });

  it('renames through the edit row and keeps the title out of markup', async () => {
    const view = await mountView();

    await byLabel(rowActions(view), '编辑')?.trigger('click');
    const titleInput = view.find('input[maxlength="120"]');
    expect(titleInput.exists()).toBe(true);

    await titleInput.setValue('<b>加粗标题</b>');
    await byLabel(anyButton(view), '保存标题')?.trigger('click');

    await vi.waitFor(() => expect(callLog.documentOps).toHaveLength(1));
    expect(callLog.documentOps[0].method).toBe('PATCH');
    expect(view.text()).toContain('<b>加粗标题</b>');
    expect(view.find('b').exists()).toBe(false);
  });

  it('replaces tags from the comma separated draft', async () => {
    const view = await mountView();

    await byLabel(rowActions(view), '编辑')?.trigger('click');
    const tagsInput = view.findAll('input[type="text"]').at(-1);
    await tagsInput?.setValue('考研数学, 错题整理 ,');

    await byLabel(anyButton(view), '保存标签')?.trigger('click');

    await vi.waitFor(() => expect(callLog.documentOps).toHaveLength(1));
    expect(callLog.documentOps[0].body).toEqual({ tags: ['考研数学', '错题整理'] });
  });

  it('requires an explicit second click before a document is deleted', async () => {
    const view = await mountView();

    await byLabel(rowActions(view), '删除')?.trigger('click');
    expect(callLog.documentOps).toHaveLength(0);
    expect(view.text()).toContain('本期不提供撤销入口');

    await byLabel(anyButton(view), '确认删除')?.trigger('click');

    await vi.waitFor(() => expect(view.findAll('tbody tr')).toHaveLength(1));
    expect(callLog.documentOps[0].method).toBe('DELETE');
    expect(callLog.documentOps[0].key).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('offers 重试解析 only on a failed row and sends it to the retry endpoint', async () => {
    countingList([documentListItem(3)]);
    const view = await mountView();
    const actions = rowActions(view);

    expect(byLabel(actions, '重试解析')).toBeDefined();
    expect(byLabel(actions, '重建索引')).toBeUndefined();

    await byLabel(actions, '重试解析')?.trigger('click');

    await vi.waitFor(() => expect(callLog.documentOps).toHaveLength(1));
    expect(callLog.documentOps[0].path).toBe(`/api/v1/documents/${FAILED_ID}/retry`);
  });

  it('disables every row write action while one write is in flight', async () => {
    server.use(
      mswHttp.delete('/api/v1/documents/:documentId', async ({ request }) => {
        callLog.documentOps.push({ method: 'DELETE', path: new URL(request.url).pathname, key: null, body: null });
        await new Promise((resolve) => setTimeout(resolve, 20));
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const view = await mountView();

    await byLabel(rowActions(view), '删除')?.trigger('click');
    await byLabel(anyButton(view), '确认删除')?.trigger('click');
    await vi.waitFor(() => expect(useDocumentsStore().busyId).toBe(FIRST_ID));

    // 串行锁只锁五个写操作；「详情」是独立 GET，在途期间仍然可点，否则操作员连唯一的服务端事实都看不到。
    expect(rowActions(view).map((button) => [button.text(), button.attributes('disabled') !== undefined])).toEqual([
      ['详情', false],
      ['编辑', true],
      ['处理中…', true],
      ['删除', true],
    ]);
    await vi.waitFor(() => expect(useDocumentsStore().busyId).toBeNull());
  });

  it('reloads the list as soon as an upload reaches INDEXED', async () => {
    const cursors = countingList([documentListItem(1)]);
    const uploads = useUploadsStore();
    const view = await mountView();

    uploads.tasks.push({
      id: 'task-1',
      fileName: 'note.pdf',
      sizeBytes: 2048,
      phase: 'done',
      progressBytes: 2048,
      partsSent: 1,
      partCount: 1,
      documentId: FIRST_ID,
      status: 'INDEXED',
      errorCode: null,
      detail: null,
    });
    await vi.waitFor(() => expect(cursors).toHaveLength(2));

    expect(cursors).toEqual(['first', 'first']);
    expect(view.findAll('tbody tr')).toHaveLength(1);
  });

  it('does not reload the list when indexedCount drops (clearFinished)', async () => {
    const cursors = countingList([documentListItem(1)]);
    const uploads = useUploadsStore();
    await mountView();
    uploads.tasks.push({
      id: 'task-1',
      fileName: 'note.pdf',
      sizeBytes: 2048,
      phase: 'done',
      progressBytes: 2048,
      partsSent: 1,
      partCount: 1,
      documentId: FIRST_ID,
      status: 'INDEXED',
      errorCode: null,
      detail: null,
    });
    await vi.waitFor(() => expect(cursors).toHaveLength(2));

    // 清理已完成项让计数下降：不应再触发整页重取（C-10）
    uploads.tasks.splice(0, uploads.tasks.length);
    await nextTick();
    await nextTick();
    expect(cursors).toHaveLength(2);
  });

  it('cancels every in-flight upload when the page is left', async () => {
    const cancelAll = vi.spyOn(useUploadsStore(), 'cancelAll');
    const view = await mountView();

    view.unmount();

    expect(cancelAll).toHaveBeenCalledTimes(1);
  });
});
