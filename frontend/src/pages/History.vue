<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ScrollText, Headset, User } from "lucide-vue-next";
import { useAuthStore } from "@/stores/auth";
import { getActors } from "@/api/logs";
import SelectMenu from "@/components/SelectMenu.vue";
import OperationLogs from "./OperationLogs.vue";
import AtendimentoHistory from "./AtendimentoHistory.vue";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

// Filtro por usuário (actor) — afeta a aba ativa.
const selectedActor = ref("");
const actorOptions = ref<{ value: string; label: string }[]>([]);

onMounted(async () => {
  try {
    const actors = await getActors();
    actorOptions.value = actors.map((a) => ({ value: a, label: a }));
  } catch {
    actorOptions.value = [];
  }
});

// Abas disponíveis conforme as permissões do usuário.
const tabs = computed(() => {
  const t: { key: string; label: string; icon: any }[] = [];
  if (auth.can("logs")) t.push({ key: "operacoes", label: "Operações", icon: ScrollText });
  if (auth.can("atendimento_historico")) t.push({ key: "atendimento", label: "Atendimento", icon: Headset });
  return t;
});

const active = ref("");

function pickInitial() {
  const wanted = String(route.query.tab || "");
  const allowed = tabs.value.map((t) => t.key);
  active.value = allowed.includes(wanted) ? wanted : (allowed[0] || "");
}

onMounted(pickInitial);
watch(tabs, pickInitial);

function setTab(key: string) {
  if (active.value === key) return;
  active.value = key;
  // Mantém a aba na URL pra permitir link direto / refresh.
  router.replace({ query: { ...route.query, tab: key } });
}
</script>

<template>
  <div>
    <!-- Cabeçalho + seletor de aba -->
    <div class="mb-6">
      <p class="text-[11px] font-bold uppercase tracking-[0.22em] text-gray-500 mb-0.5">
        Auditoria
      </p>
      <div class="flex items-center justify-between gap-4 flex-wrap">
        <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight">Histórico</h2>

        <div class="flex items-center gap-3 flex-wrap">
          <!-- Filtro por usuário -->
          <div class="flex items-center gap-1.5">
            <User :size="15" class="text-gray-400" />
            <SelectMenu
              v-model="selectedActor"
              :options="actorOptions"
              empty-label="Todos os usuários"
              placeholder="Todos os usuários"
              align="right"
              size="sm"
            />
          </div>

          <!-- Segmented control (só aparece se houver mais de uma aba) -->
          <div
            v-if="tabs.length > 1"
            class="inline-flex p-1 rounded-xl bg-gray-100 dark:bg-zinc-800"
          >
            <button
              v-for="t in tabs"
              :key="t.key"
              @click="setTab(t.key)"
              class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-semibold transition-colors"
              :class="active === t.key
                ? 'bg-brand-black text-brand-yellow shadow-sm dark:bg-brand-yellow dark:text-brand-black'
                : 'text-gray-600 hover:text-gray-900 dark:text-gray-300 dark:hover:text-gray-100'"
            >
              <component :is="t.icon" :size="15" />
              {{ t.label }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Conteúdo da aba ativa -->
    <OperationLogs v-if="active === 'operacoes'" :actor="selectedActor" />
    <AtendimentoHistory v-else-if="active === 'atendimento'" :actor="selectedActor" />
  </div>
</template>
