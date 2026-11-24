<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ApiError } from '../api/errors';
import AppAlert from '../components/ui/AppAlert.vue';
import BaseButton from '../components/ui/BaseButton.vue';
import BaseCard from '../components/ui/BaseCard.vue';
import BaseField from '../components/ui/BaseField.vue';
import { safeInternalPath } from '../router/redirect';
import { useAuthStore } from '../stores/auth';
import { PASSWORD_CHANGED_NOTICE } from '../utils/display';
import {
  LOGIN_USERNAME_MAX_LENGTH,
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
  PASSWORD_PATTERN,
  REGISTER_USERNAME_MAX_LENGTH,
  REGISTER_USERNAME_MIN_LENGTH,
  REGISTER_USERNAME_PATTERN,
} from '../types/api';

type Mode = 'login' | 'register';

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();

const mode = ref<Mode>('login');
const username = ref('');
const password = ref('');
const submitting = ref(false);
const errorDetail = ref<string | null>(null);
const errorTraceId = ref<string | null>(null);
const usernameError = ref<string | null>(null);
const passwordError = ref<string | null>(null);

const isRegister = computed(() => mode.value === 'register');
const usernameMaxLength = computed(() =>
  isRegister.value ? REGISTER_USERNAME_MAX_LENGTH : LOGIN_USERNAME_MAX_LENGTH,
);
const passwordMinLength = computed(() => (isRegister.value ? PASSWORD_MIN_LENGTH : 1));

/**
 * 跨页提示只认这一个固定键：URL 里的其它值一律不渲染，
 * 提示语本体由 display.ts 单点持有，查询串只携带键名。
 */
const noticeText = computed(() =>
  route.query.notice === 'password-changed' ? PASSWORD_CHANGED_NOTICE : null,
);

const canSubmit = computed(
  () => !submitting.value && username.value.length > 0 && password.value.length > 0,
);

function switchMode(next: Mode): void {
  mode.value = next;
  errorDetail.value = null;
  errorTraceId.value = null;
  usernameError.value = null;
  passwordError.value = null;
}

/** 注册的格式预检与服务端同口径；登录不预检，避免把账号是否存在暴露成客户端判断。 */
function checkRegisterInput(): boolean {
  let valid = true;
  if (!REGISTER_USERNAME_PATTERN.test(username.value)) {
    usernameError.value = `用户名需为 ${REGISTER_USERNAME_MIN_LENGTH}–${REGISTER_USERNAME_MAX_LENGTH} 位字母、数字、下划线、点或连字符。`;
    valid = false;
  }
  if (!PASSWORD_PATTERN.test(password.value)) {
    passwordError.value = `密码至少 ${PASSWORD_MIN_LENGTH} 位，且同时包含字母与数字。`;
    valid = false;
  }
  return valid;
}

async function submit(): Promise<void> {
  if (!canSubmit.value) {
    return;
  }
  errorDetail.value = null;
  errorTraceId.value = null;
  usernameError.value = null;
  passwordError.value = null;
  if (isRegister.value && !checkRegisterInput()) {
    return;
  }
  submitting.value = true;
  try {
    if (isRegister.value) {
      await auth.signUp(username.value.trim(), password.value);
    } else {
      // 与注册口径一致：登录也 trim，避免尾随空格导致 401（C-13）
      await auth.signIn(username.value.trim(), password.value);
    }
    password.value = '';
    await router.replace(safeInternalPath(route.query.redirect));
  } catch (error) {
    if (error instanceof ApiError) {
      errorDetail.value = error.detail;
      errorTraceId.value = error.traceId;
      usernameError.value = error.fieldMessage('username') ?? null;
      passwordError.value = error.fieldMessage('password') ?? null;
    } else {
      errorDetail.value = isRegister.value ? '注册失败，请稍后重试。' : '登录失败，请稍后重试。';
    }
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="login">
    <!--
      左栏是「这是什么产品」的说明书，不是营销位：三条都是系统真实具备的能力，
      用编号 + 一条发丝线排成规格表，避免堆图标与形容词。
    -->
    <section class="login__intro">
      <p class="login__eyebrow">
        DOCQA · 智能学习助手
      </p>
      <p class="login__claim">
        把课程资料<br>变成能追问的知识
      </p>
      <dl class="login__spec">
        <div>
          <dt>01</dt>
          <dd>文档解析与段落级检索</dd>
        </div>
        <div>
          <dt>02</dt>
          <dd>带引用的问答，受预算与内容边界约束</dd>
        </div>
        <div>
          <dt>03</dt>
          <dd>课表导入与到点提醒</dd>
        </div>
      </dl>
    </section>
    <BaseCard
      :title="isRegister ? '注册' : '登录'"
      :level="1"
    >
      <p
        v-if="noticeText !== null"
        class="login__notice"
        role="status"
        data-testid="login-notice"
      >
        {{ noticeText }}
      </p>
      <form @submit.prevent="submit">
        <BaseField
          label="用户名"
          :error="usernameError"
        >
          <input
            v-model="username"
            name="username"
            type="text"
            :maxlength="usernameMaxLength"
            :pattern="isRegister ? REGISTER_USERNAME_PATTERN.source : undefined"
            autocomplete="username"
            required
          >
        </BaseField>
        <BaseField
          label="密码"
          :error="passwordError"
        >
          <!-- 契约限制 72 个 UTF-8 字节以避开 BCrypt 静默截断；字节级校验在服务端，这里的 72 只是字符数提示。 -->
          <input
            v-model="password"
            name="password"
            type="password"
            :maxlength="PASSWORD_MAX_LENGTH"
            :minlength="passwordMinLength"
            :autocomplete="isRegister ? 'new-password' : 'current-password'"
            required
          >
          <template #hint>
            <span
              v-if="isRegister"
              class="muted"
            >至少 {{ PASSWORD_MIN_LENGTH }} 位，需同时包含字母与数字。</span>
          </template>
        </BaseField>
        <AppAlert
          :detail="errorDetail"
          :trace-id="errorTraceId"
          :fields-shown="usernameError !== null || passwordError !== null"
        />
        <BaseButton
          submit
          :disabled="!canSubmit"
        >
          {{ submitting ? (isRegister ? '注册中…' : '登录中…') : (isRegister ? '注册' : '登录') }}
        </BaseButton>
      </form>
      <p class="muted">
        <template v-if="isRegister">
          已有账号？
          <BaseButton
            variant="link"
            @click="switchMode('login')"
          >
            去登录
          </BaseButton>
        </template>
        <template v-else>
          还没有账号？
          <BaseButton
            variant="link"
            @click="switchMode('register')"
          >
            注册
          </BaseButton>
        </template>
      </p>
    </BaseCard>
  </div>
</template>
