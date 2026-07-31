import api from "./client";

export interface CloneCompatibility {
  product_id?: string;
  id?: string;
  domain_id?: string;
  name?: string;
  note?: string | null;
  positions?: Array<{ id: string; value_id: string }>;
}

export interface ClonePreview {
  original: {
    id: string;
    title: string;
    price: number;
    category_id: string;
    condition: string;
    pictures: Array<{ id: string; secure_url: string; url: string }>;
    attributes: Array<{ id: string; name: string; value_name: string | null }>;
    seller_id: number;
    permalink: string;
    available_quantity: number;
    sold_quantity: number;
    listing_type_id: string;
    shipping: Record<string, unknown>;
    sale_terms: Array<Record<string, unknown>>;
    variations: Array<Record<string, unknown>>;
    compatibilities?: CloneCompatibility[];
  };
  suggested: {
    title: string;
    category_id: string;
    condition: string;
    price: number;
    available_quantity: number;
    currency_id: string;
    buying_mode: string;
    listing_type_id: string;
    pictures: Array<{ source: string }>;
    attributes: Array<{ id: string; name?: string; value_id?: string; value_name?: string }>;
    sale_terms: Array<Record<string, unknown>>;
    shipping?: { local_pick_up?: boolean };
    description: string;
    channels: string[];
    compatibilities?: CloneCompatibility[];
    // Posições copiadas do anúncio-fonte, já traduzidas pros value_ids do POSITION.
    // Para anúncios de outras contas/concorrentes, vem [] (sem acesso ao user-product fonte).
    position_restrictions?: Array<{ value_id: string; value_name: string }>;
  };
}

export async function getClonePreview(itemId: string): Promise<ClonePreview> {
  const { data } = await api.post<ClonePreview>("/clone/preview", {
    item_id: itemId,
  }, { timeout: 150_000 }); // cobre fallback Playwright p/ anúncio de concorrente (API 403 + antibot)
  return data;
}

export interface CloneMultiResult {
  total: number;
  success: number;
  confirmed?: number;
  pending?: number;
  results: Array<{
    user_id: number;
    success: boolean;
    item?: {
      id: string;
      permalink: string;
      title: string;
      seller_id?: number;
      status?: string;
      user_product_id?: string | null;
      verification_status?: "confirmed" | "pending" | "failed";
      verification_message?: string | null;
    };
    error?: string;
  }>;
}

export interface MissingAttrDef {
  id: string;
  name: string;
  value_type: string; // "string" | "number_unit" | "list" | "boolean" | "number"
  default_unit?: string | null;
  allowed_units?: Array<{ id: string; name: string }>;
  values?: Array<{ id: string; name: string }>;
  hint?: string;
}

export async function createCloneMulti(
  cloneData: Record<string, unknown>,
  userIds: number[],
  batchId?: string
): Promise<CloneMultiResult> {
  // N contas em paralelo no backend, mas timeout precisa cobrir o pior caso.
  // `batchId` agrupa todos os anúncios da mesma publicação num só card no histórico.
  const { data } = await api.post<CloneMultiResult>("/clone/create-multi", {
    ...cloneData,
    user_ids: userIds,
    ...(batchId ? { batch_id: batchId } : {}),
  }, { timeout: 240_000 });
  return data;
}
