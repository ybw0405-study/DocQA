<script setup lang="ts">
import { useInkTilt } from '../../composables/useInkMotion';

/** 3D 纸叠：三张书页层叠，鼠标移动时倾斜、悬停时散开。 */
withDefaults(defineProps<{ compact?: boolean }>(), { compact: false });
const { target } = useInkTilt(9);
// 模板里通过字符串 ref 绑定，显式读一次值避免类型检查报未使用。
void target.value;
</script>

<template>
  <div
    ref="target"
    class="ink-paper-stack"
    :class="{ 'ink-paper-stack--compact': compact }"
    aria-hidden="true"
  >
    <div class="ink-paper-stack__stage">
      <article class="ink-sheet ink-sheet--back">
        <div class="ink-sheet__head"><span>卷一 / 文档解析</span><i>FIG. 01</i></div>
        <h4>把资料摊开</h4>
        <p>课程资料、手册与规范进入同一张书桌，段落级索引让每一句都能被找到。</p>
        <span class="ink-sheet__seal">文</span>
      </article>
      <article class="ink-sheet ink-sheet--mid">
        <div class="ink-sheet__head"><span>卷二 / 带引用问答</span><i>FIG. 02</i></div>
        <h4>追问有据</h4>
        <p>回答旁留出脚注位，命中段落与相关度一并摊在纸上，结论可以逐条核对。</p>
        <span class="ink-sheet__seal">引</span>
      </article>
      <article class="ink-sheet ink-sheet--front">
        <div class="ink-sheet__head"><span>卷三 / 课表与提醒</span><i>FIG. 03</i></div>
        <h4>到点自会响</h4>
        <p>导入课表后按本地时区排程，待触发、已暂停与不再触发各有归宿。</p>
        <span class="ink-sheet__seal">时</span>
      </article>
    </div>
    <p class="ink-paper-stack__hint">移动鼠标 · 翻检书页</p>
  </div>
</template>
