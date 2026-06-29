import api from "./client";
import type { MeliItem, ItemsResponse } from "@/types/item";

export interface ItemFilters {
  status?: string;
  seller_sku?: string;
  q?: string;
  offset?: number;
  limit?: number;
}

export async function getItems(filters: ItemFilters = {}): Promise<ItemsResponse> {
  const params = Object.fromEntries(
    Object.entries(filters).filter(([, v]) => v !== undefined && v !== "")
  );
  const { data } = await api.get<ItemsResponse>("/items", { params });
  return data;
}

export async function getItem(itemId: string): Promise<MeliItem> {
  const { data } = await api.get<MeliItem>(`/items/${itemId}`);
  return data;
}

export interface UpdateItemResult {
  ok: boolean;
  ignored_attrs: string[];
}

export async function updateItem(
  itemId: string,
  updates: Record<string, unknown>
): Promise<UpdateItemResult> {
  const { data } = await api.put<UpdateItemResult>(`/items/${itemId}`, updates);
  return data;
}

export async function updateItemStatus(
  itemId: string,
  status: string
): Promise<unknown> {
  const { data } = await api.put(`/items/${itemId}/status`, { status });
  return data;
}

export async function updateItemDescription(
  itemId: string,
  plain_text: string
): Promise<unknown> {
  const { data } = await api.put(`/items/${itemId}/description`, { plain_text });
  return data;
}

export async function updateItemPictures(
  itemId: string,
  pictures: Array<{ id?: string; source?: string }>
): Promise<unknown> {
  const { data } = await api.put(`/items/${itemId}/pictures`, { pictures });
  return data;
}

export interface CategoryAttribute {
  id: string;
  name: string;
  value_type: string;
  values: { id: string; name: string }[];
  tags: {
    required: boolean;
    catalog_required: boolean;
    variation_attribute: boolean;
    allow_custom_value: boolean;
  };
  tooltip: string;
  default_unit?: string | null;
  allowed_units?: Array<{ id: string; name: string }>;
}

export async function getCategoryAttributes(categoryId: string): Promise<CategoryAttribute[]> {
  const { data } = await api.get<CategoryAttribute[]>(`/items/category-attributes/${categoryId}`);
  return data;
}

export async function uploadPicture(file: File): Promise<{ status: number; data: { id: string; variations: { secure_url: string }[] } }> {
  const formData = new FormData();
  formData.append("file", file);
  const { data } = await api.post("/items/upload-picture", formData, {
    headers: { "Content-Type": "multipart/form-data" },
  });
  return data;
}
