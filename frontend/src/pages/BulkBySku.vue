<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { Search, PencilLine } from "lucide-vue-next";

const router = useRouter();
const query = ref("");

function goToEdit() {
  const sku = query.value.trim();
  if (sku) router.push(`/bulk/sku/${encodeURIComponent(sku)}`);
}
</script>

<template>
  <div>
    <div class="mb-6">
      <p class="text-[11px] font-bold uppercase tracking-[0.22em] text-gray-500 mb-0.5">
        Edição em massa
      </p>
      <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight">Edição por SKU</h2>
      <p class="text-sm text-gray-500 mt-1 max-w-xl">
        Digite o SKU para editar todos os anúncios associados em todas as contas
      </p>
    </div>

    <div class="bg-white rounded-2xl border shadow-sm p-5 max-w-2xl">
      <form class="flex gap-2" @submit.prevent="goToEdit">
        <div class="relative flex-1">
          <Search :size="16" class="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
          <input
            v-model="query"
            type="text"
            placeholder="Digite o código SKU..."
            autofocus
            class="w-full pl-9 pr-3 py-2.5 border rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-brand-yellow focus:border-transparent bg-gray-50 transition-shadow dark:bg-zinc-900 dark:border-zinc-700 dark:text-gray-100"
          />
        </div>
        <button
          type="submit"
          :disabled="!query.trim()"
          class="px-5 py-2.5 bg-meli-blue text-brand-yellow rounded-xl text-sm font-semibold hover:bg-meli-blue-dark disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 transition-all hover:shadow-md"
        >
          <Search :size="15" />
          Editar
        </button>
      </form>
      <p class="text-xs text-gray-400 mt-3 flex items-center gap-1.5">
        <PencilLine :size="13" />
        A edição afeta preço, estoque, atributos e medidas de todos os anúncios do SKU.
      </p>
    </div>
  </div>
</template>
