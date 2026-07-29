<script setup lang="ts">
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    /** Texto animado no centro do orbe — uma letra por vez. */
    text?: string;
    /**
     * Escala o loader inteiro: toda a geometria é em `em`.
     * Deixe vazio para escalar por classe (ex.: `class="text-xs lg:text-base"`) —
     * `style` inline venceria a classe, então só emitimos quando informado.
     */
    size?: string;
  }>(),
  { text: "Generating", size: undefined },
);

/** Uma letra por span — cada uma entra na onda com seu próprio atraso. */
const letters = computed(() => Array.from(props.text));
</script>

<template>
  <div
    class="loader-wrapper"
    :style="props.size ? { fontSize: props.size } : undefined"
    role="status"
    :aria-label="props.text"
  >
    <span
      v-for="(letter, i) in letters"
      :key="`${letter}-${i}`"
      class="loader-letter"
      :style="{ animationDelay: `${i * 0.1}s` }"
      aria-hidden="true"
      >{{ letter === " " ? " " : letter }}</span
    >

    <div class="loader" aria-hidden="true"></div>
  </div>
</template>
