// Valor editável de um atributo de categoria do ML — compartilhado entre
// BulkEdit e QualityFixModal via o componente AttributeInput.
import type { CategoryAttribute } from "@/api/items";

export interface AttrValue {
  value_id: string | null;
  value_name: string | null;
  // Para value_type=number_unit: ML exige unit (default_unit) ou ignora silenciosamente.
  number?: number | null;
  unit?: string | null;
}

// Atributo como vem do item do ML (value_struct só existe em alguns retornos).
export interface CurrentAttr {
  id: string;
  value_id?: string | null;
  value_name?: string | null;
  value_struct?: { number?: number | null; unit?: string | null } | null;
}

function parseNumberUnit(
  raw: string | null | undefined,
  defaultUnit: string | null | undefined
): { number: number | null; unit: string | null } {
  if (!raw) return { number: null, unit: defaultUnit || null };
  const m = String(raw).trim().match(/^(-?\d+(?:[.,]\d+)?)\s*([A-Za-z%º°]+)?$/);
  if (!m) return { number: null, unit: defaultUnit || null };
  const num = parseFloat(m[1].replace(",", "."));
  const unit = m[2] || defaultUnit || null;
  return { number: isNaN(num) ? null : num, unit };
}

// Monta o estado inicial de edição a partir dos atributos atuais do item.
export function initAttrValues(
  categoryAttrs: CategoryAttribute[],
  currentAttrs: CurrentAttr[]
): Record<string, AttrValue> {
  const currentMap: Record<string, CurrentAttr> = {};
  for (const attr of currentAttrs) currentMap[attr.id] = attr;
  const values: Record<string, AttrValue> = {};
  for (const ca of categoryAttrs) {
    const current = currentMap[ca.id];
    const v: AttrValue = {
      value_id: current?.value_id || null,
      value_name: current?.value_name || null,
    };
    if (ca.value_type === "number_unit") {
      const struct = current?.value_struct;
      if (struct && typeof struct === "object") {
        v.number = typeof struct.number === "number" ? struct.number : null;
        v.unit = struct.unit || ca.default_unit || null;
      } else {
        const parsed = parseNumberUnit(current?.value_name, ca.default_unit);
        v.number = parsed.number;
        v.unit = parsed.unit;
      }
    }
    values[ca.id] = v;
  }
  return values;
}

export function attrValueFilled(v: AttrValue | undefined): boolean {
  if (!v) return false;
  return !!(v.value_id || v.value_name || (v.number !== null && v.number !== undefined));
}
