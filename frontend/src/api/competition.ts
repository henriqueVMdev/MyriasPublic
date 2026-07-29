import api from "./client";

export interface Competitor {
  item_id: string;
  title?: string;
  seller_id: number;
  seller_nickname: string;
  price: number;
  original_price?: number;
  sold_quantity: number;
  available_quantity?: number;
  listing_type_id?: string;
  condition?: string;
  free_shipping: boolean;
  logistic_type: string;
  official_store_id?: string | null;
  permalink?: string | null;
  is_mine?: boolean;
  is_winner?: boolean;
}

export type CompetitionMode = "catalog" | "standalone" | "not_found";
export type CompetitionStatus = "winning" | "sharing" | "competing" | "not_listed";
export type StandaloneStatus = "cheapest" | "below_median" | "above_median" | "unknown";

export interface CompetitionAnalysis {
  item_id: string;
  mode: CompetitionMode;
  message?: string;
  title?: string;
  category_id?: string;
  status?: CompetitionStatus | StandaloneStatus;
  competitor_count?: number;
  my_price?: number;
  my_position?: number;
  competitors?: Competitor[];
  // catálogo:
  catalog_product_id?: string;
  winner_price?: number;
  price_gap?: number | null;
  price_gap_pct?: number | null;
  price_to_win?: number | null;
  // avulso (busca pública):
  median_price?: number | null;
  min_price?: number | null;
  max_price?: number | null;
  price_percentile?: number | null;
  free_shipping_pct?: number;
  matched_by?: "code" | "title";
  codes?: { label: string; value: string }[];
  search_terms?: string[];
}

export async function getItemCompetition(itemId: string, code?: string): Promise<CompetitionAnalysis> {
  const params = code ? { code } : undefined;
  const { data } = await api.get<CompetitionAnalysis>(
    `/competition/items/${encodeURIComponent(itemId)}`,
    { params }
  );
  return data;
}

// ---------- snapshot por conta ----------

export type CompItemStatus = CompetitionStatus | "needs_live_check" | "unknown";

export interface CompetitionRow {
  id: string;
  title: string;
  sku: string;
  thumbnail?: string | null;
  permalink?: string | null;
  status?: string | null;
  price: number;
  category_id: string;
  mode: "catalog" | "standalone";
  comp_status: CompItemStatus;
  catalog_product_id?: string;
  competitor_count?: number;
  my_position?: number;
  winner_price?: number | null;
  price_gap?: number | null;
  price_gap_pct?: number | null;
  price_to_win?: number | null;
}

export interface CompetitionSummary {
  analyzed: number;
  catalog: number;
  standalone: number;
  winning: number;
  sharing: number;
  competing: number;
  not_listed: number;
  unknown: number;
}

export interface CompetitionReport {
  has_snapshot: boolean;
  refreshing: boolean;
  status: "empty" | "running" | "complete" | "error" | "interrupted";
  started_at?: string | null;
  scanned_at?: string | null;
  processed: number;
  total: number;
  summary: CompetitionSummary;
  items: CompetitionRow[];
  paging: { total: number; offset: number; limit: number };
  warnings: string[];
}

export interface CompetitionFilters {
  q?: string;
  status?: string;
  offset?: number;
  limit?: number;
}

// ---------- descoberta de mercado (categoria) ----------

export interface MarketTrend {
  keyword: string;
  url: string;
}

export interface BestSeller {
  position: number;
  id: string;
  title?: string;
  price?: number;
  sold_quantity?: number;
  thumbnail?: string | null;
  permalink?: string | null;
  seller_id?: number;
}

export interface CategoryDiscovery {
  category_id: string;
  trends: MarketTrend[];
  best_sellers: BestSeller[];
}

export async function getCategoryDiscovery(categoryId: string): Promise<CategoryDiscovery> {
  const { data } = await api.get<CategoryDiscovery>(
    `/competition/category/${encodeURIComponent(categoryId)}`
  );
  return data;
}

export async function getCompetitionReport(filters: CompetitionFilters = {}): Promise<CompetitionReport> {
  const params = Object.fromEntries(
    Object.entries(filters).filter(([, value]) => value !== undefined && value !== "")
  );
  const { data } = await api.get<CompetitionReport>("/competition", { params });
  return data;
}

export async function refreshCompetition(): Promise<{ started: boolean; already_running: boolean }> {
  const { data } = await api.post("/competition/refresh");
  return data;
}
