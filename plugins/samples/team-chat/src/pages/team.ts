import { createApp } from 'vue';
import App from './team.vue';

const app = createApp(App);
if (window.MiniBotUI) app.use(window.MiniBotUI);
app.mount('#app');
