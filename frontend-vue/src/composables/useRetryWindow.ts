import { computed, onBeforeUnmount, ref, watch, type ComputedRef, type WatchSource } from 'vue';

/**
 * 契约只在 429 上声明 `Retry-After`，所以这段等待逻辑只服务于限流后的重试门控。
 * store 里保存的是服务端给定的整秒数，本地时钟只活在视图层，不进 store。
 */
export function useRetryWindow(secondsSource: WatchSource<number | null>): {
  secondsLeft: ComputedRef<number>;
  waiting: ComputedRef<boolean>;
} {
  const waitingUntil = ref<number | null>(null);
  const now = ref(Date.now());
  let ticker: ReturnType<typeof setInterval> | null = null;

  const secondsLeft = computed(() =>
    waitingUntil.value === null
      ? 0
      : Math.max(0, Math.ceil((waitingUntil.value - now.value) / 1000)),
  );
  const waiting = computed(() => secondsLeft.value > 0);

  function stop(): void {
    if (ticker !== null) {
      clearInterval(ticker);
      ticker = null;
    }
  }

  function start(): void {
    if (ticker !== null) {
      return;
    }
    ticker = setInterval(() => {
      now.value = Date.now();
      if (secondsLeft.value === 0) {
        stop();
      }
    }, 1000);
  }

  // immediate：`retryAfterSeconds` 活在 store 里、跨路由保留，重新挂载时若不立即取一次，
  // 等待门控会在用户切回页面的一瞬间失效。代价是重新计满服务端秒数（多等），收益是不会少等。
  watch(secondsSource, (seconds) => {
    stop();
    if (seconds === null) {
      waitingUntil.value = null;
      return;
    }
    waitingUntil.value = Date.now() + seconds * 1000;
    // `now` 是 composable 建立时取的，窗口却是稍后才开的：不重取就会把服务端给的秒数多显一秒。
    now.value = Date.now();
    start();
  }, { immediate: true });

  onBeforeUnmount(stop);

  return { secondsLeft, waiting };
}
