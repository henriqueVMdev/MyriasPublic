// Helpers compartilhados para renderizar o histórico de operações de forma
// amigável (lista + página de detalhe). Mantém a lógica de "o que mostrar pro
// usuário comum" num lugar só.
import type { OperationGroup, OperationLog } from "@/api/logs";

export const TYPE_LABELS: Record<string, string> = {
  update: "Edição de anúncio",
  update_description: "Descrição",
  update_status: "Status",
  bulk_update: "Edição em massa",
  bulk_update_multi_account: "Edição por SKU",
  bulk_update_description: "Descrição",
  bulk_update_compatibilities: "Compatibilidade",
  bulk_update_positions: "Posições",
  delete_listing: "Exclusão de repetidos",
  promotion_add: "Enviado para promoção",
  promotion_remove: "Removido de promoção",
  clone: "Cópia de anúncio",
  answer_question: "Resposta de pergunta",
  send_message: "Mensagem enviada",
};

// Rótulo amigável dos tipos de promoção do ML, pra exibir nos detalhes.
export const PROMO_TYPE_LABELS: Record<string, string> = {
  DEAL: "Oferta",
  LIGHTNING: "Oferta relâmpago",
  DOD: "Oferta do dia",
  PRICE_DISCOUNT: "Desconto de preço",
  PRE_NEGOTIATED: "Pré-negociada",
  SMART: "Promoção inteligente",
  UNHEALTHY_STOCK: "Estoque parado",
  MARKETPLACE_CAMPAIGN: "Campanha",
};

export const FIELD_LABELS: Record<string, string> = {
  title: "Título",
  price: "Preço",
  available_quantity: "Estoque",
  seller_custom_field: "SKU interno",
  shipping: "Medidas de envio",
  pictures: "Fotos",
  attributes: "Atributos",
  description: "Descrição",
  status: "Status",
  keep_cover_photo: "Manter foto de capa",
};

export function listingTypeLabel(id: string | null | undefined): string {
  if (id === "gold_pro") return "Premium";
  if (id === "gold_special") return "Clássico";
  return id || "";
}

function childTypes(op: OperationGroup): Set<string> {
  return new Set(op.children.map((c) => c.operation_type));
}

/** Rótulo amigável da operação inteira (considera o conjunto de sub-operações). */
export function operationLabel(op: OperationGroup): string {
  const types = childTypes(op);
  if (types.has("clone")) return "Cópia de anúncio";
  const editLike = [
    "bulk_update_multi_account",
    "bulk_update_description",
    "bulk_update_compatibilities",
    "bulk_update_positions",
    "bulk_update",
    "update",
  ];
  if (editLike.some((t) => types.has(t))) return "Edição por SKU";
  return TYPE_LABELS[op.operation_type] || op.operation_type;
}

/** Detalhe da promoção (nome + tipo) a partir das sub-operações, se houver. */
export function promotionInfo(op: OperationGroup): { name: string; type: string } | null {
  for (const c of op.children) {
    if (c.operation_type !== "promotion_add" && c.operation_type !== "promotion_remove") continue;
    const p = (c.payload || {}) as Record<string, any>;
    const type = p.promotion_type || "";
    const name = p.promotion_name || PROMO_TYPE_LABELS[type] || "promoção";
    return { name, type };
  }
  return null;
}

/** SKU(s) distintos envolvidos na operação. */
export function operationSkus(op: OperationGroup): string[] {
  const skus = new Set<string>();
  for (const c of op.children) {
    const p = (c.payload || {}) as Record<string, any>;
    const sku = p.sku || p.seller_custom_field;
    if (sku) skus.add(String(sku));
  }
  return [...skus];
}

/** "O que mudou" — chips amigáveis agregados das sub-operações. */
export function operationChanges(op: OperationGroup): string[] {
  const chips = new Set<string>();
  for (const c of op.children) {
    const p = (c.payload || {}) as Record<string, any>;
    switch (c.operation_type) {
      case "clone":
        chips.add("Criação de anúncio");
        break;
      case "bulk_update_description":
      case "update_description":
        chips.add("Descrição");
        break;
      case "bulk_update_compatibilities":
        chips.add("Compatibilidade");
        break;
      case "bulk_update_positions":
        chips.add("Posições");
        break;
      case "promotion_add":
      case "promotion_remove": {
        const name = p.promotion_name || PROMO_TYPE_LABELS[p.promotion_type] || "Promoção";
        chips.add(name);
        if (p.promotion_type && PROMO_TYPE_LABELS[p.promotion_type] && p.promotion_name)
          chips.add(PROMO_TYPE_LABELS[p.promotion_type]);
        break;
      }
      case "bulk_update_multi_account":
      case "bulk_update": {
        const updates = (p.updates || {}) as Record<string, unknown>;
        for (const k of Object.keys(updates)) {
          if (k === "keep_cover_photo") continue;
          const label = FIELD_LABELS[k] || k;
          // Pros escalares uniformes (mesmo valor pra todos), já mostra o valor
          // novo no chip — ex.: "Status: Ativo", "Preço: R$ 99,90".
          const val = fmtFieldValue(k, updates[k]);
          chips.add(val && ["status", "price", "available_quantity"].includes(k) ? `${label}: ${val}` : label);
        }
        break;
      }
      case "update":
        for (const k of Object.keys(p)) chips.add(FIELD_LABELS[k] || k);
        break;
      default:
        chips.add(TYPE_LABELS[c.operation_type] || c.operation_type);
    }
  }
  return [...chips];
}

export const STATUS_LABELS: Record<string, string> = {
  active: "Ativo",
  paused: "Pausado",
  closed: "Encerrado",
  under_review: "Em revisão",
  inactive: "Inativo",
};

/** Formata o valor de um campo pra exibição (status vira rótulo, preço vira R$…). */
function fmtFieldValue(field: string, value: unknown): string | null {
  if (value === null || value === undefined || value === "") return null;
  switch (field) {
    case "status":
      return STATUS_LABELS[String(value)] || String(value);
    case "price":
      return Number(value).toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
    case "available_quantity":
      return `${value} un.`;
    default:
      return String(value);
  }
}

// Campos escalares que sabemos exibir como "antes → depois".
const SCALAR_CHANGE_FIELDS = ["status", "price", "available_quantity", "title", "seller_custom_field"];

export interface AdChange {
  field: string;
  label: string;
  from: string | null;
  to: string | null;
}

export interface AffectedAd {
  mlb: string | null;
  sku: string | null;
  title: string | null;
  listingType?: string | null;
  userId?: number | null;
  status: string;
  // "Excluído" / "Fechado" / "Falha ao excluir" — só em operações de exclusão.
  actionLabel?: string | null;
  // Mudanças campo a campo (status, preço, estoque…) com antes → depois.
  changes?: AdChange[];
}

/** Lista de anúncios afetados pela operação (dedup por MLB), para o expand/detalhe. */
export function affectedAds(op: OperationGroup): AffectedAd[] {
  const byMlb = new Map<string, AffectedAd>();
  const push = (ad: AffectedAd) => {
    const key = ad.mlb || `${ad.title}-${ad.userId}-${Math.random()}`;
    const existing = byMlb.get(key);
    if (existing) {
      // Mantém o pior status (error > partial > success) e completa campos vazios.
      if (ad.status === "error") existing.status = "error";
      existing.sku = existing.sku || ad.sku;
      existing.title = existing.title || ad.title;
      existing.listingType = existing.listingType || ad.listingType;
      existing.actionLabel = existing.actionLabel || ad.actionLabel;
      // Acumula mudanças de sub-operações distintas (campos + atributos + …),
      // deduplicando por campo (a última vence).
      if (ad.changes?.length) {
        const merged = [...(existing.changes || [])];
        for (const ch of ad.changes) {
          const i = merged.findIndex((m) => m.field === ch.field);
          if (i >= 0) merged[i] = ch;
          else merged.push(ch);
        }
        existing.changes = merged;
      }
    } else {
      byMlb.set(key, { ...ad });
    }
  };

  for (const c of op.children) {
    const p = (c.payload || {}) as Record<string, any>;
    if (c.operation_type === "clone") {
      push({
        mlb: c.item_ids?.[0] ?? null,
        sku: p.seller_custom_field ?? null,
        title: p.title ?? null,
        listingType: p.listing_type_id ?? null,
        userId: c.user_id ?? null,
        status: c.status,
      });
    } else if (Array.isArray(p.groups)) {
      const updates = (p.updates || {}) as Record<string, unknown>;
      // Status por item: promoções logam UMA linha pro lote inteiro, com o
      // resultado individual em response.results[]. Sem isso, todo item herdava
      // o status geral da linha (ex.: "partial") e aparecia como falha mesmo
      // quando entrou na promoção. Fallback pro status da linha (a lista não
      // carrega response — só o detalhe traz).
      const itemStatus = perItemStatus(c);
      for (const g of p.groups) {
        const items = Array.isArray(g.items)
          ? g.items
          : (g.item_ids || []).map((id: string) => ({ id, title: null }));
        for (const it of items) {
          const st = itemStatus.get(it.id) ?? c.status;
          push({
            mlb: it.id ?? null,
            // SKU por item quando o payload traz (ex.: promoções, onde cada
            // anúncio tem o seu); cai pro SKU único da operação (edição por SKU).
            sku: it.sku ?? p.sku ?? null,
            title: it.title ?? null,
            userId: g.user_id ?? null,
            status: st,
            actionLabel: deleteActionLabel(c.operation_type, p.action, st),
            changes: adChanges(c.operation_type, updates, it),
          });
        }
      }
    } else if (c.item_ids?.length) {
      for (const id of c.item_ids) {
        push({ mlb: id, sku: p.sku ?? null, title: null, userId: c.user_id ?? null, status: c.status });
      }
    }
  }
  return [...byMlb.values()];
}

/** Status real por item (MLB → "success"/"error") a partir de response.results.
 * Vazio quando a linha não traz response (ex.: na listagem, só no detalhe). */
function perItemStatus(c: OperationLog): Map<string, "success" | "error"> {
  const map = new Map<string, "success" | "error">();
  const results = (c.response as Record<string, any> | null | undefined)?.results;
  if (Array.isArray(results)) {
    for (const r of results) {
      if (r?.item_id) map.set(r.item_id, r.ok ? "success" : "error");
    }
  }
  return map;
}

/** Rótulo de ação pra exclusões: "Excluído" / "Fechado" / "Falha ao excluir". */
function deleteActionLabel(opType: string, action: unknown, status: string): string | null {
  if (opType !== "delete_listing") return null;
  if (status === "error") return "Falha ao excluir";
  return action === "closed" ? "Fechado" : "Excluído";
}

/** Mudanças campo a campo de um item, com antes → depois quando o "antes" existe. */
function adChanges(opType: string, updates: Record<string, unknown>, it: Record<string, any>): AdChange[] {
  const before = (it.before || {}) as Record<string, unknown>;
  const out: AdChange[] = [];

  for (const f of SCALAR_CHANGE_FIELDS) {
    if (!(f in updates)) continue;
    const to = fmtFieldValue(f, updates[f]);
    const from = f in before ? fmtFieldValue(f, before[f]) : null;
    if (to === null && from === null) continue;
    out.push({ field: f, label: FIELD_LABELS[f] || f, from, to });
  }

  // Promoção: o "preço na promoção" enviado por anúncio (deal_price).
  if (opType === "promotion_add" && it.deal_price != null) {
    out.push({
      field: "deal_price",
      label: "Preço na promoção",
      from: null,
      to: fmtFieldValue("price", it.deal_price),
    });
  }

  return out;
}

/** Linha de resumo curta para o card fechado. */
export function operationSummary(op: OperationGroup): string {
  const types = childTypes(op);
  if (types.has("clone")) {
    const ads = affectedAds(op);
    const titles = new Set(ads.map((a) => a.title).filter(Boolean));
    const lts = new Set(ads.map((a) => listingTypeLabel(a.listingType)).filter(Boolean));
    const parts = [`${ads.length} anúncio(s)`];
    if (titles.size) parts.push(`${titles.size} título(s)`);
    if (lts.size) parts.push([...lts].join(" e "));
    return parts.join(" · ");
  }
  // Promoções: "Nome da promoção · N anúncio(s)" (deixa o nome em destaque).
  const promo = promotionInfo(op);
  if (promo) {
    const verb = types.has("promotion_remove") ? "Removidos de" : "Enviados para";
    const n = affectedAds(op).length;
    return `${verb} “${promo.name}” · ${n} anúncio(s)`;
  }

  const skus = operationSkus(op);
  const changes = operationChanges(op);
  const parts: string[] = [];
  if (skus.length === 1) parts.push(`SKU ${skus[0]}`);
  else if (skus.length > 1) parts.push(`${skus.length} SKUs`);
  if (changes.length) parts.push(changes.join(", "));
  return parts.join(" · ") || "—";
}
