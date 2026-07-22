import api from "./client";

export interface Competitor {
  item_id: string;
  seller_id: number;
  seller_nickname: string;
  price: number;
  original_price?: number;
  sold_quantity: number;
  available_quantity: number;
  listing_type_id: string;
  condition: string;
  free_shipping: boolean;
  logistic_type: string;
  official_store_id?: string | null;
  is_mine: boolean;
  is_winner: boolean;
}

export type CompetitionMode = "catalog" | "standalone" | "not_found";
export type CompetitionStatus = "winning" | "sharing" | "competing" | "not_listed";

export interface CompetitionAnalysis {
  item_id: string;
  mode: CompetitionMode;
  message?: string;
  // catálogo:
  catalog_product_id?: string;
  title?: string;
  category_id?: string;
  status?: CompetitionStatus;
  competitor_count?: number;
  my_price?: number;
  my_position?: number;
  winner_price?: number;
  price_gap?: number | null;
  price_gap_pct?: number | null;
  price_to_win?: number | null;
  competitors?: Competitor[];
}

export async function getItemCompetition(itemId: string): Promise<CompetitionAnalysis> {
  const { data } = await api.get<CompetitionAnalysis>(
    `/competition/items/${encodeURIComponent(itemId)}`
  );
  return data;
}
