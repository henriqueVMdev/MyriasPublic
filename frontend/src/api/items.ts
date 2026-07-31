import api from "./client";
import type { MeliItem } from "@/types/item";

export async function getItem(itemId: string): Promise<MeliItem> {
  const { data } = await api.get<MeliItem>(`/items/${itemId}`);
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
