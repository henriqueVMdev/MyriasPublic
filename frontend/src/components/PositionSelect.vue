<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from "vue";
import { ChevronDown, Check, X } from "lucide-vue-next";

interface Option {
  id: string;
  name: string;
}

const props = defineProps<{
  modelValue: string[];
  options: Option[];
  placeholder?: string;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: string[]];
}>();

const open = ref(false);
const wrapperRef = ref<HTMLElement | null>(null);

const selectedNames = computed(() =>
  props.modelValue
    .map((id) => props.options.find((o) => o.id === id)?.name || id)
    .filter(Boolean)
);

const displayText = computed(() => {
  if (selectedNames.value.length === 0) return props.placeholder || "— não definir —";
  if (selectedNames.value.length === 1) return selectedNames.value[0];
  return selectedNames.value.join(" + ");
});

function toggle() {
  open.value = !open.value;
}

function pick(id: string) {
  const current = props.modelValue;
  const idx = current.indexOf(id);
  if (idx === -1) {
    emit("update:modelValue", [...current, id]);
  } else {
    emit("update:modelValue", current.filter((v) => v !== id));
  }
  // Não fecha — permite selecionar múltiplos em sequência
}

function clearAll() {
  emit("update:modelValue", []);
  open.value = false;
}

function onClickOutside(e: MouseEvent) {
  if (!wrapperRef.value) return;
  if (!wrapperRef.value.contains(e.target as Node)) open.value = false;
}

function onEscape(e: KeyboardEvent) {
  if (e.key === "Escape") open.value = false;
}

onMounted(() => {
  document.addEventListener("mousedown", onClickOutside);
  document.addEventListener("keydown", onEscape);
});

onUnmounted(() => {
  document.removeEventListener("mousedown", onClickOutside);
  document.removeEventListener("keydown", onEscape);
});
</script>

<template>
  <div ref="wrapperRef" class="relative">
    <button
      type="button"
      @click="toggle"
      class="w-full flex items-center justify-between gap-2 px-3 py-2 rounded-lg text-sm
             border transition-colors text-left
             bg-white hover:border-meli-blue
             border-gray-200 text-gray-900
             dark:bg-zinc-900 dark:hover:border-meli-blue
             dark:border-zinc-700 dark:text-gray-100
             focus:outline-none focus:ring-2 focus:ring-meli-blue"
      :class="[
        open ? 'border-meli-blue ring-2 ring-meli-blue/40' : '',
        modelValue.length > 0 ? 'border-meli-blue' : '',
      ]"
    >
      <span
        :class="modelValue.length > 0 ? 'text-gray-900 dark:text-gray-100' : 'text-gray-400 dark:text-gray-500'"
        class="truncate flex-1"
      >
        {{ displayText }}
      </span>
      <span v-if="modelValue.length > 1" class="flex-shrink-0 text-[10px] font-semibold bg-meli-blue text-white rounded-full px-1.5 py-0.5">
        {{ modelValue.length }}
      </span>
      <button
        v-if="modelValue.length > 0"
        type="button"
        @click.stop="clearAll"
        class="flex-shrink-0 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
        title="Limpar seleção"
      >
        <X :size="13" />
      </button>
      <ChevronDown
        v-else
        :size="16"
        class="flex-shrink-0 transition-transform text-gray-400"
        :class="open ? 'rotate-180' : ''"
      />
    </button>

    <div
      v-if="open"
      class="absolute z-30 mt-1 w-full rounded-lg shadow-lg overflow-hidden border
             bg-white border-gray-200
             dark:bg-zinc-900 dark:border-zinc-700"
    >
      <div class="max-h-60 overflow-y-auto py-1">
        <button
          v-for="opt in options"
          :key="opt.id"
          type="button"
          @click="pick(opt.id)"
          class="w-full px-3 py-2 text-left text-sm flex items-center gap-2 transition-colors
                 text-gray-900 hover:bg-gray-100
                 dark:text-gray-100 dark:hover:bg-zinc-800"
          :class="modelValue.includes(opt.id) ? 'bg-blue-50 dark:bg-zinc-800 font-medium' : ''"
        >
          <span
            class="w-4 h-4 rounded border flex items-center justify-center flex-shrink-0"
            :class="modelValue.includes(opt.id)
              ? 'bg-meli-blue border-meli-blue'
              : 'border-gray-300 dark:border-zinc-600'"
          >
            <Check v-if="modelValue.includes(opt.id)" :size="11" class="text-white" />
          </span>
          {{ opt.name }}
        </button>
      </div>
      <div v-if="modelValue.length > 0" class="border-t border-gray-100 dark:border-zinc-700 px-3 py-1.5">
        <button
          type="button"
          @click="clearAll"
          class="text-xs text-gray-400 hover:text-red-500 transition-colors"
        >
          Limpar seleção
        </button>
      </div>
    </div>
  </div>
</template>
