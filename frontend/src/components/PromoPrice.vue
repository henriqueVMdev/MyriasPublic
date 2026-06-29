<script setup lang="ts">
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    price: number | null;
    original?: number | null;
  }>(),
  { original: null }
);

function fmt(v: number | null): string {
  if (v == null) return "—";
  return v.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}

// Promoção ativa: existe preço "de" maior que o preço atual.
const onSale = computed(
  () => props.original != null && props.price != null && props.original > props.price
);
</script>

<template>
  <span class="inline-flex items-baseline gap-1 justify-end">
    <span v-if="onSale" class="text-[11px] text-gray-400 line-through">{{ fmt(original) }}</span>
    <span :class="onSale ? 'text-green-600 font-medium' : ''">{{ fmt(price) }}</span>
  </span>
</template>
