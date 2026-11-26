import { createApp, nextTick } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import { router } from './router';
import { observeInkReveal } from './composables/useInkMotion';
import { bindSessionToAuthStore } from './stores/sessionBridge';
import './styles/tokens.css';
import './styles/base.css';
import './styles/components.css';
import './styles/utilities.css';

const app = createApp(App);
app.use(createPinia());
app.use(router);

bindSessionToAuthStore(() => {
  const current = router.currentRoute.value;
  if (current.name !== 'login') {
    void router.replace({ name: 'login', query: { redirect: current.fullPath } });
  }
});

/** 每次换页后重挂观察器：旧观察器先断开，避免同一节点上堆多个实例。 */
let stopReveal: (() => void) | undefined;
function refreshReveal(): void {
  void nextTick(() => {
    stopReveal?.();
    stopReveal = observeInkReveal(document.querySelector('.app-main') ?? document);
  });
}
router.afterEach(refreshReveal);

app.mount('#app');
refreshReveal();
