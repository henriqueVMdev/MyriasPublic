<script setup lang="ts">
import { useRouter } from "vue-router";

const router = useRouter();
</script>

<template>
  <div class="min-h-screen flex items-center justify-center p-4 bg-gradient-to-br from-brand-yellow via-brand-yellow-light to-white">
    <div class="max-w-2xl w-full bg-white rounded-2xl shadow-xl p-8 space-y-6">
      <header class="flex items-center gap-4 border-b pb-5">
        <img src="/MyriasDarkLogo.png" alt="Myrias Imports" class="h-16 w-auto flex-shrink-0 dark:hidden" />
        <img src="/MyriasYellowLogo.png" alt="Myrias Imports" class="h-16 w-auto flex-shrink-0 hidden dark:block" />
        <div>
          <h1 class="text-2xl font-bold text-gray-900">Myrias Imports</h1>
          <p class="text-sm text-gray-500">Painel interno de gestão</p>
        </div>
      </header>

      <section class="space-y-3 text-gray-700 text-sm leading-relaxed">
        <p>
          Este endereço hospeda o <strong>painel administrativo interno da Myrias Imports</strong>,
          utilizado exclusivamente pelos colaboradores autorizados da empresa para
          tarefas operacionais de catálogo e atendimento.
        </p>
        <p>
          O sistema <strong>não realiza vendas, não recebe pedidos do público e não
          processa pagamentos</strong>. Todo o conteúdo é restrito por autenticação e
          requer credenciais corporativas válidas.
        </p>
        <p>
          Se você chegou aqui por engano ou suspeita de uso indevido deste domínio,
          entre em contato pelo e-mail abaixo. Agradecemos o aviso.
        </p>
      </section>

      <section class="bg-gray-50 rounded-xl p-5 space-y-2 text-sm">
        <h2 class="font-semibold text-gray-900">Contato</h2>
        <p class="text-gray-700">
          E-mail:
          <a href="mailto:assist88myrias@outlook.com" class="text-meli-blue underline hover:no-underline">
            assist88myrias@outlook.com
          </a>
        </p>
        <p class="text-gray-700">
          Razão social: Myrias Imports — uso interno
        </p>
      </section>

      <footer class="flex items-center justify-between pt-4 border-t text-xs text-gray-400">
        <span>&copy; {{ new Date().getFullYear() }} Myrias Imports</span>
        <button
          type="button"
          @click="router.push('/login')"
          class="px-3 py-1.5 rounded-lg bg-brand-black text-brand-yellow text-xs font-medium hover:bg-brand-black-soft"
        >
          Acesso de colaboradores
        </button>
      </footer>
    </div>
  </div>
</template>
