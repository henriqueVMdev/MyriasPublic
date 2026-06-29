<script setup lang="ts">
import { computed } from "vue";
import { Truck } from "lucide-vue-next";
import type { ModalityBreakdown } from "@/api/performance";

const props = withDefaults(
  defineProps<{
    data?: ModalityBreakdown;
    title?: string;
    // false = dado vem do ANÚNCIO (só dá Full vs Não-Full, pois Flex é por entrega)
    // true  = dado vem do PEDIDO/entrega real (Full / Flex / Padrão separados)
    byOrder?: boolean;
  }>(),
  { title: "Vendas por modalidade de entrega", byOrder: false }
);

interface Row { label: string; qty: number; count: number; bar: string; text: string }

const rows = computed<Row[]>(() => {
  const d = props.data;
  if (!d) return [];
  if (props.byOrder) {
    return [
      { label: "Full",   qty: d.full.qty,   count: d.full.count,   bar: "bg-green-500", text: "text-green-700" },
      { label: "Flex",   qty: d.flex.qty,   count: d.flex.count,   bar: "bg-blue-500",  text: "text-blue-700" },
      { label: "Padrão", qty: d.padrao.qty, count: d.padrao.count, bar: "bg-gray-400",  text: "text-gray-600" },
    ];
  }
  // Nível do anúncio: Full é confiável; o resto é "Não-Full" (Flex/Padrão juntos,
  // porque a entrega Flex só é decidida no pedido).
  return [
    { label: "Full",     qty: d.full.qty, count: d.full.count, bar: "bg-green-500", text: "text-green-700" },
    {
      label: "Não-Full",
      qty: d.flex.qty + d.padrao.qty,
      count: d.flex.count + d.padrao.count,
      bar: "bg-gray-400",
      text: "text-gray-600",
    },
  ];
});

const total = computed(() => rows.value.reduce((s, r) => s + r.qty, 0));
function pct(qty: number): number {
  return total.value > 0 ? (qty / total.value) * 100 : 0;
}
</script>

<template>
  <div class="bg-white rounded-xl border shadow-sm p-4">
    <h3 class="text-sm font-semibold text-gray-700 mb-1 flex items-center gap-1.5">
      <Truck :size="15" class="text-meli-blue" /> {{ title }}
    </h3>
    <p v-if="!byOrder" class="text-[11px] text-gray-400 mb-3">
      Por anúncio: Full é confiável; entrega Flex é definida por pedido (não aparece aqui).
    </p>
    <div v-if="!data || total === 0" class="text-sm text-gray-400 py-2">
      Sem vendas no escopo.
    </div>
    <div v-else class="space-y-2">
      <div v-for="r in rows" :key="r.label" class="flex items-center gap-2">
        <span class="w-16 text-xs font-medium" :class="r.text">{{ r.label }}</span>
        <div class="flex-1 bg-gray-100 rounded-full h-4 overflow-hidden">
          <div class="h-full rounded-full" :class="r.bar" :style="{ width: pct(r.qty) + '%' }"></div>
        </div>
        <span class="w-28 text-right text-xs tabular-nums text-gray-600">
          <span class="font-semibold text-gray-800">{{ r.qty.toLocaleString("pt-BR") }}</span>
          ({{ pct(r.qty).toFixed(0) }}%) · {{ r.count }} anún.
        </span>
      </div>
    </div>
  </div>
</template>
