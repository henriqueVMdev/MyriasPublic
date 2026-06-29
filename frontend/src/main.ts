import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import router from "./router";
import { useThemeStore } from "./stores/theme";
import "./style.css";

const app = createApp(App);
const pinia = createPinia();
app.use(pinia);
useThemeStore();
app.use(router);
app.mount("#app");
