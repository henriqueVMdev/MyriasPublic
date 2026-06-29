import api from "./client";

export interface PerfItem {
  id: string;
  title: string;
  sku: string;
  status: string;
  price: number | null;
  available_quantity: number | null;
  sold_quantity?: number;
  listing_type_id?: string;
  permalink?: string;
  thumbnail?: string;
  category_id?: string;
  user_id: number;
  nickname: string;
  qty_sold: number;
  revenue: number;
  last_sale_date: string | null;
  days_since_last_sale: number | null;
  never_sold: boolean;
  date_created?: string | null;
  original_price?: number | null;
  logistic_type?: string | null;
  visits?: number;
  visits_lifetime?: number | null;
  conversion?: number | null;
}

export interface ModalityBreakdown {
  full: { qty: number; revenue: number; count: number };
  flex: { qty: number; revenue: number; count: number };
  padrao: { qty: number; revenue: number; count: number };
}

export interface AdsBreakdown {
  ads: {
    units: number;
    amount: number;
    cost: number;
    acos: number | null;
    clicks?: number;
    prints?: number;
    cpc?: number | null;
    ctr?: number | null;
    conversion?: number | null;
  };
  organic: { units: number };
}

export interface PerfSummary {
  total: number;
  sem_vendas: number;
  com_vendas: number;
  parados: number;
  stale_days: number;
  top_items: Array<{ id: string; title: string; sku: string; qty_sold: number }>;
}

export interface PerfListResponse {
  needs_refresh: boolean;
  missing_accounts: Array<{ user_id: number; nickname: string }>;
  items: PerfItem[];
  summary: PerfSummary;
  snapshot?: {
    inventory_scanned_at: string | null;
    inventory_count: number;
    sales_scanned_at: string | null;
    sales_lookback_days: number | null;
    visits_scanned_at?: string | null;
    visits_count?: number;
  };
  sort?: string;
  by_modality?: ModalityBreakdown;
  by_ads?: AdsBreakdown;
  paging: { total: number; offset: number; limit: number };
}

export interface PerfListParams {
  q?: string;
  filter?: "all" | "top_sellers" | "no_sales" | "stale";
  stale_days?: number;
  status?: string;
  account?: string;
  logistic?: string;
  sort?: string;
  days?: number;
  offset?: number;
  limit?: number;
}

export async function getPerfItems(params: PerfListParams = {}): Promise<PerfListResponse> {
  const clean = Object.fromEntries(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== "")
  );
  const { data } = await api.get<PerfListResponse>("/performance/items", { params: clean });
  return data;
}

export interface PerfVisits {
  total: number;
  dates: string[];
  series: number[];
}

export interface PerfSales {
  dates: string[];
  series: number[];
  revenue_series: number[];
}

export interface ItemAds {
  item_id: string;
  days: number;
  has_activity: boolean;
  campaign_id?: number | null;
  ad_status?: string | null;
  clicks?: number;
  prints?: number;
  cost?: number;
  units?: number;
  amount?: number;
  acos?: number | null;
  cpc?: number | null;
  ctr?: number | null;
  conversion?: number | null;
  direct_units?: number;
  indirect_units?: number;
}

export interface PerfItemDetail {
  item_id: string;
  meta: (PerfItem & { sale?: Record<string, unknown> }) | null;
  visits: PerfVisits;
  visits_lifetime?: number;
  sold_lifetime?: number;
  questions: { total: number; recent_dates: string[] };
  qty_sold: number;
  revenue: number;
  last_sale_date: string | null;
  sales: PerfSales;
  ads_series?: number[] | null;
  conversion: number | null;
  questions_per_visit: number | null;
  ads?: ItemAds | null;
}

export async function getPerfItem(itemId: string, days = 90): Promise<PerfItemDetail> {
  const { data } = await api.get<PerfItemDetail>(`/performance/items/${itemId}`, {
    params: { days },
  });
  return data;
}

export interface PerfSkuMember extends PerfItem {
  ads?: ItemAds | null;
  visits_lifetime?: number;
  sold_lifetime?: number;
  // Séries diárias por anúncio (alinhadas a PerfSkuResponse.dates) — pro gráfico
  // ads × orgânico, agregado client-side conforme o filtro de conta.
  sales_series?: number[];
  ads_series?: number[] | null;
}

export interface PerfSkuResponse {
  sku: string;
  members: PerfSkuMember[];
  best_item_id: string | null;
  by_modality?: ModalityBreakdown;
  by_ads?: AdsBreakdown;
  dates?: string[] | null;
  totals: {
    total_sold: number;
    total_sold_lifetime?: number;
    total_revenue: number;
    total_visits: number;
    total_visits_lifetime?: number;
    total_stock: number;
    conversion: number | null;
    item_count: number;
    ads_cost?: number;
    ads_units?: number;
    ads_amount?: number;
    ads_clicks?: number;
    ads_acos?: number | null;
  };
}

// `lite`: página de exclusão de repetidos — pula ads e série de visitas no
// backend (não são exibidos lá), deixando o carregamento bem mais rápido.
export async function getPerfSku(sku: string, days = 90, lite = false): Promise<PerfSkuResponse> {
  const { data } = await api.get<PerfSkuResponse>(`/performance/sku/${encodeURIComponent(sku)}`, {
    params: lite ? { days, lite: true } : { days },
  });
  return data;
}

export interface DuplicateGroup {
  sku: string;
  title: string;
  thumbnail?: string | null;
  count: number;
  qty_sold_total: number;
  never_sold_count: number;
}

export interface DuplicatesResponse {
  needs_refresh: boolean;
  missing_accounts: Array<{ user_id: number; nickname: string }>;
  items: DuplicateGroup[];
  snapshot?: {
    inventory_scanned_at: string | null;
    inventory_count: number;
  };
  sort?: string;
  paging: { total: number; offset: number; limit: number };
}

export interface DuplicatesParams {
  q?: string;
  account?: string;
  min_count?: number;
  sort?: "count_desc" | "count_asc";
  offset?: number;
  limit?: number;
}

export async function getDuplicates(params: DuplicatesParams = {}): Promise<DuplicatesResponse> {
  const clean = Object.fromEntries(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== "")
  );
  const { data } = await api.get<DuplicatesResponse>("/performance/duplicates", { params: clean });
  return data;
}

export interface DeleteItemResult {
  item_id: string;
  user_id: number;
  ok: boolean;
  action: string;
  status: number;
}

// `batchId`/`sku`: quando excluindo vários do mesmo SKU em massa, passe o mesmo
// batchId em todas as chamadas — o histórico agrupa numa só operação (por SKU,
// não por anúncio). Exclusão avulsa não manda batchId (vira linha própria).
export async function deletePerfItem(
  itemId: string,
  closeOnly = false,
  opts: { batchId?: string; sku?: string } = {}
): Promise<DeleteItemResult> {
  const params: Record<string, unknown> = { close_only: closeOnly };
  if (opts.batchId) params.batch_id = opts.batchId;
  if (opts.sku) params.sku = opts.sku;
  const { data } = await api.delete<DeleteItemResult>(`/performance/items/${itemId}`, { params });
  return data;
}

export interface SnapshotStatusAccount {
  user_id: number;
  nickname: string;
  refreshing: boolean;
  inventory_scanned_at: string | null;
  inventory_count: number;
  sales_scanned_at: string | null;
  sales_lookback_days: number | null;
  visits_scanned_at?: string | null;
  visits_count?: number;
}

export async function getSnapshotStatus(): Promise<{ accounts: SnapshotStatusAccount[] }> {
  const { data } = await api.get<{ accounts: SnapshotStatusAccount[] }>("/performance/snapshot-status");
  return data;
}

export interface RefreshResponse {
  started?: number[];
  already_running?: number[];
  accounts?: unknown[];
}

export async function refreshSnapshot(
  account = "all",
  background = false,
  mode: "auto" | "light" | "full" = "auto"
): Promise<RefreshResponse> {
  const { data } = await api.post<RefreshResponse>("/performance/refresh", null, {
    params: background ? { account, background: 1, mode } : { account, mode },
  });
  return data;
}
