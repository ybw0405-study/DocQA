import { defineStore } from 'pinia';
import {
  askConversation,
  askConversationStream,
  createConversation,
  listConversationMessages,
  listConversations,
} from '../api/conversations';
import { listDocuments } from '../api/documents';
import { StreamUnavailableError } from '../api/errors';
import { newIdempotencyKey, type Traced } from '../api/http';
import type { DocumentListItemData } from '../types/documents';
import type {
  AskData,
  AskStreamDonePayload,
  AskStreamMetaPayload,
  ConversationListItemData,
  MessageHistoryItemData,
  MessageRole,
} from '../types/conversations';
import { DOCUMENT_IDS_MAX, QUESTION_MAX_LENGTH, TITLE_MAX_LENGTH } from '../types/conversations';
import {
  clearFailureFields,
  isStaleCursor,
  makeApplyFailure,
  PAGE_LIMIT,
  rejectLocal,
  type FailureFields as BaseFailureFields,
} from './failureFields';

const applyFailure = makeApplyFailure('问答页请求失败，请稍后重试。');

/**
 * 生成中的中止控制器。它不进 state：`AbortController` 与 `AbortSignal` 都不该被响应式代理，
 * 而需要它的只有本 store 自己（切换会话与视图卸载都要复用同一个句柄）。
 */
let streamController: AbortController | null = null;
/** 非流式回退请求的中止控制器；两条通道不会同时在途，但视图卸载时都必须能断。 */
let syncController: AbortController | null = null;
/**
 * 回答归属代号：发起新一轮提问、停止生成、离开问答页都会递增它。
 * 在途的同步回答返回时若代号已变（或会话已切），说明它属于上一会话，整条丢弃，
 * 绝不把答案追加进当前全局 `messages`。
 */
let requestSeq = 0;

function abortActiveStream(): void {
  streamController?.abort();
}

/**
 * 会话内的一行消息。`governance` 只存在于本次提问直接得到的回答上：
 * 历史接口的 `MessageHistoryItemData` 不返回治理字段，刷新后这些行只能给出 token 与 traceId。
 */
export interface MessageRow {
  key: string;
  role: MessageRole;
  content: string;
  tokenCount: number | null;
  traceId: string | null;
  createdAt: string | null;
  governance: AskData | null;
}

interface FailureFields extends BaseFailureFields {
  retryAfterSeconds: number | null;
}

export interface ConversationsState extends FailureFields {
  items: ConversationListItemData[];
  nextCursor: string | null;
  listLoading: boolean;
  listLoaded: boolean;
  /** 列表取数代号：写后刷新/过滤切换递增它，在途翻页的过期响应一律丢弃。 */
  listSeq: number;

  currentId: string | null;
  messages: MessageRow[];
  historyCursor: string | null;
  historyLoading: boolean;
  historyLoaded: boolean;
  /** 历史取数代号：每次按会话取最新一页都递增，快速 A→B 切换时在途的 A 响应整体丢弃。 */
  historySeq: number;

  newTitle: string;
  creating: boolean;
  createKey: string | null;

  question: string;
  submitting: boolean;
  /** 本轮提问的幂等键：失败时保留供手动重试复用，成功或改问题时清空。 */
  pendingKey: string | null;
  /** 本次提问由事件流生成；`submitting` 覆盖两条通道，`streaming` 只用来区分渲染与可中断性。 */
  streaming: boolean;
  /** 生成中的助手行键，视图据此只在最后一行挂光标；done 之后随分支结束一起清空。 */
  streamKey: string | null;

  selected: string[];
  pickerItems: DocumentListItemData[];
  pickerCursor: string | null;
  pickerLoading: boolean;
  pickerLoaded: boolean;
}

function toRow(item: MessageHistoryItemData): MessageRow {
  return {
    key: item.messageId,
    role: item.role,
    content: item.content,
    tokenCount: item.tokenCount,
    traceId: item.traceId,
    createdAt: item.createdAt,
    governance: null,
  };
}

/** 历史接口按 `created_at DESC` 返回，展示顺序是时间升序，因此每页都要反序后再拼接。 */
function toAscending(items: MessageHistoryItemData[]): MessageRow[] {
  return [...items].reverse().map(toRow);
}

export const useConversationsStore = defineStore('conversations', {
  state: (): ConversationsState => ({
    items: [],
    nextCursor: null,
    listLoading: false,
    listLoaded: false,
    listSeq: 0,

    currentId: null,
    messages: [],
    historyCursor: null,
    historyLoading: false,
    historyLoaded: false,
    historySeq: 0,

    newTitle: '',
    creating: false,
    createKey: null,

    question: '',
    submitting: false,
    pendingKey: null,
    streaming: false,
    streamKey: null,

    selected: [],
    pickerItems: [],
    pickerCursor: null,
    pickerLoading: false,
    pickerLoaded: false,

    detail: null,
    code: null,
    traceId: null,
    retryAfterSeconds: null,
  }),

  getters: {
    canLoadListMore: (state): boolean => !state.listLoading && state.nextCursor !== null,
    canLoadHistoryMore: (state): boolean => !state.historyLoading && state.historyCursor !== null,
    canLoadPickerMore: (state): boolean => !state.pickerLoading && state.pickerCursor !== null,
    /** 流式生成可以被打断着离开；非流式请求一旦发出就要等它的结果，不允许中途切走。 */
    canSwitchConversation: (state): boolean => !state.submitting || state.streaming,

    /** 勾选与当前可检索列表的交集，去重后即为 `documentIds`。 */
    indexedSelection: (state): string[] => {
      const indexed = new Set(
        state.pickerItems.filter((doc) => doc.status === 'INDEXED').map((doc) => doc.documentId),
      );
      return [...new Set(state.selected)].filter((id) => indexed.has(id));
    },

    /** 引用回落展示名用的文档名表；`Citation.fileName` 缺失时使用。 */
    fileNames: (state): Record<string, string> =>
      Object.fromEntries(state.pickerItems.map((doc) => [doc.documentId, doc.fileName])),
  },

  actions: {
    clearFailure(): void {
      clearFailureFields(this);
    },

    async loadListFirst(): Promise<void> {
      if (this.listLoading) {
        return;
      }
      await this.fetchFirstListPage();
    },

    /**
     * 取列表第一页，不受 `listLoading` 守卫约束：提问落地、新建会话后的写后刷新必须真正取回
     * 服务端最新结果，被一次在途旧读挤掉就等于白刷；过期响应按代号丢弃。
     */
    async fetchFirstListPage(): Promise<void> {
      const seq = ++this.listSeq;
      this.listLoading = true;
      try {
        const data = await listConversations({ limit: PAGE_LIMIT });
        if (seq !== this.listSeq) {
          return;
        }
        this.items = data.items;
        this.nextCursor = data.nextCursor;
        this.clearFailure();
      } catch (error) {
        if (seq !== this.listSeq) {
          return;
        }
        applyFailure(this, error);
      } finally {
        if (seq === this.listSeq) {
          this.listLoading = false;
          this.listLoaded = true;
        }
      }
    },

    async loadListMore(): Promise<void> {
      const cursor = this.nextCursor;
      if (!this.canLoadListMore || cursor === null) {
        return;
      }
      const seq = this.listSeq;
      this.listLoading = true;
      try {
        const data = await listConversations({ cursor, limit: PAGE_LIMIT });
        if (seq !== this.listSeq) {
          return;
        }
        this.items = [...this.items, ...data.items];
        this.nextCursor = data.nextCursor;
        this.clearFailure();
      } catch (error) {
        if (seq !== this.listSeq) {
          return;
        }
        if (isStaleCursor(error)) {
          this.nextCursor = null;
          this.listLoading = false;
          await this.fetchFirstListPage();
          this.detail = '会话翻页游标已失效，列表已回到第一页。';
          return;
        }
        applyFailure(this, error);
      } finally {
        if (seq === this.listSeq) {
          this.listLoading = false;
        }
      }
    },

    updateTitle(value: string): void {
      if (value === this.newTitle) {
        return;
      }
      this.newTitle = value;
      // 契约：同一幂等键对应不同标题会 409 AGT-CONFLICT-002，改了标题就必须换新键。
      this.createKey = null;
    },

    /** 显式新建会话（D-5 方案 A）：会话 ID 由服务端签发，前端不铸造任何 UUID。 */
    async provisionConversation(): Promise<void> {
      if (this.creating) {
        return;
      }
      const title = this.newTitle.trim();
      if (title.length > TITLE_MAX_LENGTH) {
        rejectLocal(this, `会话标题不能超过 ${TITLE_MAX_LENGTH} 字。`);
        return;
      }
      const key = this.createKey ?? newIdempotencyKey();
      this.creating = true;
      this.createKey = key;
      try {
        const data = await createConversation(title.length > 0 ? { title } : {}, key);
        this.createKey = null;
        this.newTitle = '';
        await this.openConversation(data.conversationId);
        await this.fetchFirstListPage();
      } catch (error) {
        applyFailure(this, error);
      } finally {
        this.creating = false;
      }
    },

    async openConversation(conversationId: string): Promise<void> {
      if (this.submitting && !this.streaming) {
        return;
      }
      // 生成中点会话等于放弃本次生成：先断开事件流，本地半行随之丢弃，再按新会话取历史。
      abortActiveStream();
      if (this.currentId !== conversationId) {
        this.currentId = conversationId;
        this.messages = [];
        this.historyCursor = null;
        this.historyLoaded = false;
        // 幂等键的作用域是 conversationId + key，换了会话就不该再复用上一次尝试的键。
        this.pendingKey = null;
      }
      await this.fetchFirstHistoryPage();
    },

    async loadHistoryFirst(): Promise<void> {
      if (this.historyLoading) {
        return;
      }
      await this.fetchFirstHistoryPage();
    },

    /**
     * 取当前会话最新一页，不受 `historyLoading` 守卫约束：快速 A→B 切换时，在途的 A 请求
     * 不能挡住 B 的历史。每个在途请求捕获代号，返回后代号不符或 `currentId` 已变就整体丢弃，
     * 这样 A 的旧页既不会覆盖 B 的消息，也不会把 A 的游标写进 B。
     */
    async fetchFirstHistoryPage(): Promise<void> {
      const conversationId = this.currentId;
      if (conversationId === null) {
        return;
      }
      const seq = ++this.historySeq;
      this.historyLoading = true;
      try {
        const data = await listConversationMessages(conversationId, { limit: PAGE_LIMIT });
        if (seq !== this.historySeq || conversationId !== this.currentId) {
          return;
        }
        this.messages = toAscending(data.items);
        this.historyCursor = data.nextCursor;
        this.clearFailure();
      } catch (error) {
        if (seq !== this.historySeq || conversationId !== this.currentId) {
          return;
        }
        applyFailure(this, error);
      } finally {
        if (seq === this.historySeq) {
          this.historyLoading = false;
          this.historyLoaded = true;
        }
      }
    },

    async loadHistoryMore(): Promise<void> {
      const conversationId = this.currentId;
      const cursor = this.historyCursor;
      if (conversationId === null || !this.canLoadHistoryMore || cursor === null) {
        return;
      }
      const seq = this.historySeq;
      this.historyLoading = true;
      try {
        const data = await listConversationMessages(conversationId, { cursor, limit: PAGE_LIMIT });
        if (seq !== this.historySeq || conversationId !== this.currentId) {
          return;
        }
        this.messages = [...toAscending(data.items), ...this.messages];
        this.historyCursor = data.nextCursor;
        this.clearFailure();
      } catch (error) {
        if (seq !== this.historySeq || conversationId !== this.currentId) {
          return;
        }
        if (isStaleCursor(error)) {
          this.historyCursor = null;
          this.historyLoading = false;
          await this.fetchFirstHistoryPage();
          this.detail = '消息翻页游标已失效，已回到最新一页。';
          return;
        }
        applyFailure(this, error);
      } finally {
        if (seq === this.historySeq) {
          this.historyLoading = false;
        }
      }
    },

    async loadPickerFirst(): Promise<void> {
      if (this.pickerLoading) {
        return;
      }
      this.pickerLoading = true;
      try {
        const data = await listDocuments({ status: 'INDEXED', limit: PAGE_LIMIT });
        this.pickerItems = data.items;
        this.pickerCursor = data.nextCursor;
        this.clearFailure();
      } catch (error) {
        applyFailure(this, error);
      } finally {
        this.pickerLoading = false;
        this.pickerLoaded = true;
      }
    },

    async loadPickerMore(): Promise<void> {
      const cursor = this.pickerCursor;
      if (!this.canLoadPickerMore || cursor === null) {
        return;
      }
      this.pickerLoading = true;
      try {
        const data = await listDocuments({ status: 'INDEXED', cursor, limit: PAGE_LIMIT });
        this.pickerItems = [...this.pickerItems, ...data.items];
        this.pickerCursor = data.nextCursor;
        this.clearFailure();
      } catch (error) {
        if (isStaleCursor(error)) {
          this.pickerCursor = null;
          this.pickerLoading = false;
          await this.loadPickerFirst();
          this.detail = '可选文档翻页游标已失效，已回到第一页。';
          return;
        }
        applyFailure(this, error);
      } finally {
        this.pickerLoading = false;
      }
    },

    toggleDocument(documentId: string): void {
      const index = this.selected.indexOf(documentId);
      if (index >= 0) {
        this.selected.splice(index, 1);
        return;
      }
      if (this.selected.length >= DOCUMENT_IDS_MAX) {
        rejectLocal(this, `一次最多勾选 ${DOCUMENT_IDS_MAX} 份文档。`);
        return;
      }
      this.selected.push(documentId);
    },

    updateQuestion(value: string): void {
      if (value === this.question) {
        return;
      }
      this.question = value;
      // 契约：同一幂等键对应不同问题一律 409，改了问题说明这是一次新尝试。
      this.pendingKey = null;
    },

    /**
     * 「停止生成」/离开问答页：断开在途事件流与非流式回退请求，并让在途回答整体失效。
     * 问题文本与幂等键保留，由用户决定是否再问。
     */
    abortStream(): void {
      requestSeq += 1;
      abortActiveStream();
      syncController?.abort();
    },

    /**
     * 流式优先；服务端没用事件流应答时以**同一个幂等键**回退非流式 `/ask`。
     * 回退不是重试：同一次提问在两个端点上仍共用一个键，服务端不会因此看到两次问题。
     * 返回 `false` 表示本次生成被主动断开、没有任何行落地。
     */
    async deliver(
      conversationId: string,
      question: string,
      documentIds: string[],
      key: string,
    ): Promise<boolean> {
      try {
        return await this.runStream(conversationId, question, documentIds, key);
      } catch (error) {
        if (error instanceof StreamUnavailableError) {
          return await this.runSync(conversationId, question, documentIds, key);
        }
        throw error;
      }
    },

    /**
     * 帧序即状态机：`meta` 落两行（助手行先空），`delta` 只拼接文本，
     * `done` 用服务端字段整体替换——到达即渲染，不做本地补间的假动画（D-176）。
     * `error` 帧与首字节前的 JSON 拒绝在 api 层已是同一个 `ApiError`，这里只有一个失败分支。
     */
    async runStream(
      conversationId: string,
      question: string,
      documentIds: string[],
      key: string,
    ): Promise<boolean> {
      const controller = new AbortController();
      streamController = controller;
      this.streaming = true;
      const userRowKey = `local:${key}`;
      let assistantRowKey = '';
      let finished = false;
      try {
        const events = askConversationStream(
          conversationId,
          { question, documentIds },
          key,
          controller.signal,
        );
        for await (const event of events) {
          if (event.kind === 'meta') {
            assistantRowKey = event.payload.messageId;
            this.streamKey = assistantRowKey;
            this.messages = [...this.messages, ...toStreamRows(question, userRowKey, event.payload)];
            continue;
          }
          if (event.kind === 'delta') {
            const text = event.payload.text;
            this.messages = this.messages.map((row) =>
              row.key === assistantRowKey ? { ...row, content: row.content + text } : row,
            );
            continue;
          }
          const done = event.payload;
          const replaced = toDoneRow(done);
          this.messages = this.messages.map((row) => (row.key === assistantRowKey ? replaced : row));
          // done 才是字段真源：行键也按它重定，meta 与 done 不一致时同样收敛到服务端形态。
          assistantRowKey = done.messageId;
          this.streamKey = assistantRowKey;
          finished = true;
        }
        return finished;
      } catch (error) {
        // 主动断开不是失败：不写 detail，回到空闲态让用户重新提问。
        if (controller.signal.aborted) {
          return false;
        }
        throw error;
      } finally {
        if (!finished) {
          this.messages = this.messages.filter(
            (row) => row.key !== userRowKey && row.key !== assistantRowKey,
          );
        }
        this.streaming = false;
        this.streamKey = null;
        streamController = null;
      }
    },

    /**
     * 非流式回退同样要可中断、可归属校验：早期实现既不带 signal，又把结果直接追加到全局
     * `messages`，视图卸载或切会话后在途的答案会落到别处的列表里。
     */
    async runSync(
      conversationId: string,
      question: string,
      documentIds: string[],
      key: string,
    ): Promise<boolean> {
      const controller = new AbortController();
      syncController = controller;
      const seq = requestSeq;
      try {
        const result = await askConversation(
          conversationId,
          { question, documentIds },
          key,
          controller.signal,
        );
        // 归属校验：停止生成 / 离开问答页 / 切换会话都会改变代号或 currentId，
        // 此时答案属于上一会话，整条丢弃而不是写进当前 messages。
        if (seq !== requestSeq || controller.signal.aborted || this.currentId !== conversationId) {
          return false;
        }
        this.messages = [...this.messages, ...toLocalRows(question, key, result)];
        return true;
      } catch (error) {
        // 主动断开（卸载/切会话导致的 abort）不是失败：不写 detail，回到空闲态。
        if (controller.signal.aborted) {
          return false;
        }
        throw error;
      } finally {
        if (syncController === controller) {
          syncController = null;
        }
      }
    },

    /**
     * 失败后 `pendingKey` 保留，用户点「重试」复用同一个键：
     * 服务端对「同键同问题」会返回原结果或仍在执行，自动重试反而会把可恢复态变成持续 409。
     */
    async submitQuestion(): Promise<void> {
      if (this.submitting) {
        return;
      }
      const question = this.question.trim();
      if (this.currentId === null) {
        rejectLocal(this, '请先新建或选择一个会话。');
        return;
      }
      if (question.length === 0) {
        rejectLocal(this, '请输入问题后再提问。');
        return;
      }
      if (question.length > QUESTION_MAX_LENGTH) {
        rejectLocal(this, `问题不能超过 ${QUESTION_MAX_LENGTH} 字。`);
        return;
      }
      const requested = [...new Set(this.selected)];
      if (requested.length === 0) {
        rejectLocal(this, '请至少勾选一份已可检索的文档。');
        return;
      }
      if (requested.length > DOCUMENT_IDS_MAX) {
        rejectLocal(this, `一次最多勾选 ${DOCUMENT_IDS_MAX} 份文档。`);
        return;
      }
      const documentIds = this.indexedSelection;
      if (documentIds.length !== requested.length) {
        rejectLocal(this, '有文档已不可检索或不在当前列表里，请重新勾选。');
        return;
      }

      const conversationId = this.currentId;
      const key = this.pendingKey ?? newIdempotencyKey();
      this.pendingKey = key;
      this.submitting = true;
      this.clearFailure();
      try {
        if (!(await this.deliver(conversationId, question, documentIds, key))) {
          // 被主动断开：没有任何行落地，也就没有任何东西需要清空。
          return;
        }
        this.pendingKey = null;
        this.question = '';
        // 8A-2 边界：last_message_at 变了，列表只可能漏读不会重复，回到第一页最诚实。
        await this.fetchFirstListPage();
      } catch (error) {
        applyFailure(this, error);
      } finally {
        this.submitting = false;
      }
    },
  },
});

/**
 * 本地追加的两行：用户行的 `key` 用幂等键（服务端不返回用户消息 ID），
 * 助手行用 `messageId` 且携带治理字段；两行共用本次请求的 traceId。
 * 用户行是原文回显且没有落库时间，刷新后由服务端脱敏文本与真实时间替换。
 */
function toLocalRows(question: string, key: string, result: Traced<AskData>): MessageRow[] {
  return [
    {
      key: `local:${key}`,
      role: 'user',
      content: question,
      tokenCount: null,
      traceId: result.traceId,
      createdAt: null,
      governance: null,
    },
    {
      key: result.data.messageId,
      role: 'assistant',
      content: result.data.answer,
      tokenCount: result.data.tokenUsed,
      traceId: result.traceId,
      createdAt: result.data.createdAt,
      governance: result.data,
    },
  ];
}

/**
 * 流式的两行在 `meta` 帧落地：助手行先以空文本出现，行键与 traceId 从流首起就与服务端一致，
 * 因此中途断开只会丢掉本地半行，不会留下一条对不上账的记录。
 */
function toStreamRows(question: string, userRowKey: string, meta: AskStreamMetaPayload): MessageRow[] {
  return [
    {
      key: userRowKey,
      role: 'user',
      content: question,
      tokenCount: null,
      traceId: meta.traceId,
      createdAt: null,
      governance: null,
    },
    {
      key: meta.messageId,
      role: 'assistant',
      content: '',
      tokenCount: null,
      traceId: meta.traceId,
      createdAt: null,
      governance: null,
    },
  ];
}

/**
 * `done` 帧是字段真源：它比 `AskData` 多一个 `traceId`，而该键已由行字段承载、不参与徽章渲染。
 * 治理徽章与引用区到此才出现，避免把尚未定稿的拼接态渲染成「已有治理结论」。
 */
function toDoneRow(payload: AskStreamDonePayload): MessageRow {
  return {
    key: payload.messageId,
    role: 'assistant',
    content: payload.answer,
    tokenCount: payload.tokenUsed,
    traceId: payload.traceId,
    createdAt: payload.createdAt,
    governance: payload,
  };
}
