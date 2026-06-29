<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from "vue";
import { ChevronDown, Check } from "lucide-vue-next";

interface Option {
  value: string;
  label: string;
}

interface OptionGroup {
  label: string;
  options: Option[];
}

const props = defineProps<{
  modelValue: string | undefined;
  options?: Option[];
  groups?: OptionGroup[];
  placeholder?: string;
  emptyLabel?: string;        // Label da opção "vazia" (default: "Todos")
  hideEmpty?: boolean;        // Se true, não mostra a opção "vazia"
  disabled?: boolean;
  size?: "sm" | "md";         // Compactação (px/py)
  align?: "left" | "right";   // Alinhamento do popup
}>();

const emit = defineEmits<{
  "update:modelValue": [value: string];
}>();

const open = ref(false);
const wrapperRef = ref<HTMLElement | null>(null);

const flatOptions = computed<Option[]>(() => {
  if (props.groups) return props.groups.flatMap((g) => g.options);
  return props.options || [];
});

const selected = computed(() =>
  flatOptions.value.find((o) => o.value === props.modelValue) || null
);

const displayText = computed(() => {
  if (selected.value) return selected.value.label;
  return props.placeholder || props.emptyLabel || "Todos";
});

function toggle() {
  if (props.disabled) return;
  open.value = !open.value;
}

function pick(value: string) {
  emit("update:modelValue", value);
  open.value = false;
}

function clear() {
  emit("update:modelValue", "");
  open.value = false;
}

function onClickOutside(e: MouseEvent) {
  if (!wrapperRef.value) return;
  if (!wrapperRef.value.contains(e.target as Node)) {
    open.value = false;
  }
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

const sizeClasses = computed(() => {
  return props.size === "sm" ? "px-2 py-1.5 text-xs" : "px-3 py-2 text-sm";
});
</script>

<template>
  <div ref="wrapperRef" class="relative">
    <button
      type="button"
      @click="toggle"
      :disabled="disabled"
      class="w-full flex items-center justify-between gap-2 rounded-lg border transition-colors text-left
             bg-white hover:border-meli-blue
             border-gray-200 text-gray-900
             dark:bg-zinc-900 dark:hover:border-meli-blue
             dark:border-zinc-700 dark:text-gray-100
             focus:outline-none focus:ring-2 focus:ring-meli-blue
             disabled:opacity-50 disabled:cursor-not-allowed"
      :class="[
        sizeClasses,
        open ? 'border-meli-blue ring-2 ring-meli-blue/40' : '',
      ]"
    >
      <span
        :class="selected ? 'text-gray-900 dark:text-gray-100' : 'text-gray-400 dark:text-gray-500'"
        class="truncate flex-1"
      >
        {{ displayText }}
      </span>
      <ChevronDown
        :size="14"
        class="flex-shrink-0 transition-transform text-gray-400"
        :class="open ? 'rotate-180' : ''"
      />
    </button>

    <div
      v-if="open"
      class="absolute z-30 mt-1 min-w-full rounded-lg shadow-lg overflow-hidden border
             bg-white border-gray-200
             dark:bg-zinc-900 dark:border-zinc-700"
      :class="align === 'right' ? 'right-0' : 'left-0'"
    >
      <div class="max-h-60 overflow-y-auto py-1 min-w-[14rem]">
        <button
          v-if="!hideEmpty"
          type="button"
          @click="clear"
          class="w-full px-3 py-2 text-left text-sm flex items-center gap-2 transition-colors
                 text-gray-500 italic
                 hover:bg-gray-100
                 dark:text-gray-400 dark:hover:bg-zinc-800"
          :class="!selected ? 'bg-gray-50 dark:bg-zinc-800' : ''"
        >
          <Check
            :size="14"
            :class="!selected ? 'text-meli-blue' : 'invisible'"
          />
          {{ emptyLabel || "Todos" }}
        </button>

        <!-- Opções planas -->
        <template v-if="options && !groups">
          <button
            v-for="opt in options"
            :key="opt.value"
            type="button"
            @click="pick(opt.value)"
            class="w-full px-3 py-2 text-left text-sm flex items-center gap-2 transition-colors
                   text-gray-900
                   hover:bg-gray-100
                   dark:text-gray-100 dark:hover:bg-zinc-800"
            :class="selected?.value === opt.value ? 'bg-blue-50 dark:bg-zinc-800 font-medium' : ''"
          >
            <Check
              :size="14"
              :class="selected?.value === opt.value ? 'text-meli-blue' : 'invisible'"
            />
            <span class="truncate">{{ opt.label }}</span>
          </button>
        </template>

        <!-- Opções agrupadas -->
        <template v-if="groups">
          <div v-for="g in groups" :key="g.label">
            <div class="px-3 py-1 text-[10px] uppercase tracking-wide font-semibold text-gray-400 dark:text-gray-500 bg-gray-50 dark:bg-zinc-950">
              {{ g.label }}
            </div>
            <button
              v-for="opt in g.options"
              :key="opt.value"
              type="button"
              @click="pick(opt.value)"
              class="w-full px-3 py-2 text-left text-sm flex items-center gap-2 transition-colors
                     text-gray-900
                     hover:bg-gray-100
                     dark:text-gray-100 dark:hover:bg-zinc-800"
              :class="selected?.value === opt.value ? 'bg-blue-50 dark:bg-zinc-800 font-medium' : ''"
            >
              <Check
                :size="14"
                :class="selected?.value === opt.value ? 'text-meli-blue' : 'invisible'"
              />
              <span class="truncate">{{ opt.label }}</span>
            </button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>
