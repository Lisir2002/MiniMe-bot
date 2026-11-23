import { createApp } from 'vue';
import App from './clean-cache.vue';

const app = createApp(App);
if (window.MiniBotUI) app.use(window.MiniBotUI);
app.mount('#app');
