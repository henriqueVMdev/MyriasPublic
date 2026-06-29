<script setup lang="ts">
import { onMounted, watch, onUnmounted } from "vue";
import { useAuthStore } from "@/stores/auth";
import { useQuestionsStore } from "@/stores/questions";
import { useMessagesStore } from "@/stores/messages";

const auth = useAuthStore();
const questionsStore = useQuestionsStore();
const messagesStore = useMessagesStore();

// Liga o polling só pra quem tem a seção (evita 403 em loop) e está com ML
// conectado.
function syncPolling() {
  if (auth.status.authenticated && auth.can("perguntas")) questionsStore.startPolling();
  else questionsStore.stopPolling();
  if (auth.status.authenticated && auth.can("mensagens")) messagesStore.startPolling();
  else messagesStore.stopPolling();
}

onMounted(async () => {
  if (!auth.sessionLoaded) await auth.loadSession();
  await auth.checkAuth();
  syncPolling();
});

// Reage a mudanças de conexão ML ou de sessão/permissões.
watch(() => [auth.status.authenticated, auth.me], syncPolling, { deep: true });

onUnmounted(() => {
  questionsStore.stopPolling();
  messagesStore.stopPolling();
});
</script>

<template>
  <router-view />
</template>
