<script setup lang="ts">
// Input de um atributo de categoria do ML, renderizado pelo value_type:
// number_unit → número + unidade; list fechada → SelectMenu; list com
// allow_custom_value → combobox; number → input numérico (ML rejeita texto);
// default → texto livre. Único renderizador — usado pelo BulkEdit e pelo
// QualityFixModal pra não haver dois editores divergindo.
import { computed, ref } from "vue";
import SelectMenu from "@/components/SelectMenu.vue";
import type { CategoryAttribute } from "@/api/items";
import { attrValueFilled, type AttrValue } from "@/lib/attrValues";

const props = defineProps<{
  attr: CategoryAttribute;
  modelValue?: AttrValue;
  size?: "sm" | "md";
}>();

const emit = defineEmits<{ "update:modelValue": [value: AttrValue] }>();

// Busca do combobox — estado local do input (undefined = dropdown fechado)
const search = ref<string | undefined>(undefined);

const pad = computed(() => (props.size === "sm" ? "px-2 py-1.5" : "px-3 py-2"));
const filled = computed(() => attrValueFilled(props.modelValue));
const inputClass = computed(() => [
  pad.value,
  filled.value ? "border-blue-300 bg-blue-50/40" : "border-gray-200",
]);

const searchResults = computed(() => {
  const needle = (search.value || "").toLowerCase();
  return props.attr.values
    .filter((v) => !needle || v.name.toLowerCase().includes(needle))
    .slice(0, 20);
});

function setValue(valueId: string | null, valueName: string | null) {
  emit("update:modelValue", { ...(props.modelValue || {}), value_id: valueId, value_name: valueName });
}

function setNumberUnit(number: number | null, unit: string | null) {
  const value_name =
    number !== null && unit ? `${number} ${unit}` : number !== null ? String(number) : null;
  emit("update:modelValue", { value_id: null, value_name, number, unit });
}

function onNumberUnitInput(e: Event) {
  const raw = (e.target as HTMLInputElement).value;
  const n = raw === "" ? null : parseFloat(raw.replace(",", "."));
  setNumberUnit(
    n !== null && !isNaN(n) ? n : null,
    props.modelValue?.unit || props.attr.default_unit || null
  );
}

function closeSearch() {
  // Atraso pra permitir o @mousedown da opção rodar antes do dropdown sumir.
  setTimeout(() => {
    search.value = undefined;
  }, 200);
}
</script>

<template>
  <!-- number_unit -->
  <div v-if="attr.value_type === 'number_unit'" class="flex gap-1">
    <input
      :value="modelValue?.number ?? ''"
      @input="onNumberUnitInput"
      type="number" step="any" min="0" placeholder="0"
      class="flex-1 min-w-0 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
      :class="inputClass"
    />
    <select
      v-if="(attr.allowed_units?.length ?? 0) > 1"
      :value="modelValue?.unit || attr.default_unit || ''"
      @change="(e) => setNumberUnit(modelValue?.number ?? null, (e.target as HTMLSelectElement).value)"
      class="px-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-meli-blue bg-white"
      :class="size === 'sm' ? 'py-1.5 text-xs' : 'py-2 text-sm'"
    >
      <option v-for="u in attr.allowed_units" :key="u.id" :value="u.id">{{ u.name }}</option>
    </select>
    <span
      v-else
      class="text-xs text-gray-500 bg-gray-100 rounded-lg flex items-center whitespace-nowrap"
      :class="pad"
    >
      {{ modelValue?.unit || attr.default_unit || "—" }}
    </span>
  </div>

  <!-- dropdown fixo -->
  <SelectMenu
    v-else-if="attr.values.length > 0 && !attr.tags.allow_custom_value"
    :model-value="modelValue?.value_id || ''"
    :options="attr.values.map((v) => ({ value: v.id, label: v.name }))"
    :size="size"
    empty-label="— Selecionar —"
    @update:model-value="(id) => { const sel = attr.values.find((v) => v.id === id); setValue(sel?.id || null, sel?.name || null); }"
  />

  <!-- combobox: values + texto livre -->
  <div v-else-if="attr.values.length > 0 && attr.tags.allow_custom_value" class="relative">
    <input
      :value="modelValue?.value_name || ''"
      @input="(e) => { const val = (e.target as HTMLInputElement).value; setValue(null, val); search = val; }"
      @focus="search = modelValue?.value_name || ''"
      @blur="closeSearch"
      placeholder="Digitar ou selecionar..."
      class="w-full border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
      :class="inputClass"
    />
    <div
      v-if="search !== undefined"
      class="absolute z-10 w-full mt-1 bg-white border rounded-lg shadow-lg max-h-48 overflow-y-auto"
    >
      <button
        v-for="v in searchResults"
        :key="v.id"
        @mousedown.prevent="setValue(v.id, v.name); search = undefined"
        class="w-full text-left px-3 py-2 text-sm hover:bg-blue-50 truncate"
      >{{ v.name }}</button>
    </div>
  </div>

  <!-- number puro: ML rejeita texto — força input numérico -->
  <input
    v-else-if="attr.value_type === 'number'"
    :value="modelValue?.value_name || ''"
    @input="(e) => setValue(null, (e.target as HTMLInputElement).value)"
    type="number" step="any" :placeholder="attr.name"
    class="w-full border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
    :class="inputClass"
  />

  <!-- texto livre -->
  <input
    v-else
    :value="modelValue?.value_name || ''"
    @input="(e) => setValue(null, (e.target as HTMLInputElement).value)"
    type="text" :placeholder="attr.name"
    class="w-full border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
    :class="inputClass"
  />
</template>
