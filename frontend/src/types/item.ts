export interface Picture {
  id: string;
  url: string;
  secure_url: string;
  size: string;
  max_size: string;
}

export interface Attribute {
  id: string;
  name: string;
  value_id: string | null;
  value_name: string | null;
}

export interface Variation {
  id: number;
  attribute_combinations: Attribute[];
  price: number;
  available_quantity: number;
  sold_quantity: number;
  picture_ids: string[];
  seller_custom_field: string | null;
  attributes: Attribute[];
}

export interface MeliItem {
  id: string;
  title: string;
  price: number;
  available_quantity: number;
  sold_quantity: number;
  status: string;
  sub_status: string[];
  pictures: Picture[];
  thumbnail: string;
  permalink: string;
  category_id: string;
  seller_custom_field: string | null;
  attributes: Attribute[];
  shipping: Record<string, unknown>;
  listing_type_id: string;
  date_created: string;
  last_updated: string;
  variations: Variation[];
  description?: {
    plain_text: string;
  } | null;
  variations_detail?: Variation[];
}

export interface ItemsResponse {
  items: MeliItem[];
  paging: {
    total: number;
    offset: number;
    limit: number;
  };
}
