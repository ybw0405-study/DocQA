<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue';
import CitationList from '../components/CitationList.vue';
import GovernanceBadge from '../components/GovernanceBadge.vue';
import AppAlert from '../components/ui/AppAlert.vue';
import BaseButton from '../components/ui/BaseButton.vue';
import BaseCard from '../components/ui/BaseCard.vue';
import BaseField from '../components/ui/BaseField.vue';
import EmptyState from '../components/ui/EmptyState.vue';
import SkeletonBlock from '../components/ui/SkeletonBlock.vue';
import { useRetryWindow } from '../composables/useRetryWindow';
import { useConversationsStore } from '../stores/conversations';
import { DOCUMENT_IDS_MAX, QUESTION_MAX_LENGTH, TITLE_MAX_LENGTH } from '../types/conversations';
import { EMPTY_STATE_TEXT, ROLE_LABELS, formatDateTime } from '../utils/display';

const conversations = useConversationsStore();

/** 限流等待秒数只活在视图层：store 保存的是服务端的 `Retry-After`，本地时钟不进 store。 */
const { secondsLeft, waiting } = useRetryWindow(() => conversations.retryAfterSeconds);

const submitLabel = computed(() => {
  if (conversations.streaming) {
    return '生成中…';
  }
  if (conversations.submitting) {
    return '提问中…';
  }
  return conversations.pendingKey === null ? '提问' : '重试同一问题';
});

function onTitle(event: Event): void {
  conversations.updateTitle((event.target as HTMLInputElement).value);
}

function onQuestion(event: Event): void {
  conversations.updateQuestion((event.target as HTMLTextAreaElement).value);
}

onMounted(() => {
  void conversations.loadListFirst();
  void conversations.loadPickerFirst();
});

// 离开页面即断开在途请求：abortStream() 现在同时取消事件流与非流式回退，并让在途回答
// 的归属代号失效——否则同步答案会在返回时追加进已经不属于它的全局 messages。
onBeforeUnmount(() => {
  conversations.abortStream();
});
</script>

<template>
  <div class="ink-reading">
  <header class="ink-section-head ink-reveal">
    <span class="ink-section-head__no">贰</span>
    <h2>阅读室</h2>
    <p class="ink-section-head__aside">一问一答都留出处；生成中的行不写成已落库的记录。</p>
  </header>

  <BaseCard
    title="问答"
    :level="1"
    class="ink-reveal"
  >
    <div class="ask__new">
      <BaseField label="新会话标题（可留空）">
        <input
          data-testid="title"
          :value="conversations.newTitle"
          :maxlength="TITLE_MAX_LENGTH"
          :disabled="conversations.creating"
          @input="onTitle"
        >
      </BaseField>
      <BaseButton
        :disabled="conversations.creating"
        @click="conversations.provisionConversation()"
      >
        {{ conversations.creating ? '创建中…' : '新建会话' }}
      </BaseButton>
    </div>

    <SkeletonBlock
      v-if="conversations.listLoading && !conversations.listLoaded"
      label="会话列表加载中"
    />
    <ul class="ask__sessions">
      <li
        v-for="item in conversations.items"
        :key="item.conversationId"
      >
        <BaseButton
          variant="link"
          :disabled="!conversations.canSwitchConversation"
          @click="conversations.openConversation(item.conversationId)"
        >
          {{ item.title }}
        </BaseButton>
        <span
          v-if="item.conversationId === conversations.currentId"
          class="ask__current"
        >当前</span>
        <span class="muted">{{ item.lastMessageAt === null ? '尚未发言' : formatDateTime(item.lastMessageAt) }}</span>
      </li>
    </ul>
    <EmptyState
      v-if="conversations.listLoaded && conversations.items.length === 0"
      :text="EMPTY_STATE_TEXT.askNoSessions"
    />
    <BaseButton
      v-if="conversations.canLoadListMore"
      @click="conversations.loadListMore()"
    >
      加载更多会话
    </BaseButton>
  </BaseCard>

  <div class="ask__stream">
    <BaseCard title="对话记录">
      <BaseButton
        v-if="conversations.canLoadHistoryMore"
        @click="conversations.loadHistoryMore()"
      >
        加载更早的消息
      </BaseButton>

      <ol class="ask__messages">
        <li
          v-for="row in conversations.messages"
          :key="row.key"
          class="ask__message"
        >
          <p class="ask__message-head">
            <span class="ask__role">{{ ROLE_LABELS[row.role] }}</span>
            <span class="muted">{{ formatDateTime(row.createdAt) }}</span>
          </p>
          <!-- 消息正文按 pii-v2 脱敏后仍是不可信文本，只做文本插值。 -->
          <p class="ask__content">
            {{ row.content }}<span
              v-if="row.key === conversations.streamKey"
              class="ask__cursor"
              data-testid="stream-cursor"
              aria-hidden="true"
            />
          </p>
          <!-- 生成中的行还没有治理结论：此时按「历史消息」渲染会把尚未定稿的半行说成已落库的记录。 -->
          <GovernanceBadge
            v-if="row.role === 'assistant' && row.key !== conversations.streamKey"
            :governance="row.governance"
            :token-count="row.tokenCount"
            :trace-id="row.traceId"
          />
          <CitationList
            v-if="row.governance !== null"
            :citations="row.governance.citations"
            :known-file-names="conversations.fileNames"
          />
        </li>
      </ol>
      <div
        v-if="conversations.pendingApproval"
        class="ask__approval"
        data-testid="approval-card"
      >
        <p>
          高风险工具调用等待审批：
          <span class="mono">{{ conversations.pendingApproval.approval.tool }}</span>
        </p>
        <p class="muted mono">{{ conversations.pendingApproval.approval.input }}</p>
        <div class="ask__approval-actions">
          <BaseButton
            data-testid="approval-approve"
            :disabled="conversations.submitting"
            @click="conversations.resumeApproval(true)"
          >
            批准并继续
          </BaseButton>
          <BaseButton
            data-testid="approval-deny"
            :disabled="conversations.submitting"
            @click="conversations.resumeApproval(false)"
          >
            拒绝
          </BaseButton>
        </div>
      </div>
      <EmptyState
        v-if="conversations.currentId === null"
        :text="EMPTY_STATE_TEXT.askNoCurrent"
      />
      <EmptyState
        v-else-if="conversations.historyLoaded && conversations.messages.length === 0"
        :text="EMPTY_STATE_TEXT.askNoMessages"
      />
    </BaseCard>

    <BaseCard title="提问">
      <BaseField label="问题">
        <textarea
          data-testid="question"
          :value="conversations.question"
          :maxlength="QUESTION_MAX_LENGTH"
          rows="4"
          :disabled="conversations.submitting"
          @input="onQuestion"
        />
      </BaseField>

      <div class="ask__docs">
        <p class="muted">
          已选 {{ conversations.selected.length }}/{{ DOCUMENT_IDS_MAX }}，检索范围只来自已可检索的文档。
        </p>
        <ul>
          <li
            v-for="doc in conversations.pickerItems"
            :key="doc.documentId"
          >
            <label>
              <input
                type="checkbox"
                :checked="conversations.selected.includes(doc.documentId)"
                :disabled="conversations.submitting"
                @change="conversations.toggleDocument(doc.documentId)"
              >
              {{ doc.fileName }}
            </label>
          </li>
        </ul>
        <EmptyState
          v-if="conversations.pickerLoaded && conversations.pickerItems.length === 0"
          :text="EMPTY_STATE_TEXT.askNoIndexedDocs"
        />
        <BaseButton
          v-if="conversations.canLoadPickerMore"
          @click="conversations.loadPickerMore()"
        >
          加载更多文档
        </BaseButton>
      </div>

      <BaseButton
        class="ask__submit"
        data-testid="submit"
        :disabled="conversations.submitting || waiting"
        @click="conversations.submitQuestion()"
      >
        {{ submitLabel }}
      </BaseButton>
      <!-- 提问按钮在生成期间禁用，断开只能由这个入口发起；切换会话同样会断开。 -->
      <BaseButton
        v-if="conversations.streaming"
        data-testid="stop"
        @click="conversations.abortStream()"
      >
        停止生成
      </BaseButton>
      <p
        v-if="waiting"
        class="muted"
      >
        限流等待 {{ secondsLeft }} 秒。
      </p>

      <AppAlert
        :detail="conversations.detail"
        :code="conversations.code"
        :trace-id="conversations.traceId"
      />
    </BaseCard>
  </div>
  </div>
</template>
