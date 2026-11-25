<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import AppAlert from '../../components/ui/AppAlert.vue';
import BaseButton from '../../components/ui/BaseButton.vue';
import BaseCard from '../../components/ui/BaseCard.vue';
import SkeletonBlock from '../../components/ui/SkeletonBlock.vue';
import { useTenantControlsStore } from '../../stores/tenantControls';
import { formatDateTime } from '../../utils/display';

const controls = useTenantControlsStore();

const confirming = ref(false);
const writeResult = ref<string | null>(null);

const killSwitch = computed(() => controls.data);

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
 * 二次确认只挡「开启」这一个方向：它让本租户所有新问答拿到 503，代价与「关闭＝恢复」不对称；
 * 要求明示影响面的也正是开启分支。
 */
function askEnable(): void {
  writeResult.value = null;
  confirming.value = true;
}

function cancelEnable(): void {
  confirming.value = false;
}

/**
 * 写入结果只描述「这次赋值有没有拿到成功回执」。回显的是 OR 叠加后的有效状态：
 * 若写入了 false 而回显仍是 active=true，唯一诚实的解释是平台级一键全停还在生效，
 * 不能把它写成「已恢复服务」，也不能当成写入失败。
 */
async function write(active: boolean): Promise<void> {
  confirming.value = false;
  const succeeded = await controls.setTenantKillSwitch(active);
  if (!succeeded) {
    writeResult.value = '本次写入未回执。若上方错误条是 500，含义是「开关已生效、审计写入失败」，最终状态以重新读取到的值为准。';
    return;
  }
  if (!active && controls.active === true) {
    writeResult.value = '本租户键已关闭，但有效状态仍为「已开启」：平台级一键全停仍在生效，本租户的新问答依旧返回 503。请到「治理」页确认平台级开关，本页无法关闭它。';
    return;
  }
  writeResult.value = `已${active ? '开启' : '关闭'}本租户 Kill Switch，下方回显的是服务端写入后的有效状态；各实例最长 30 秒内收敛。`;
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
    title="本租户 Kill Switch"
    :level="1"
  >
    <details class="page-note">
      <summary>作用范围与「有效状态」</summary>
      <p>
        本开关只影响<strong>当前登录租户</strong>：租户由服务端从已验签 JWT 取得，页面不接受、也不提供选择租户的入口。
        需要 ADMIN 角色。
      </p>
      <p>
        这里显示的是<strong>有效状态</strong>——平台级一键全停键与本租户键按 OR 叠加，任一开启即为「已开启」。
        契约只返回叠加结果，因此本页无法判断当前是哪个键在生效；关闭本租户键后若仍显示「已开启」，
        说明平台级开关仍开着，那次关闭并没有让服务恢复。平台级开关在「治理」页控制。
      </p>
      <p>
        「最近写入」两个值只记录本租户键的写入。平台级开启而本租户键从未写入时，
        会出现「已开启」但两个值为空——这不是数据缺失，也不改变状态判读。
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
      label="本租户熔断状态加载中"
    />
  </BaseCard>

  <BaseCard
    v-if="controls.loaded"
    title="当前状态"
  >
    <dl class="admin-state">
      <div>
        <dt>有效状态</dt>
        <dd>
          <span :class="chipClass">{{ stateLabel }}</span>
        </dd>
      </div>
      <div>
        <dt>本租户键最近写入时刻</dt>
        <dd>{{ formatDateTime(killSwitch?.updatedAt ?? null) }}</dd>
      </div>
      <div>
        <dt>本租户键最近写入者</dt>
        <dd class="mono-id">
          {{ killSwitch?.updatedBy ?? '—' }}
        </dd>
      </div>
    </dl>

    <p
      v-if="killSwitch !== null && (killSwitch.updatedAt === null || killSwitch.updatedBy === null)"
      class="muted"
    >
      元数据缺失不改变有效状态的判读；可能只是本租户键从未写入。重新读取可恢复元数据。
    </p>

    <p
      v-if="killSwitch === null"
      class="muted"
    >
      未读取到服务端状态，因此这里不提供写入入口——在看不见当前值的情况下赋值，等于凭猜改动治理开关。
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
        {{ controls.submitting ? '提交中…' : '开启本租户 Kill Switch' }}
      </BaseButton>
      <BaseButton
        v-if="!confirming && killSwitch.active"
        :disabled="controls.submitting"
        @click="write(false)"
      >
        {{ controls.submitting ? '提交中…' : '关闭本租户键' }}
      </BaseButton>
    </div>

    <div
      v-if="confirming"
      class="admin-confirm"
    >
      <p class="error-text">
        确认开启本租户 Kill Switch？开启后本租户所有新的问答请求返回 503 AGT-UPSTREAM-003；
        登录、文档、提醒与审计查询不受影响。其他租户不受本开关影响。
        网关本地缓存 30 秒、Python 侧缓存 5 秒，因此各实例最长 30 秒内收敛，不是「瞬时全停」。
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
      这个开关是对本租户键的全量赋值：重复提交同一个值得到同一状态，每次调用都会追加一条管理审计事件。
      两个管理员同时写入时服务端是后写覆盖，界面检测不到这种覆盖。
    </p>
  </BaseCard>
</template>
