<script setup lang="ts">
import BaseCard from '../components/ui/BaseCard.vue';
</script>

<template>
  <BaseCard
    title="页面不存在"
    :level="1"
  >
    <p class="muted">
      请从导航重新进入，或返回账户页。
    </p>
    <RouterLink :to="{ name: 'home' }">返回账户</RouterLink>
  </BaseCard>
</template>
