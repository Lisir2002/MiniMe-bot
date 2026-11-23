import { createApp } from 'vue';
import App from './config.vue';

const app = createApp(App);
if (window.MiniBotUI) app.use(window.MiniBotUI);
app.mount('#app');
