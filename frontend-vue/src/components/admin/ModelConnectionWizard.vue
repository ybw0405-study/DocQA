<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import AppAlert from '../ui/AppAlert.vue';
import BaseButton from '../ui/BaseButton.vue';
import BaseField from '../ui/BaseField.vue';
import { useAdminModelConnectionsStore } from '../../stores/adminModelConnections';
import type { ModelPreset } from '../../types/modelConnections';
import { MODEL_PRESET_GROUPS, MODEL_PRESET_GROUP_LABELS } from '../../types/modelConnections';
import { hostOf } from '../../utils/modelConnection';
import {
  MODEL_CONNECTION_BASE_URL_MAX_LENGTH,
  MODEL_CONNECTION_MODEL_MAX_LENGTH,
  MODEL_CONNECTION_NAME_MAX_LENGTH,
  MODEL_CONNECTION_SECRET_MAX_LENGTH,
} from '../../types/modelConnections';

/**
 * 模型连接向导：先选厂商预设（或自定义端点），再填名称/端点/模型名/API Key，测试并连接。
 *
 * 做成内联面板而不是模态框，与 UploadPanel / ScheduleImportPanel 同一形态：
 * 不引入焦点陷阱与滚动锁，键盘顺序天然正确。
 *
 * 凭据纪律：明文 Key 只存本组件的局部 ref，提交后立即清空；不写进 store、不进任何文案。
 * 预设目录由服务端下发，本组件不硬编码任何端点地址。
 */

const props = defineProps<{
  presets: ModelPreset[] | null;
  presetsLoading: boolean;
}>();

const emit = defineEmits<{
  (e: 'connected'): void;
  (e: 'cancel'): void;
}>();

const store = useAdminModelConnectionsStore();

const step = ref<'pick' | 'form'>('pick');
const query = ref('');
const selected = ref<ModelPreset | null>(null);

const name = ref('');
const baseUrl = ref('');
const model = ref('');
const secret = ref('');
const showSecret = ref(false);
const noKeyAllowed = ref(false);
const allowWithoutProbe = ref(false);
const localError = ref<string | null>(null);

const filtered = computed(() => {
  const list = props.presets ?? [];
  const q = query.value.trim().toLowerCase();
  if (!q) return list;
  return list.filter((p) =>
    [p.label, p.vendor, p.model, p.baseUrl, p.note, ...p.models].join(' ').toLowerCase().includes(q),
  );
});

const grouped = computed(() =>
  MODEL_PRESET_GROUPS.map((group) => ({
    id: group,
    label: MODEL_PRESET_GROUP_LABELS[group],
    items: filtered.value.filter((p) => p.group === group),
  })).filter((section) => section.items.length > 0),
);

const host = computed(() => hostOf(baseUrl.value));

/** 预设切换时重填表单；已填的 Key 一律清空，避免把上一家的 Key 发给下一家。 */
watch(selected, (preset) => {
  secret.value = '';
  showSecret.value = false;
  localError.value = null;
  if (preset) {
    name.value = `${preset.label} 连接`;
    baseUrl.value = preset.baseUrl;
    model.value = preset.model;
    noKeyAllowed.value = preset.noKey;
  } else {
    name.value = '';
    baseUrl.value = '';
    model.value = '';
    noKeyAllowed.value = false;
  }
});

function choose(preset: ModelPreset | null): void {
  selected.value = preset;
  step.value = 'form';
}

function back(): void {
  step.value = 'pick';
  secret.value = '';
  localError.value = null;
}

function cancel(): void {
  secret.value = '';
  emit('cancel');
}

/** 出网前先把明显的填错拦下来，省一次真实外呼。 */
function validate(): string | null {
  if (!name.value.trim()) return '请填写连接名称';
  if (name.value.trim().length > MODEL_CONNECTION_NAME_MAX_LENGTH) return `连接名称不能超过 ${MODEL_CONNECTION_NAME_MAX_LENGTH} 字`;
  if (!model.value.trim()) return '请填写模型名';
  if (model.value.trim().length > MODEL_CONNECTION_MODEL_MAX_LENGTH) return `模型名不能超过 ${MODEL_CONNECTION_MODEL_MAX_LENGTH} 字`;
  if (baseUrl.value.trim().length > MODEL_CONNECTION_BASE_URL_MAX_LENGTH) return `Base URL 不能超过 ${MODEL_CONNECTION_BASE_URL_MAX_LENGTH} 字`;
  let parsed: URL;
  try {
    parsed = new URL(baseUrl.value.trim());
  } catch {
    return 'Base URL 不是合法的 URL';
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return 'Base URL 只支持 http/https';
  if (!parsed.hostname) return 'Base URL 缺少主机名';
  if (parsed.username || parsed.password) return 'Base URL 不得内嵌账号口令';
  if (!secret.value.trim() && !noKeyAllowed.value) return '请填写 API Key';
  if (secret.value.trim().length > MODEL_CONNECTION_SECRET_MAX_LENGTH) return `API Key 不能超过 ${MODEL_CONNECTION_SECRET_MAX_LENGTH} 字`;
  return null;
}

async function submit(): Promise<void> {
  localError.value = null;
  const problem = validate();
  if (problem) {
    localError.value = problem;
    return;
  }
  const result = await store.connect({
    name: name.value.trim(),
    presetId: selected.value?.id ?? null,
    baseUrl: baseUrl.value.trim(),
    model: model.value.trim(),
    secret: secret.value,
    noKeyAllowed: noKeyAllowed.value,
    allowWithoutProbe: allowWithoutProbe.value,
  });
  // 无论成败都立刻丢弃明文：它只在这一次请求里有意义。
  secret.value = '';
  showSecret.value = false;
  if (result.ok) {
    emit('connected');
    return;
  }
  localError.value = result.message ?? '建立连接失败，请稍后重试。';
}
</script>

<template>
  <div class="model-wizard">
    <template v-if="step === 'pick'">
      <BaseField label="搜索厂商或模型">
        <input
          v-model="query"
          type="search"
          placeholder="例如 DeepSeek、千问、glm、ollama"
          autocomplete="off"
        >
      </BaseField>

      <p
        v-if="props.presetsLoading"
        class="muted"
      >
        正在读取厂商预设…
      </p>
      <AppAlert
        v-else-if="props.presets === null"
        detail="厂商预设读取失败，可以直接使用自定义端点。"
      />

      <section
        v-for="section in grouped"
        :key="section.id"
        class="model-wizard__group"
      >
        <h3>{{ section.label }}</h3>
        <ul class="model-wizard__list">
          <li
            v-for="preset in section.items"
            :key="preset.id"
          >
            <BaseButton
              class="model-wizard__item"
              @click="choose(preset)"
            >
              <span
                class="model-wizard__mark"
                aria-hidden="true"
              >{{ preset.mark }}</span>
              <span class="model-wizard__body">
                <span class="model-wizard__label">
                  {{ preset.label }}
                  <span
                    v-if="preset.tag"
                    class="governance__chip"
                  >{{ preset.tag }}</span>
                </span>
                <span class="muted">{{ preset.vendor }} · {{ preset.model }}</span>
                <span class="muted">{{ preset.note }}</span>
              </span>
            </BaseButton>
          </li>
        </ul>
      </section>

      <div class="admin__toolbar">
        <BaseButton @click="choose(null)">
          自定义 OpenAI 兼容端点
        </BaseButton>
        <BaseButton
          variant="link"
          @click="cancel"
        >
          取消
        </BaseButton>
      </div>
    </template>

    <template v-else>
      <p
        v-if="selected?.needsServerOptIn"
        class="error-text"
      >
        这个端点是 http + 回环地址，默认被出网策略拦截：需要服务端显式打开
        <code>DOCQA_LLM_ALLOW_PRIVATE</code>，否则测试连接会返回 EGRESS_BLOCKED。
      </p>

      <BaseField
        label="连接名称"
        :error="localError && !name.trim() ? localError : null"
      >
        <input
          v-model="name"
          type="text"
          :maxlength="MODEL_CONNECTION_NAME_MAX_LENGTH"
          autocomplete="off"
        >
        <template #hint>
          <span class="muted">租户内唯一；同名重复提交会更新这一条，而不是新建。</span>
        </template>
      </BaseField>

      <BaseField label="Base URL">
        <input
          v-model="baseUrl"
          type="text"
          class="mono-id"
          :maxlength="MODEL_CONNECTION_BASE_URL_MAX_LENGTH"
          autocomplete="off"
          spellcheck="false"
        >
        <template #hint>
          <span class="muted">
            OpenAI 兼容端点根地址{{ host ? `，主机 ${host}` : '' }}；路径以服务商控制台为准。
          </span>
        </template>
      </BaseField>

      <BaseField label="模型名">
        <input
          v-model="model"
          type="text"
          list="model-candidates"
          :maxlength="MODEL_CONNECTION_MODEL_MAX_LENGTH"
          autocomplete="off"
          spellcheck="false"
        >
        <datalist id="model-candidates">
          <option
            v-for="candidate in selected?.models ?? []"
            :key="candidate"
            :value="candidate"
          />
        </datalist>
        <template #hint>
          <span class="muted">实际下发给厂商的模型名；探测到的候选会并入这里供选择。</span>
        </template>
      </BaseField>

      <BaseField label="API Key">
        <input
          v-model="secret"
          :type="showSecret ? 'text' : 'password'"
          :maxlength="MODEL_CONNECTION_SECRET_MAX_LENGTH"
          autocomplete="off"
          spellcheck="false"
        >
        <template #hint>
          <span class="muted">
            {{ selected?.keyHint ?? '在服务商控制台创建 API Key' }}
            <a
              v-if="selected?.keyUrl"
              :href="selected.keyUrl"
              target="_blank"
              rel="noopener noreferrer"
            >去申请 Key</a>
          </span>
          <span class="muted">密钥以 AES-256-GCM 密文存储，保存后不再回显；本页面不会把它写进任何日志。</span>
        </template>
      </BaseField>

      <div class="admin__toolbar">
        <BaseButton
          variant="link"
          @click="showSecret = !showSecret"
        >
          {{ showSecret ? '隐藏密钥' : '显示密钥' }}
        </BaseButton>
      </div>

      <details class="page-note">
        <summary>高级选项</summary>
        <BaseField label="这个端点不需要 API Key（本地模型）">
          <input
            v-model="noKeyAllowed"
            type="checkbox"
          >
        </BaseField>
        <BaseField label="端点不提供 GET /models 时，仍按上面填的模型名保存并设为生效">
          <input
            v-model="allowWithoutProbe"
            type="checkbox"
          >
        </BaseField>
      </details>

      <AppAlert :detail="localError" />

      <div class="admin__toolbar">
        <BaseButton
          :disabled="store.submitting"
          @click="submit"
        >
          {{ store.submitting ? '正在验证并连接…' : '测试并连接' }}
        </BaseButton>
        <BaseButton
          variant="link"
          :disabled="store.submitting"
          @click="back"
        >
          返回选择厂商
        </BaseButton>
        <BaseButton
          variant="link"
          :disabled="store.submitting"
          @click="cancel"
        >
          取消
        </BaseButton>
      </div>
    </template>
  </div>
</template>
