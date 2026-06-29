<script setup lang="ts">
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    logisticType?: string | null;
    // mostra também o selo "Padrão" (default: só FULL/Flex, mais discreto)
    showPadrao?: boolean;
  }>(),
  { logisticType: null, showPadrao: false }
);

const modality = computed(() => {
  const lt = (props.logisticType || "").toLowerCase();
  if (lt === "fulfillment") return "full";
  if (lt === "self_service") return "flex";
  return "padrao";
});
</script>

<template>
  <span
    v-if="modality === 'full'"
    class="text-[10px] font-bold uppercase px-1.5 py-0.5 rounded bg-green-100 text-green-700 tracking-wide"
    title="Mercado Livre Full (fulfillment)"
  >Full</span>
  <span
    v-else-if="modality === 'flex'"
    class="text-[10px] font-semibold uppercase px-1.5 py-0.5 rounded bg-blue-100 text-blue-700 tracking-wide"
    title="Mercado Envios Flex"
  >Flex</span>
  <span
    v-else-if="showPadrao"
    class="text-[10px] font-medium uppercase px-1.5 py-0.5 rounded bg-gray-100 text-gray-500 tracking-wide"
    title="Envio padrão (coleta/agência)"
  >Padrão</span>
</template>
