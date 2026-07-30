<script setup lang="ts">
import { ref, watch } from "vue";
import { useRoute } from "vue-router";
import Sidebar from "./Sidebar.vue";
import AssistantWidget from "./AssistantWidget.vue";
import { Menu, Sun, Moon } from "lucide-vue-next";
import { useThemeStore } from "@/stores/theme";
import { useAuthStore } from "@/stores/auth";

const theme = useThemeStore();
const auth = useAuthStore();
const route = useRoute();
const sidebarOpen = ref(false);

// Colapso da sidebar no desktop — persistido entre sessões
const COLLAPSE_KEY = "myrias-sidebar-collapsed";
const collapsed = ref(localStorage.getItem(COLLAPSE_KEY) === "1");
watch(collapsed, (v) => localStorage.setItem(COLLAPSE_KEY, v ? "1" : "0"));
</script>

<template>
  <div class="flex h-screen">
    <!-- Overlay mobile -->
    <div
      v-if="sidebarOpen"
      class="fixed inset-0 bg-black/40 backdrop-blur-[2px] z-20 lg:hidden"
      @click="sidebarOpen = false"
    />

    <!-- Sidebar -->
    <div
      class="fixed z-30 h-full transition-transform duration-200 lg:static lg:translate-x-0"
      :class="sidebarOpen ? 'translate-x-0' : '-translate-x-full'"
    >
      <Sidebar
        :collapsed="collapsed"
        @navigate="sidebarOpen = false"
        @toggle-collapse="collapsed = !collapsed"
      />
    </div>

    <!-- Main -->
    <div class="flex-1 flex flex-col min-w-0">
      <!-- Top bar mobile -->
      <header
        class="border-b px-4 py-2.5 flex items-center gap-3 lg:hidden
               bg-white border-gray-200
               dark:bg-brand-black-soft dark:border-zinc-800"
      >
        <button
          @click="sidebarOpen = !sidebarOpen"
          class="p-2 rounded-lg transition-colors
                 hover:bg-brand-yellow-soft text-gray-800
                 dark:hover:bg-zinc-800 dark:text-gray-100"
          aria-label="Abrir menu"
        >
          <Menu :size="22" />
        </button>
        <div class="flex items-center gap-2 min-w-0">
          <img src="/logo_favicon.svg" alt="" class="w-6 h-6 dark:hidden" />
          <img src="/logo dourado 1.svg" alt="" class="w-6 h-6 hidden dark:block" />
          <h1 class="text-sm font-bold text-gray-900 dark:text-gray-100 truncate">
            Myrias ML Manager
          </h1>
        </div>
      </header>

      <main class="flex-1 overflow-auto bg-gray-50 dark:bg-brand-black p-4 lg:p-6">
        <!-- h-full: páginas que ocupam a tela inteira (ex.: /assistente) precisam
             de uma altura definida pra resolver o `h-full` delas. -->
        <div class="max-w-[1500px] mx-auto h-full">
          <router-view v-slot="{ Component }">
            <transition name="page" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </div>
      </main>
    </div>

    <!-- Assistente de IA (flutua acima do toggle de tema) -->
    <AssistantWidget
      v-if="auth.can('assistente') && route.name !== 'assistant-chat'"
    />

    <!-- Toggle de tema: bolinha flutuante no canto inferior direito.
         Claro → bola preta/lua branca (vai pro escuro). Escuro → bola branca/sol preto (vai pro claro). -->
    <button
      @click="theme.toggle()"
      class="fixed bottom-4 right-4 z-50 w-11 h-11 rounded-full flex items-center justify-center shadow-lg ring-1 transition-colors"
      :class="theme.mode === 'dark' ? 'bg-white text-black ring-black/10 hover:bg-gray-100' : 'bg-black text-white ring-white/15 hover:bg-zinc-800'"
      :title="theme.mode === 'dark' ? 'Mudar para tema claro' : 'Mudar para tema escuro'"
      aria-label="Alternar tema"
    >
      <Sun v-if="theme.mode === 'dark'" :size="20" />
      <Moon v-else :size="20" />
    </button>
  </div>
</template>
