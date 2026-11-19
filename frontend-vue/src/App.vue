<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import BaseButton from './components/ui/BaseButton.vue';
import { useAuthStore } from './stores/auth';
import { isThemePreference, readThemePreference, saveThemePreference, type ThemePreference } from './theme';

type NavItem = {
  name: string;
  label: string;
  /** 16×16 网格上的描边路径。用内联几何而不用图标库：多一个依赖换九个图形不划算。 */
  paths: readonly string[];
};

/**
 * 主导航只有三项。把「账户」沉到底部、把治理面折进第二组：
 * 学习动线越短，误点治理面（每个动作都会写审计）的概率越低。
 */
const primaryNav: readonly NavItem[] = [
  {
    name: 'documents',
    label: '文档',
    paths: ['M4.5 2.5h4.5L11.5 5v8.5h-7z', 'M9 2.5V5h2.5', 'M6.2 8.2h3.6', 'M6.2 10.8h2.4'],
  },
  {
    name: 'ask',
    label: '问答',
    paths: ['M3 3.5h10v6.2H7.2L4.2 12V9.7H3z', 'M5.6 6.2h4.8'],
  },
  {
    name: 'reminders',
    label: '提醒',
    paths: ['M8 2.6a5.1 5.1 0 1 0 0 10.2A5.1 5.1 0 0 0 8 2.6z', 'M8 5.4V8l1.9 1.2'],
  },
];

const adminNav: readonly NavItem[] = [
  { name: 'admin-ai-controls', label: '治理', paths: ['M3 4.6h10', 'M3 8h10', 'M3 11.4h10', 'M6 3v3.2', 'M10.4 6.4v3.2', 'M7.4 9.6v3.2'] },
  { name: 'admin-tenant-controls', label: '本租户', paths: ['M8 1.8 13.4 4v4L8 12.2 2.6 8V4z', 'M2.6 4 8 6.2 13.4 4', 'M8 6.2v6'] },
  { name: 'admin-quotas', label: '配额', paths: ['M3.2 3.6h9.6v8.8H3.2z', 'M3.2 6.6h9.6', 'M6.4 9.2h3.2'] },
  { name: 'admin-audit', label: '审计', paths: ['M4.2 2.8h7.6v10.4H4.2z', 'M6 6.4l1.4 1.4 2.6-2.6', 'M6 10.4h4'] },
  { name: 'admin-usage', label: '用量', paths: ['M3 12.8h10', 'M4.6 12.8V8.4', 'M8 12.8V4.6', 'M11.4 12.8V9.4'] },
  { name: 'admin-users', label: '用户', paths: ['M6.2 3.4a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z', 'M2.8 13c.5-2.1 1.9-3.2 3.4-3.2s2.9 1.1 3.4 3.2', 'M10.6 4.2a1.8 1.8 0 1 1 0 3.6', 'M11.4 9.9c1.2.3 2 1.3 2.3 3.1'] },
];

const accountNav: readonly NavItem[] = [
  {
    name: 'home',
    label: '账户',
    paths: ['M8 2.8a2.6 2.6 0 1 0 0 5.2 2.6 2.6 0 0 0 0-5.2z', 'M3 13.2c.7-2.5 2.6-3.9 5-3.9s4.3 1.4 5 3.9'],
  },
];

const auth = useAuthStore();
const router = useRouter();
const isAuthenticated = computed(() => auth.isAuthenticated);
const themePreference = ref<ThemePreference>(readThemePreference());
const mainRegion = ref<HTMLElement | null>(null);

/** 片段链接不会把焦点交给目标区域（浏览器只在目标可聚焦时才移交给它，jsdom 干脆不移交），所以跳过链接自己移交。 */
function skipToMain(): void {
  mainRegion.value?.focus();
}

function onThemeChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value;
  if (!isThemePreference(value)) {
    return;
  }
  themePreference.value = value;
  saveThemePreference(value);
}

/**
 * 退出必须显式导航：`signOut()` 只清本地会话，不会改变当前路由。
 * 停在原页面会让界面「看似在线」，而此后每个请求都带不上令牌、逐个 401（S10-M4）。
 * 用 `replace` 而非 `push`，是为了让浏览器的后退键不把一个已被清空的会话页翻回来。
 */
async function onSignOut(): Promise<void> {
  await auth.signOut();
  await router.replace({ name: 'login' });
}
</script>

<template>
  <a
    class="skip-link"
    href="#main-content"
    @click.prevent="skipToMain"
  >跳到主内容</a>
  <div
    class="app-shell"
    :class="{ 'app-shell--with-rail': isAuthenticated }"
  >
    <header class="app-header">
      <span class="app-header__brand">
        <span
          class="app-header__mark"
          aria-hidden="true"
        />
        DocQA
      </span>
      <nav class="app-header__nav">
        <!-- 主题开关与登录态无关：匿名访客也要能选。 -->
        <label class="theme-switch">
          <span>主题</span>
          <select
            :value="themePreference"
            @change="onThemeChange"
          >
            <option value="auto">
              自动
            </option>
            <option value="light">
              浅色
            </option>
            <option value="dark">
              深色
            </option>
          </select>
        </label>
        <BaseButton
          v-if="isAuthenticated"
          variant="link"
          data-testid="sign-out"
          @click="onSignOut"
        >
          退出登录
        </BaseButton>
        <RouterLink
          v-else
          :to="{ name: 'login' }"
        >登录</RouterLink>
      </nav>
    </header>

    <nav
      v-if="isAuthenticated"
      class="app-rail"
      aria-label="主导航"
    >
      <RouterLink
        v-for="item in primaryNav"
        :key="item.name"
        class="app-rail__item"
        :to="{ name: item.name }"
      >
        <svg
          class="app-rail__icon"
          viewBox="0 0 16 16"
          fill="none"
          stroke="currentColor"
          stroke-width="1.2"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path
            v-for="(path, index) in item.paths"
            :key="index"
            :d="path"
          />
        </svg>
        <span>{{ item.label }}</span>
      </RouterLink>

      <!-- 入口可见性只是「不让非管理员点进一个必然 403 的页面」，服务端逐请求的 ADMIN 校验才是判据。 -->
      <template v-if="auth.isAdmin">
        <div
          class="app-rail__divider"
          role="presentation"
        />
        <RouterLink
          v-for="item in adminNav"
          :key="item.name"
          class="app-rail__item"
          :to="{ name: item.name }"
        >
          <svg
            class="app-rail__icon"
            viewBox="0 0 16 16"
            fill="none"
            stroke="currentColor"
            stroke-width="1.2"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path
              v-for="(path, index) in item.paths"
              :key="index"
              :d="path"
            />
          </svg>
          <span>{{ item.label }}</span>
        </RouterLink>
      </template>

      <div class="app-rail__footer">
        <RouterLink
          v-for="item in accountNav"
          :key="item.name"
          class="app-rail__item"
          :to="{ name: item.name }"
        >
          <svg
            class="app-rail__icon"
            viewBox="0 0 16 16"
            fill="none"
            stroke="currentColor"
            stroke-width="1.2"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path
              v-for="(path, index) in item.paths"
              :key="index"
              :d="path"
            />
          </svg>
          <span>{{ item.label }}</span>
        </RouterLink>
      </div>
    </nav>

    <main
      id="main-content"
      ref="mainRegion"
      class="app-main"
      tabindex="-1"
    >
      <RouterView />
    </main>
  </div>
</template>
