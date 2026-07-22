import api from "./client";

export interface Promotion {
  id: string;
  type: string;
  name: string | null;
  status: string | null;
  start_date: string | null;
  finish_date: string | null;
  deadline_date: string | null;
  fixed_percentage: number | null;
  sub_type: string | null;
  price_choice: boolean;
  candidate_count: number | null; // elegíveis
  started_count: number | null; // participando
}

export interface Coupon {
  id: string;
  name: string | null;
  status: string | null;
  sub_type: string | null;
  fixed_percentage: number | null;
  min_purchase_amount: number | null; // valor mínimo de compra
  max_purchase_amount: number | null; // teto de desconto (R$)
  redeems_per_user: number | null;
  budget: number | null;
  remaining_budget: number | null;
  used_coupons: number | null; // nº de resgates
  start_date: string | null;
  finish_date: string | null;
}

export interface PromotionItem {
  id: string;
  title: string | null;
  sku: string;
  thumbnail: string | null;
  category_id: string | null;
  category_name: string | null;
  status: string | null; // "candidate" | "started"
  current_price: number | null; // preço cheio ("de")
  promo_price: number | null; // preço promocional (escolhido/sugerido/fixo)
  // Faixa de preço promo quando o mesmo MLB participa com vários preços (= promo_price se único).
  promo_price_min: number | null;
  promo_price_max: number | null;
  discount_pct: number | null;
  price_editable: boolean;
  min_price: number | null;
  max_price: number | null;
  suggested_price: number | null;
  stock_min: number | null;
  stock_max: number | null;
  meli_percentage: number | null;
  seller_percentage: number | null;
  fixed_percentage: number | null;
  offer_id: string | null;
}

export interface PromotionItemsPage {
  items: PromotionItem[];
  paging: { total: number | null; search_after: string | null };
}

export interface PromotionItemInput {
  item_id: string;
  deal_price?: number;
  top_deal_price?: number;
  stock?: number; // unidades reservadas — obrigatório em LIGHTNING
  offer_id?: string;
}

export interface PromotionActionResult {
  ok: boolean;
  batch_id: string;
  succeeded: number;
  failed: number;
  results: Array<{ item_id: string; ok: boolean; status: number; data?: unknown; error?: string }>;
}

export async function getPromotions(): Promise<Promotion[]> {
  const { data } = await api.get<{ promotions: Promotion[] }>("/promotions");
  return data.promotions;
}

export async function getCoupons(): Promise<Coupon[]> {
  const { data } = await api.get<{ coupons: Coupon[] }>("/promotions/coupons");
  return data.coupons;
}

export interface CouponInput {
  name: string;
  sub_type: "FIXED_PERCENTAGE" | "FIXED_AMOUNT";
  start_date: string;
  finish_date: string;
  fixed_percentage?: number | null;
  fixed_amount?: number | null;
  min_purchase_amount?: number | null;
  max_purchase_amount?: number | null;
  budget?: number | null;
  redeems_per_user?: number | null;
  partial_coupon_code?: string | null;
}

// Resposta crua do ML (body de escrita ainda em validação) — ok + status + data.
export interface CouponWriteResult {
  ok: boolean;
  status: number;
  data?: unknown;
  error?: string;
}

export async function createCoupon(payload: CouponInput): Promise<CouponWriteResult> {
  const { data } = await api.post<CouponWriteResult>("/promotions/coupons", payload);
  return data;
}

export async function updateCoupon(id: string, payload: Partial<CouponInput>): Promise<CouponWriteResult> {
  const { data } = await api.put<CouponWriteResult>(`/promotions/coupons/${encodeURIComponent(id)}`, payload);
  return data;
}

export async function endCoupon(id: string): Promise<CouponWriteResult> {
  const { data } = await api.delete<CouponWriteResult>(`/promotions/coupons/${encodeURIComponent(id)}`);
  return data;
}

export async function getPromotionItems(
  promotionId: string,
  promotionType: string,
  status?: string,
  searchAfter?: string,
  enrichLimits = true
): Promise<PromotionItemsPage> {
  const params: Record<string, string> = { promotion_type: promotionType };
  if (status) params.status = status;
  if (searchAfter) params.search_after = searchAfter;
  // Varredura de categorias pede enrich_limits=false pra não disparar 1 chamada/item no LIGHTNING.
  if (!enrichLimits) params.enrich_limits = "false";
  const { data } = await api.get<PromotionItemsPage>(
    `/promotions/${encodeURIComponent(promotionId)}/items`,
    { params }
  );
  return data;
}

// Limites reais de LIGHTNING (min/máx/sugerido) buscados sob demanda ao abrir uma categoria.
export interface LightningLimit {
  min_price: number | null;
  max_price: number | null;
  suggested_price: number | null;
}
export async function enrichLightning(
  promotionId: string,
  itemIds: string[]
): Promise<Record<string, LightningLimit>> {
  const { data } = await api.post<{ limits: Record<string, LightningLimit> }>(
    `/promotions/${encodeURIComponent(promotionId)}/enrich-lightning`,
    { item_ids: itemIds }
  );
  return data.limits;
}

export async function searchPromotionItems(
  promotionId: string,
  promotionType: string,
  q: string
): Promise<{ items: PromotionItem[]; messages: string[] }> {
  const { data } = await api.get<{ items: PromotionItem[]; messages: string[] }>(
    `/promotions/${encodeURIComponent(promotionId)}/search`,
    { params: { q, promotion_type: promotionType } }
  );
  return data;
}

export async function addPromotionItems(payload: {
  promotion_id: string;
  promotion_type: string;
  items: PromotionItemInput[];
}): Promise<PromotionActionResult> {
  const { data } = await api.post<PromotionActionResult>("/promotions/items", payload);
  return data;
}

export async function removePromotionItems(payload: {
  promotion_id: string;
  promotion_type: string;
  item_ids: string[];
}): Promise<PromotionActionResult> {
  const { data } = await api.delete<PromotionActionResult>("/promotions/items", { data: payload });
  return data;
}
