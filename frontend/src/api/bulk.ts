import api from "./client";
import type { MeliItem } from "@/types/item";

export interface BulkResult {
  total: number;
  success: number;
  errors: Array<{
    item_id: string;
    status?: number;
    error: unknown;
  }>;
  warnings?: Array<{
    item_id: string;
    skipped_fields: string[];
  }>;
  accounts?: Array<{
    user_id: number;
    total: number;
    success: number;
    errors: Array<{ item_id: string; error: unknown }>;
    warnings?: Array<{ item_id: string; skipped_fields: string[] }>;
  }>;
  message?: string;
}

export interface AccountItemsGroup {
  user_id: number;
  nickname: string;
  items: MeliItem[];
  error?: string;
}

export async function getItemsBySkuAllAccounts(
  sku: string
): Promise<AccountItemsGroup[]> {
  const { data } = await api.get<{ groups: AccountItemsGroup[] }>(
    `/bulk/sku/${encodeURIComponent(sku)}/all`
  );
  return data.groups;
}

export async function bulkUpdateMulti(
  groups: Array<{ user_id: number; item_ids: string[] }>,
  updates: Record<string, unknown>,
  sku?: string,
  opts?: {
    titles?: Record<string, string>;
    batchId?: string;
    // item_id -> { campo: valor_antigo } — pro histórico mostrar "antes → depois".
    before?: Record<string, Record<string, unknown>>;
  }
): Promise<BulkResult> {
  const { data } = await api.post<BulkResult>("/bulk/update-multi", {
    groups,
    updates,
    sku,
    titles: opts?.titles,
    before: opts?.before,
    batch_id: opts?.batchId,
  });
  return data;
}

// ---------------------------------------------------------------------------
// Compatibilidades de autopeças (veículos MLB-CARS_AND_VANS)
// ---------------------------------------------------------------------------

export interface VehicleCompatibility {
  product_id: string;
  domain_id: string;
  name: string;
  attributes: Array<{ id: string; name: string; value_name?: string }>;
  // "Posição" da peça no ML (campo `note`): "Dianteiro Esquerdo" etc.
  note?: string | null;
}

export interface ItemCompatibility {
  id?: string;
  product_id?: string;
  domain_id?: string;
  name?: string;
  attributes?: Array<{ id: string; name: string; value_name?: string }>;
  note?: string | null;
}

export interface ItemCompatibilitiesResponse {
  compatibilities: ItemCompatibility[];
  positions: Array<{ id: string; value_id: string; value_name?: string }>;
}

export async function getItemCompatibilities(
  itemId: string
): Promise<ItemCompatibilitiesResponse> {
  const { data } = await api.get<ItemCompatibilitiesResponse>(
    `/bulk/compatibilities/item/${encodeURIComponent(itemId)}`
  );
  return data;
}

export interface PositionAttribute {
  id: string;
  name: string;
  values: Array<{ id: string; name: string }>;
}

export async function getPositionAttributes(
  categoryId: string
): Promise<PositionAttribute[]> {
  const { data } = await api.get<PositionAttribute[]>(
    `/bulk/compatibilities/position-attributes/${encodeURIComponent(categoryId)}`
  );
  return data;
}

export interface ItemPackageInfo {
  item_id: string;
  shipping_dimensions: string | null;
  package_attributes: Array<{
    id: string;
    name?: string;
    value_name: string | null;
    value_struct?: { number: number; unit: string } | null;
  }>;
}

export async function getItemDescription(itemId: string): Promise<string> {
  const { data } = await api.get<{ item_id: string; plain_text: string }>(
    `/bulk/description/item/${encodeURIComponent(itemId)}`
  );
  return data.plain_text ?? "";
}

export async function bulkUpdateDescriptionMulti(
  groups: Array<{ user_id: number; item_ids: string[] }>,
  description: string,
  sku?: string,
  batchId?: string
): Promise<BulkResult> {
  const { data } = await api.post<BulkResult>("/bulk/description/update-multi", {
    groups,
    description,
    sku,
    batch_id: batchId,
  });
  return data;
}

export async function getItemPackageInfo(itemId: string): Promise<ItemPackageInfo> {
  const { data } = await api.get<ItemPackageInfo>(
    `/bulk/debug/item-package/${encodeURIComponent(itemId)}`
  );
  return data;
}


export async function bulkUpdatePositions(
  groups: Array<{ user_id: number; item_ids: string[] }>,
  positions: Array<{ attribute_id: string; value_id: string; value_name?: string }>,
  sku?: string,
  batchId?: string
): Promise<BulkResult> {
  const { data } = await api.post<BulkResult>(
    "/bulk/positions/update",
    { groups, positions, sku, batch_id: batchId },
    { timeout: 10 * 60 * 1000 }
  );
  return data;
}

export async function bulkUpdateCompatibilities(
  groups: Array<{ user_id: number; item_ids: string[] }>,
  productIds: string[],
  mode: "replace" | "append",
  sku?: string,
  vehicleNames?: string[],
  notes?: Array<string | null>,
  positions?: Array<{ id: string; value_id: string }>,
  batchId?: string
): Promise<BulkResult> {
  // Operação bulk com muitos veículos pode levar minutos (DELETE+POST por veículo
  // em cada anúncio destino). Sobrescreve o timeout default de 30s.
  const { data } = await api.post<BulkResult>(
    "/bulk/compatibilities/update",
    {
      groups,
      product_ids: productIds,
      mode,
      sku,
      vehicle_names: vehicleNames,
      notes,
      positions,
      batch_id: batchId,
    },
    { timeout: 10 * 60 * 1000 } // 10 min
  );
  return data;
}
