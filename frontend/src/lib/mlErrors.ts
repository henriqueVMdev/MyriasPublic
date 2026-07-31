/**
 * Tradução dos erros da API do Mercado Livre para texto que o usuário entende.
 *
 * Ficava só no BulkEdit; o CloneListing tinha um `formatError` homônimo que
 * caía direto em JSON.stringify — quem publicava em várias contas via uma
 * linha de JSON cru na tabela de resultados em vez da mensagem.
 */
const ML_ERROR_MESSAGES: Record<string, string> = {
  field_not_updatable: "Campo não editável neste tipo de anúncio",
  invalid_operation: "Operação não permitida",
  item_not_active: "Anúncio inativo",
  not_found: "Anúncio não encontrado",
  access_denied: "Sem permissão para editar",
  forbidden: "Sem permissão para editar",
  invalid_price: "Preço inválido",
  insufficient_stock: "Estoque insuficiente",
  item_closed: "Anúncio encerrado",
  invalid_attribute: "Atributo inválido",
  catalog_listing_not_allowed: "Anúncio de catálogo — edição restrita",
  max_images_exceeded: "Limite máximo de fotos atingido",
  "item.user_product.repeated.conflict":
    "Alteração negada pelo Mercado Livre, anúncio idêntico a outro.",
  "item.attribute.invalid":
    "Valor de atributo inválido para o Mercado Livre (ex.: texto num campo que espera número).",
};

export function formatMlError(err: unknown): string {
  if (err == null) return "Erro desconhecido";
  if (typeof err === "string") return err;
  if (typeof err !== "object") return String(err);
  const e = err as Record<string, unknown>;

  const code = (e.error as string) || "";
  if (ML_ERROR_MESSAGES[code]) return ML_ERROR_MESSAGES[code];

  // O ML detalha o motivo real dentro de `cause`; o `error` de fora costuma ser genérico.
  const causes = e.cause as Array<Record<string, unknown>> | undefined;
  if (causes?.length) {
    const causeMsg = causes
      .map((c) => {
        const causeCode = (c.code as string) || "";
        return ML_ERROR_MESSAGES[causeCode] || (c.message as string) || causeCode;
      })
      .join("; ");
    if (causeMsg) return causeMsg;
  }

  const status = e.status as number | undefined;
  if (status === 403) return "Sem permissão para editar";
  if (status === 404) return "Anúncio não encontrado";
  if (status === 429) return "Muitas requisições — tente novamente em instantes";
  if (status && status >= 500) return "Erro interno do Mercado Livre";

  const msg = e.message as string | undefined;
  if (msg) return msg;

  try {
    return JSON.stringify(err);
  } catch {
    return String(err);
  }
}
