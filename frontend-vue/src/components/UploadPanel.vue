<script setup lang="ts">
import BaseButton from './ui/BaseButton.vue';
import EmptyState from './ui/EmptyState.vue';
import type { DocumentStatus } from '../types/documents';
import type { UploadPhase } from '../stores/uploads';
import { useUploadsStore } from '../stores/uploads';
import { EMPTY_STATE_TEXT, PHASE_LABELS, STATUS_LABELS, formatBytes } from '../utils/display';

const ACTIVE_PHASES: readonly UploadPhase[] = ['hashing', 'uploading', 'indexing'];

const uploads = useUploadsStore();

function isActive(phase: UploadPhase): boolean {
  return ACTIVE_PHASES.includes(phase);
}

function onFilesChange(event: Event): void {
  const target = event.target as HTMLInputElement;
  const files = target.files;
  if (files === null) {
    return;
  }
  for (const file of Array.from(files)) {
    // 失败一律登记到任务行里，这里没有需要额外提示的分支。
    void uploads.upload(file);
  }
  // 不清空 value 的话，连续选同一个文件不会触发 change。
  target.value = '';
}

function statusLabel(status: DocumentStatus | null): string {
  return status === null ? '' : STATUS_LABELS[status];
}
</script>

<template>
  <section class="upload-panel">
    <!--
      整块虚线区就是一个 label：原生 file 控件被铺满并透明化，点击任意处都能选文件。
      不用 <img>（红线禁止），图标走内联 SVG。
    -->
    <label class="dropzone">
      <input
        type="file"
        multiple
        accept=".pdf,.pptx,.docx,.txt"
        @change="onFilesChange"
      >
      <svg
        class="dropzone__icon"
        viewBox="0 0 24 24"
        aria-hidden="true"
      >
        <path
          d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5"
          fill="none"
          stroke="currentColor"
          stroke-width="1.3"
          stroke-linecap="square"
        />
        <path
          d="M4 15v3.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V15"
          fill="none"
          stroke="currentColor"
          stroke-width="1.3"
          stroke-linecap="square"
        />
      </svg>
      <span class="dropzone__title">拖放文件到这里，或点击选择</span>
      <span class="dropzone__hint">PDF、PPTX、DOCX、TXT · 单个文件不超过 50MiB · 支持多选</span>
    </label>

    <ul
      v-if="uploads.tasks.length > 0"
      class="upload-tasks"
    >
      <li
        v-for="task in uploads.tasks"
        :key="task.id"
        class="upload-task"
      >
        <p class="upload-task__title">
          {{ task.fileName }}
          <span class="muted">（{{ formatBytes(task.sizeBytes) }}）</span>
        </p>
        <progress
          v-if="isActive(task.phase)"
          :value="task.progressBytes"
          :max="task.sizeBytes"
        />
        <p class="upload-task__status">
          <span>{{ PHASE_LABELS[task.phase] }}</span>
          <span
            v-if="task.phase === 'uploading' && task.partCount > 0"
            class="muted"
          >分片 {{ task.partsSent }}/{{ task.partCount }}</span>
          <span
            v-if="task.status !== null"
            class="muted"
          >服务端状态：{{ statusLabel(task.status) }}</span>
          <span
            v-if="task.errorCode !== null"
            class="muted"
          >错误码 {{ task.errorCode }}</span>
        </p>
        <p
          v-if="task.detail !== null"
          class="error-text"
        >
          {{ task.detail }}
        </p>
        <BaseButton
          v-if="isActive(task.phase)"
          variant="link"
          @click="uploads.cancel(task.id)"
        >
          取消
        </BaseButton>
      </li>
    </ul>
    <EmptyState
      v-else
      :text="EMPTY_STATE_TEXT.uploadsNone"
    />

    <BaseButton
      v-if="uploads.tasks.length > 0"
      @click="uploads.clearFinished()"
    >
      清除已结束条目
    </BaseButton>
  </section>
</template>
