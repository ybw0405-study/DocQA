import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import { router } from './router';
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

app.mount('#app');
