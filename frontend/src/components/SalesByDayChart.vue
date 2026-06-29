<script setup lang="ts">
import { computed } from "vue";
import type { SalesResponse } from "@/api/dashboard";

const props = defineProps<{ data: SalesResponse; maxRows?: number }>();

const ACCOUNT_COLORS = ["#3483FA", "#9333EA", "#059669", "#EA580C"];

const accountColorMap = computed(() => {
  const map = new Map<number, string>();
  props.data.accounts.forEach((a, i) => {
    map.set(a.user_id, ACCOUNT_COLORS[i % ACCOUNT_COLORS.length]);
  });
  return map;
});

const rows = computed(() =>
  props.data.items.slice(0, props.maxRows ?? 20)
);

const maxQty = computed(() => {
  let max = 0;
  for (const r of rows.value) if (r.quantity > max) max = r.quantity;
  return max || 1;
});

function pct(q: number): number {
  return (q / maxQty.value) * 100;
}

function formatBRL(v: number): string {
  return v.toLocaleString("pt-BR", {
    style: "currency",
    currency: "BRL",
    maximumFractionDigits: 2,
  });
}
</script>

<template>
  <div>
    <div v-if="rows.length === 0" class="text-sm text-gray-500 text-center py-6">
      Nenhuma venda registrada nessa data.
    </div>

    <div v-else class="space-y-2">
      <div
        v-for="r in rows"
        :key="`${r.account.user_id}-${r.sku}-${r.item_id}`"
        class="grid grid-cols-[minmax(0,10rem)_1fr_auto] items-center gap-3"
      >
        <!-- SKU + título -->
        <div class="min-w-0">
          <p class="text-sm font-mono font-medium text-gray-900 truncate" :title="r.sku">
            {{ r.sku }}
          </p>
          <p v-if="r.title" class="text-[10px] text-gray-400 truncate" :title="r.title">
            {{ r.title }}
          </p>
        </div>

        <!-- Barra + conta -->
        <div class="flex items-center gap-2">
          <div class="flex-1 h-5 bg-gray-100 rounded-full overflow-hidden">
            <div
              class="h-full rounded-full transition-all flex items-center justify-end px-2"
              :style="{
                width: pct(r.quantity) + '%',
                background: accountColorMap.get(r.account.user_id) || '#9CA3AF',
              }"
            />
          </div>
          <span
            class="text-[10px] uppercase font-semibold px-1.5 py-0.5 rounded-full flex-shrink-0"
            :style="{
              background: (accountColorMap.get(r.account.user_id) || '#9CA3AF') + '22',
              color: accountColorMap.get(r.account.user_id) || '#6B7280',
            }"
          >
            {{ r.account.nickname }}
          </span>
        </div>

        <!-- Quantidade + receita -->
        <div class="text-right flex-shrink-0">
          <p class="text-sm font-bold text-gray-900">{{ r.quantity }}</p>
          <p class="text-[10px] text-gray-500">{{ formatBRL(r.revenue) }}</p>
        </div>
      </div>
    </div>

    <p
      v-if="data.items.length > rows.length"
      class="text-[11px] text-gray-400 text-center mt-2"
    >
      + {{ data.items.length - rows.length }} outros SKUs vendidos
    </p>
  </div>
</template>
