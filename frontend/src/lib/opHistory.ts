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
const PROMO_TYPE_LABELS: Record<string, string> = {
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
  listing_type_id: "Tipo de anúncio",
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
function promotionInfo(op: OperationGroup): { name: string; type: string } | null {
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
function operationSkus(op: OperationGroup): string[] {
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
  // Motivo amigavel devolvido pelo ML para falhas individuais em promocoes.
  errorMessage?: string | null;
  errorCode?: string | null;
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
      existing.errorMessage = existing.errorMessage || ad.errorMessage;
      existing.errorCode = existing.errorCode || ad.errorCode;
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
      const itemResults = perItemResults(c);
      for (const g of p.groups) {
        const items = Array.isArray(g.items)
          ? g.items
          : (g.item_ids || []).map((id: string) => ({ id, title: null }));
        for (const it of items) {
          const result = itemResults.get(it.id);
          // Item listado como falha → erro. Senão: numa linha "partial" os não
          // listados deram certo (sucesso); numa linha inteira ok/erro (ex.:
          // delete, que loga 1 linha por anúncio) herda o status da linha.
          const st = result?.status ?? (c.status === "partial" ? "success" : c.status);
          push({
            mlb: it.id ?? null,
            // SKU por item quando o payload traz (ex.: promoções, onde cada
            // anúncio tem o seu); cai pro SKU único da operação (edição por SKU).
            sku: it.sku ?? p.sku ?? null,
            title: it.title ?? null,
            userId: g.user_id ?? null,
            status: st,
            errorMessage: result?.errorMessage ?? null,
            errorCode: result?.errorCode ?? null,
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

/** Mapa dos itens que falharam (MLB → status/erro), reunindo promoções
 * (response.results), bulk (response.errors / per_account[].errors) e o
 * failed_ids leve que a listagem traz mesmo sem a response completa.
 * Itens fora do mapa não necessariamente falharam — ver uso em affectedAds. */
function perItemResults(c: OperationLog): Map<string, {
  status: "success" | "error";
  errorMessage: string | null;
  errorCode: string | null;
}> {
  const map = new Map<string, {
    status: "success" | "error";
    errorMessage: string | null;
    errorCode: string | null;
  }>();
  const resp = (c.response as Record<string, any> | null | undefined) || {};
  // Promoções: results[{item_id, ok}] — traz ok e falha com motivo do ML.
  if (Array.isArray(resp.results)) {
    for (const r of resp.results) {
      if (!r?.item_id) continue;
      const failure = r.ok ? { message: null, code: null } : friendlyMeliError(r);
      map.set(r.item_id, {
        status: r.ok ? "success" : "error",
        errorMessage: failure.message,
        errorCode: failure.code,
      });
    }
  }
  // Bulk (conta única: errors[]; multi-conta: per_account[].errors[]).
  const bulkErrors = [
    ...(Array.isArray(resp.errors) ? resp.errors : []),
    ...((Array.isArray(resp.per_account) ? resp.per_account : []).flatMap(
      (a: any) => (Array.isArray(a?.errors) ? a.errors : [])
    )),
  ];
  for (const e of bulkErrors) {
    if (!e?.item_id || map.has(e.item_id)) continue;
    map.set(e.item_id, { status: "error", errorMessage: bulkErrorText(e.error), errorCode: null });
  }
  // Listagem: response completa é omitida, mas failed_ids sempre vem.
  for (const id of (c.failed_ids || [])) {
    if (!map.has(id)) map.set(id, { status: "error", errorMessage: null, errorCode: null });
  }
  return map;
}

// Tradução dos códigos de erro de bulk mais comuns do ML pra algo legível.
const BULK_ERROR_MESSAGES: Record<string, string> = {
  "item.user_product.repeated.conflict":
    "Alteração negada pelo Mercado Livre, anúncio idêntico a outro.",
  "item.attribute.invalid":
    "Valor de atributo inválido para o Mercado Livre (ex.: texto num campo que espera número).",
};

/** Texto amigável de um erro de bulk (ML devolve dict {message, error, cause[]} ou string). */
function bulkErrorText(error: unknown): string | null {
  if (!error) return null;
  if (typeof error === "string") return error;
  if (typeof error !== "object") return String(error);
  const e = error as Record<string, any>;
  const causes = Array.isArray(e.cause) ? e.cause : [];
  const knownCode = causes.map((c: any) => c?.code).find((c: string) => BULK_ERROR_MESSAGES[c]);
  if (knownCode) return BULK_ERROR_MESSAGES[knownCode];
  const cause = causes.map((c: any) => c?.message || c?.code).filter(Boolean).join("; ");
  const raw = cause || e.message || e.error || "";
  return raw ? `O Mercado Livre rejeitou: ${raw}` : null;
}

const MELI_ERROR_MESSAGES: Record<string, string> = {
  ERROR_CREDIBILITY_DISCOUNTED_PRICE:
    "O Mercado Livre rejeitou o preço promocional por estar fora da faixa considerada válida para este anúncio. Confira os limites mínimo e máximo atualizados antes de tentar novamente.",
  OFFER_ALREADY_EXISTS:
    "Este anúncio já possui uma oferta nesta promoção.",
};

/** Traduz o retorno tecnico do seller-promotions sem esconder o codigo do ML. */
function friendlyMeliError(result: Record<string, any>): { message: string; code: string | null } {
  const data = result?.data;
  const raw = [result?.error, data?.message, data?.error, typeof data === "string" ? data : null]
    .find((v) => typeof v === "string" && v.trim()) || "";
  const causes = Array.isArray(data?.cause) ? data.cause : [];
  const causeCode = causes.map((c: any) => c?.code).find(Boolean);
  const knownCode = Object.keys(MELI_ERROR_MESSAGES).find((code) =>
    causeCode === code || raw.includes(code)
  );
  if (knownCode) return { message: MELI_ERROR_MESSAGES[knownCode], code: knownCode };
  return {
    message: raw ? `O Mercado Livre rejeitou este anúncio: ${raw}` : "O Mercado Livre rejeitou este anúncio sem informar o motivo.",
    code: causeCode || null,
  };
}

export interface OperationDisplayStats {
  total: number;
  success: number;
  error: number;
  isPromotion: boolean;
}

/** Contagem por anuncio para promocoes; demais operacoes mantem a contagem do grupo. */
export function operationDisplayStats(op: OperationGroup): OperationDisplayStats {
  const isPromotion = op.children.some((c) =>
    c.operation_type === "promotion_add" || c.operation_type === "promotion_remove"
  );
  if (!isPromotion) return { total: op.total, success: op.success, error: op.error, isPromotion };

  const ads = affectedAds(op);
  const exact = ads.filter((ad) => ad.status === "success" || ad.status === "error");
  if (exact.length === ads.length && ads.length) {
    const error = ads.filter((ad) => ad.status === "error").length;
    return { total: ads.length, success: ads.length - error, error, isPromotion };
  }

  const failed = op.children.reduce((sum, child) => {
    const match = child.error_message?.match(/(\d+)\s+item\(ns\)\s+falharam/i);
    return sum + (match ? Number(match[1]) : 0);
  }, 0);
  const total = ads.length || op.total;
  return { total, success: Math.max(0, total - failed), error: failed, isPromotion };
}

/** Rótulo de ação pra exclusões: "Excluído" / "Fechado" / "Falha ao excluir". */
function deleteActionLabel(opType: string, action: unknown, status: string): string | null {
  if (opType !== "delete_listing") return null;
  if (status === "error") return "Falha ao excluir";
  return action === "closed" ? "Fechado" : "Excluído";
}

// Rótulos amigáveis dos atributos que sabemos nomear (medidas de embalagem nos
// vários formatos que o ML usa). Os demais caem no próprio id.
const ATTR_LABELS: Record<string, string> = {
  seller_package_height: "Altura (embalagem)",
  seller_package_width: "Largura (embalagem)",
  seller_package_length: "Comprimento (embalagem)",
  seller_package_weight: "Peso (embalagem)",
  PACKAGE_HEIGHT: "Altura (embalagem)",
  PACKAGE_WIDTH: "Largura (embalagem)",
  PACKAGE_LENGTH: "Comprimento (embalagem)",
  PACKAGE_WEIGHT: "Peso (embalagem)",
};

/** Valor de um atributo enviado no PUT: value_name, ou number+unit, ou "Não se aplica". */
function fmtAttrValue(a: Record<string, any>): string {
  if (a.value_name) return String(a.value_name);
  const vs = a.value_struct;
  if (vs && typeof vs === "object" && vs.number != null) {
    return `${vs.number} ${vs.unit || ""}`.trim();
  }
  return "Não se aplica";
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

  // Atributos aplicados (medidas de embalagem, atributos de categoria…). O bulk
  // não manda "antes" de atributo — mostramos só o valor definido ("depois").
  // SELLER_SKU é ignorado: já aparece como "SKU interno" pelo campo escalar.
  if (Array.isArray(updates.attributes)) {
    for (const a of updates.attributes as Record<string, any>[]) {
      if (!a?.id || a.id === "SELLER_SKU") continue;
      out.push({
        field: `attr:${a.id}`,
        label: ATTR_LABELS[a.id] || a.id,
        from: null,
        to: fmtAttrValue(a),
      });
    }
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
