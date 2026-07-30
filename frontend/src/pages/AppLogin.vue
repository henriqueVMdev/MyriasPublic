<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { Loader2, Sun, Moon } from "lucide-vue-next";
import { appLogin, getLoginUsers, type PublicUser } from "@/api/users";
import { useThemeStore } from "@/stores/theme";
import { useAuthStore } from "@/stores/auth";

const router = useRouter();
const theme = useThemeStore();
const auth = useAuthStore();
const users = ref<PublicUser[]>([]);
const username = ref("");
const password = ref("");
const error = ref("");
const loading = ref(false);

onMounted(async () => {
  try {
    users.value = await getLoginUsers();
    if (users.value.length === 1) username.value = users.value[0].username;
  } catch {
    // lista indisponível — usuário ainda pode digitar? mantemos só o select
  }
});

async function login() {
  if (!username.value || !password.value) return;
  error.value = "";
  loading.value = true;
  try {
    await appLogin(username.value, password.value);
    // Carrega sessão (permissões) + contas ML antes de navegar.
    await auth.loadSession();
    await auth.checkAuth();
    router.replace("/");
  } catch (e: any) {
    error.value = e.response?.data?.detail || "Usuário ou senha incorretos.";
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div
    class="min-h-screen flex items-center justify-center p-4 transition-colors
           bg-cover bg-center bg-no-repeat
           bg-[url('/Background_black.svg')]
           dark:bg-[url('/Background_yellow_liquid_flowing_smooth.svg')]"
  >
    <!-- Theme toggle (canto superior direito) -->
    <button
      @click="theme.toggle()"
      class="absolute top-4 right-4 p-2 rounded-full transition-colors
             bg-white/70 hover:bg-white text-brand-black shadow
             dark:bg-zinc-800 dark:hover:bg-zinc-700 dark:text-brand-yellow"
      :title="theme.mode === 'dark' ? 'Tema claro' : 'Tema escuro'"
    >
      <Sun v-if="theme.mode === 'dark'" :size="18" />
      <Moon v-else :size="18" />
    </button>

    <div
      class="rounded-2xl shadow-xl p-8 w-full max-w-md transition-colors
             bg-white
             dark:bg-brand-black-soft dark:border dark:border-zinc-800"
    >
      <!-- Company branding -->
      <div class="flex flex-col items-center mb-8">
        <img
          src="/MyriasDarkLogo.png"
          alt="Myrias Imports"
          class="h-20 w-auto mb-4 drop-shadow dark:hidden"
        />
        <img
          src="/MyriasYellowLogo.png"
          alt="Myrias Imports"
          class="h-20 w-auto mb-4 drop-shadow hidden dark:block"
        />
        <p class="text-sm text-gray-500 dark:text-gray-400 mt-1">Painel de Gestão Interno</p>
      </div>

      <!-- Login form -->
      <form @submit.prevent="login" class="space-y-5">
        <div>
          <label
            for="username"
            class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1.5"
          >
            Usuário
          </label>
          <select
            id="username"
            v-model="username"
            class="w-full px-4 py-3 rounded-xl outline-none text-sm transition-colors
                   border border-gray-200 bg-gray-50 text-gray-900
                   focus:ring-2 focus:ring-brand-yellow focus:border-transparent
                   dark:border-zinc-700 dark:bg-zinc-900 dark:text-gray-100
                   dark:focus:ring-brand-yellow"
          >
            <option value="" disabled>Selecione seu usuário</option>
            <option v-for="u in users" :key="u.username" :value="u.username">
              {{ u.display_name || u.username }}
            </option>
          </select>
        </div>

        <div>
          <label
            for="access-code"
            class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1.5"
          >
            Senha
          </label>
          <input
            id="access-code"
            v-model="password"
            type="password"
            placeholder="Digite sua senha"
            autocomplete="current-password"
            class="w-full px-4 py-3 rounded-xl outline-none text-sm transition-colors
                   border border-gray-200 bg-gray-50 text-gray-900
                   focus:ring-2 focus:ring-brand-yellow focus:border-transparent
                   dark:border-zinc-700 dark:bg-zinc-900 dark:text-gray-100
                   dark:focus:ring-brand-yellow"
          />
        </div>

        <p
          v-if="error"
          class="text-sm text-center rounded-lg py-2 px-3
                 text-red-600 bg-red-50
                 dark:text-red-400 dark:bg-red-900/30"
        >
          {{ error }}
        </p>

        <button
          type="submit"
          :disabled="loading || !password || !username"
          class="w-full py-3 rounded-xl text-sm font-medium transition-all
                 flex items-center justify-center gap-2
                 disabled:opacity-50 disabled:cursor-not-allowed
                 bg-brand-black text-brand-yellow hover:bg-brand-black-soft
                 dark:bg-brand-yellow dark:text-brand-black dark:hover:bg-brand-yellow-dark"
        >
          <Loader2 v-if="loading" :size="16" class="animate-spin" />
          {{ loading ? "Verificando..." : "Acessar painel" }}
        </button>
      </form>

      <!-- Footer -->
      <div class="mt-8 space-y-1 text-center">
        <p class="text-xs text-gray-500 dark:text-gray-400">
          Sistema interno Myrias Imports — acesso restrito a colaboradores.
        </p>
        <p class="text-xs text-gray-400 dark:text-gray-500">
          Dúvidas ou problemas de acesso:
          <a href="mailto:assist88myrias@outlook.com" class="underline hover:text-gray-600 dark:hover:text-gray-300">
            assist88myrias@outlook.com
          </a>
        </p>
        <p class="text-[11px] text-gray-400 dark:text-gray-500 pt-2">
          &copy; {{ new Date().getFullYear() }} Myrias Imports &middot;
          <router-link to="/sobre" class="underline hover:text-gray-600 dark:hover:text-gray-300">
            Sobre
          </router-link>
        </p>
      </div>
    </div>
  </div>
</template>
