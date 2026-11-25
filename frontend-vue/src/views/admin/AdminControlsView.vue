<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import AppAlert from '../../components/ui/AppAlert.vue';
import BaseButton from '../../components/ui/BaseButton.vue';
import BaseCard from '../../components/ui/BaseCard.vue';
import SkeletonBlock from '../../components/ui/SkeletonBlock.vue';
import { useAdminControlsStore } from '../../stores/adminControls';
import { formatDateTime } from '../../utils/display';

const controls = useAdminControlsStore();

const confirming = ref(false);
const writeResult = ref<string | null>(null);

const killSwitch = computed(() => controls.data?.killSwitch ?? null);
const budget = computed(() => controls.data?.experimentBudget ?? null);

/** 三态而不是布尔：未读到 / 已开启 / 已关闭。把「未知」渲染成「关闭」就是伪造治理事实。 */
const stateLabel = computed(() => {
  if (killSwitch.value === null) {
    return '未读取';
  }
  return killSwitch.value.active ? '已开启' : '已关闭';
});

const chipClass = computed(() => {
  if (killSwitch.value === null) {
    return 'governance__chip';
  }
  return killSwitch.value.active ? 'governance__chip governance__chip--warn' : 'governance__chip governance__chip--ok';
});

/**
 * 二次确认只挡「开启」这一个方向：它让所有新问答拿到 503，代价与「关闭＝恢复服务」不对称，
 *  要求明示影响面的也正是开启分支。
 */
function askEnable(): void {
  writeResult.value = null;
  confirming.value = true;
}

function cancelEnable(): void {
  confirming.value = false;
}

/**
 * 写入结果只描述「这次赋值有没有拿到成功回执」：契约上这是全量赋值、按值幂等，
 * 没有版本号也没有幂等键，所以不宣称乐观锁冲突，也不宣称瞬时全量生效（网关缓存 30 秒、Python 侧 5 秒）。
 */
async function write(active: boolean): Promise<void> {
  confirming.value = false;
  const succeeded = await controls.setKillSwitch(active);
  writeResult.value = succeeded
    ? `已${active ? '开启' : '关闭'} Kill Switch，下方回显的是服务端写入后回显的状态；各实例最长 30 秒内收敛。`
    : '本次写入未回执。若上方错误条是 500，含义是「开关已生效、审计写入失败」，最终状态以重新读取到的值为准。';
}

async function refresh(): Promise<void> {
  confirming.value = false;
  writeResult.value = null;
  await controls.fetch();
}

onMounted(() => {
  void controls.load();
});
</script>

<template>
  <BaseCard
    title="AI 治理"
    :level="1"
  >
    <details class="page-note">
      <summary>这些值从哪来</summary>
      <p>
        本页只回显服务端事实：Kill Switch 的状态源是 Redis 键的存在性，实验账本由 Python 侧维护，
        读取经网关 30 秒本地缓存。本页不做任何本地推断。
      </p>
    </details>
    <div class="admin__toolbar">
      <BaseButton
        :disabled="controls.loading"
        @click="refresh"
      >
        {{ controls.loading ? '读取中…' : '重新读取' }}
      </BaseButton>
    </div>

    <AppAlert
      :detail="controls.detail"
      :code="controls.code"
      :trace-id="controls.traceId"
    />

    <p
      v-if="writeResult !== null"
      class="admin-controls__result"
      role="status"
    >
      {{ writeResult }}
    </p>

    <SkeletonBlock
      v-if="controls.loading && !controls.loaded"
      label="治理状态加载中"
    />
  </BaseCard>

  <BaseCard
    v-if="controls.loaded"
    title="Kill Switch"
  >
    <dl class="admin-state">
      <div>
        <dt>当前状态</dt>
        <dd>
          <span :class="chipClass">{{ stateLabel }}</span>
        </dd>
      </div>
      <div>
        <dt>最近写入时刻</dt>
        <dd>{{ formatDateTime(killSwitch?.updatedAt ?? null) }}</dd>
      </div>
      <div>
        <dt>最近写入者</dt>
        <dd class="mono-id">
          {{ killSwitch?.updatedBy ?? '—' }}
        </dd>
      </div>
    </dl>

    <p
      v-if="killSwitch !== null && (killSwitch.updatedAt === null || killSwitch.updatedBy === null)"
      class="muted"
    >
      元数据缺失不改变开关本身的判读；重新读取可恢复元数据。
    </p>

    <p
      v-if="killSwitch === null"
      class="muted"
    >
      未读取到服务端状态，因此这里不提供写入入口——在看不见当前值的情况下赋值，等于凭猜改动全站总闸。
    </p>

    <div
      v-if="killSwitch !== null"
      class="admin__toolbar"
    >
      <BaseButton
        v-if="!confirming && !killSwitch.active"
        variant="danger"
        :disabled="controls.submitting"
        @click="askEnable"
      >
        {{ controls.submitting ? '提交中…' : '开启 Kill Switch' }}
      </BaseButton>
      <BaseButton
        v-if="!confirming && killSwitch.active"
        :disabled="controls.submitting"
        @click="write(false)"
      >
        {{ controls.submitting ? '提交中…' : '关闭 Kill Switch' }}
      </BaseButton>
    </div>

    <div
      v-if="confirming"
      class="admin-confirm"
    >
      <p class="error-text">
        确认开启 Kill Switch？开启后所有新的问答请求返回 503 AGT-UPSTREAM-003；
        登录、文档、提醒与审计查询不受影响。网关本地缓存 30 秒、Python 侧缓存 5 秒，
        因此各实例最长 30 秒内收敛，不是「瞬时全停」。
      </p>
      <div class="admin__toolbar">
        <BaseButton
          variant="danger"
          :disabled="controls.submitting"
          @click="write(true)"
        >
          {{ controls.submitting ? '提交中…' : '确认开启' }}
        </BaseButton>
        <BaseButton
          :disabled="controls.submitting"
          @click="cancelEnable"
        >
          取消
        </BaseButton>
      </div>
    </div>

    <p class="muted">
      这个开关是对单一状态的全量赋值：重复提交同一个值得到同一状态，每次调用都会追加一条管理审计事件。
      两个管理员同时写入时服务端是后写覆盖，界面检测不到这种覆盖。
    </p>
  </BaseCard>

  <BaseCard
    v-if="controls.loaded"
    title="实验额度账本"
  >
    <dl
      v-if="budget !== null"
      class="admin-state"
    >
      <div>
        <dt>本轮调用次数</dt>
        <dd>{{ budget.callsUsed }}</dd>
      </div>
      <div>
        <dt>调用上限</dt>
        <dd>{{ budget.callLimit ?? '上限未发布' }}</dd>
      </div>
      <div>
        <dt>本轮 token 用量</dt>
        <dd>{{ budget.tokensUsed }}</dd>
      </div>
      <div>
        <dt>token 上限</dt>
        <dd>{{ budget.tokenLimit ?? '上限未发布' }}</dd>
      </div>
    </dl>

    <p
      v-if="budget !== null && !controls.limitsKnown"
      class="muted"
    >
      Python 尚未发布上限（离线部署，或它的启动晚于网关），因此上限不可知。不可知不是 0，也不是一条已经用满的额度。
    </p>

    <p class="muted">
      账本只读、不提供在线重置：计数没有 TTL，上限是真实花费硬闸，需要重置时由部署者人工删键并自行留痕。
    </p>
  </BaseCard>
</template>
