<script setup lang="ts">
// Uma métrica dentro do <MetricStrip>: rótulo em cima, valor (slot) embaixo,
// tudo centralizado e sem ícone. Vira <button> quando `clickable` (mantém o
// comportamento de filtro/navegação que alguns cards têm).
defineProps<{ label?: string; clickable?: boolean }>();
const emit = defineEmits<{ (e: "click"): void }>();
</script>

<template>
  <component
    :is="clickable ? 'button' : 'div'"
    :type="clickable ? 'button' : undefined"
    class="px-4 py-7 text-center w-full h-full transition-colors"
    :class="clickable ? 'cursor-pointer hover:bg-black/[0.03] dark:hover:bg-white/[0.04]' : ''"
    @click="clickable && emit('click')"
  >
    <div class="text-gray-500 text-[11px] font-bold uppercase tracking-wider mb-1.5">
      <slot name="label">{{ label }}</slot>
    </div>
    <slot />
  </component>
</template>
