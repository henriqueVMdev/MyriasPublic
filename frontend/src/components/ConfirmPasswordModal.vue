<script setup lang="ts">
import { ref, watch, nextTick } from "vue";
import { Loader2, ShieldAlert, X } from "lucide-vue-next";
import { appLogin } from "@/api/users";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();

const props = withDefaults(
  defineProps<{
    open: boolean;
    title?: string;
    message?: string;
    confirmLabel?: string;
  }>(),
  {
    title: "Confirmar ação",
    message: "Esta ação é sensível. Digite sua senha para confirmar.",
    confirmLabel: "Confirmar",
  }
);

const emit = defineEmits<{ confirmed: []; cancel: [] }>();

const password = ref("");
const error = ref("");
const loading = ref(false);
const inputEl = ref<HTMLInputElement | null>(null);

watch(
  () => props.open,
  (v) => {
    if (v) {
      password.value = "";
      error.value = "";
      loading.value = false;
      nextTick(() => inputEl.value?.focus());
    }
  }
);

function cancel() {
  if (loading.value) return;
  emit("cancel");
}

async function confirm() {
  if (!password.value || loading.value) return;
  error.value = "";
  loading.value = true;
  try {
    // Revalida a senha do PRÓPRIO usuário logado (200 = ok, 403 = incorreta).
    // Em bootstrap (sem usuário), não há o que revalidar — libera direto.
    if (auth.me) {
      await appLogin(auth.me.username, password.value);
    }
    emit("confirmed");
  } catch (e: any) {
    error.value = e?.response?.data?.detail || "Senha incorreta.";
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/50"
      @click.self="cancel"
    >
      <div
        class="w-full max-w-sm rounded-2xl shadow-xl overflow-hidden
               bg-white dark:bg-brand-black-soft border border-gray-200 dark:border-zinc-700"
      >
        <div class="flex items-start gap-3 p-5">
          <div class="p-2 rounded-full bg-red-100 dark:bg-red-900/40 flex-shrink-0">
            <ShieldAlert :size="20" class="text-red-600 dark:text-red-400" />
          </div>
          <div class="min-w-0 flex-1">
            <h3 class="text-base font-bold text-gray-900 dark:text-gray-100">{{ title }}</h3>
            <p class="text-sm text-gray-500 dark:text-gray-400 mt-1">{{ message }}</p>
          </div>
          <button
            @click="cancel"
            class="p-1 rounded-lg hover:bg-gray-100 dark:hover:bg-zinc-800 text-gray-400"
          >
            <X :size="18" />
          </button>
        </div>

        <div class="px-5 pb-2">
          <label class="block text-xs text-gray-500 mb-1">Sua senha</label>
          <input
            ref="inputEl"
            v-model="password"
            type="password"
            autocomplete="current-password"
            placeholder="••••••••"
            class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-red-500
                   dark:bg-zinc-900 dark:border-zinc-700 dark:text-gray-100"
            @keyup.enter="confirm"
          />
          <p v-if="error" class="text-xs text-red-600 mt-1">{{ error }}</p>
        </div>

        <div class="flex justify-end gap-2 p-5 pt-3">
          <button
            @click="cancel"
            :disabled="loading"
            class="px-3 py-2 rounded-lg text-sm border hover:bg-gray-50 disabled:opacity-50
                   dark:border-zinc-700 dark:hover:bg-zinc-800 dark:text-gray-200"
          >
            Cancelar
          </button>
          <button
            @click="confirm"
            :disabled="loading || !password"
            class="px-3 py-2 rounded-lg text-sm bg-red-600 text-white hover:bg-red-700 disabled:opacity-50
                   inline-flex items-center gap-1.5"
          >
            <Loader2 v-if="loading" :size="14" class="animate-spin" />
            {{ confirmLabel }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
