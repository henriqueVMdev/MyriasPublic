package com.myrias.mlmanager.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myrias.mlmanager.meli.MeliClient;
import com.myrias.mlmanager.meli.MeliClient.MeliResponse;
import com.myrias.mlmanager.perf.PerfSnapshot;
import com.myrias.mlmanager.perf.PerfSnapshotRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auditoria de completude dos anúncios de uma conta Mercado Livre. Espelho de
 * backend/app/services/meli_quality.py.
 *
 * Combina três fontes: {@code catalog_quality/status} (atributos ausentes),
 * {@code /item/{id}/performance} (objetivos: vídeo, imagens, descrição) e
 * {@code /items/{id}/description} (checagem direta da descrição).
 *
 * Divergência do Python: o snapshot em disco (quality_{user}.json) vira uma linha
 * na tabela {@code perf_snapshots} com {@code kind="quality"} (corpo JSON opaco),
 * reusando {@link PerfSnapshotRepository}. ponytail: auditoria sequencial item a
 * item (o rate limiter já serializa); paralelizar via executor se conta grande pesar.
 */
@Service
public class MeliQualityService {

    private static final Logger log = LoggerFactory.getLogger(MeliQualityService.class);
    static final ObjectMapper M = new ObjectMapper();
    static final String KIND = "quality";

    private static final List<String> QUALITY_FIELDS = List.of(
            "id", "title", "thumbnail", "permalink", "status", "category_id",
            "listing_type_id", "seller_custom_field", "attributes", "tags",
            "user_product_id");

    private static final Map<String, String> ATTRIBUTE_LABELS = Map.ofEntries(
            Map.entry("GTIN", "Código universal (GTIN)"),
            Map.entry("BRAND", "Marca"),
            Map.entry("MODEL", "Modelo"),
            Map.entry("PART_NUMBER", "Número da peça"),
            Map.entry("OEM", "Código OEM"),
            Map.entry("DIAMETER", "Diâmetro"),
            Map.entry("INNER_DIAMETER", "Diâmetro interno"),
            Map.entry("OUTER_DIAMETER", "Diâmetro externo"),
            Map.entry("LENGTH", "Comprimento"),
            Map.entry("WIDTH", "Largura"),
            Map.entry("HEIGHT", "Altura"),
            Map.entry("THICKNESS", "Espessura"),
            Map.entry("MATERIAL", "Material"),
            Map.entry("COLOR", "Cor"),
            Map.entry("VEHICLE_TYPE", "Tipo de veículo"));

    private final MeliClient client;
    private final PerfSnapshotRepository repo;

    public MeliQualityService(MeliClient client, PerfSnapshotRepository repo) {
        this.client = client;
        this.repo = repo;
    }

    // ---------- load / save ----------

    @Transactional(readOnly = true)
    public ObjectNode load(long userId) {
        return repo.findByUserIdAndKind(userId, KIND)
                .map(s -> s.getData() instanceof ObjectNode o ? o : null)
                .orElse(null);
    }

    /** Upsert do snapshot. Single-writer (prod = 1 worker), como o set em memória do Python. */
    @Transactional
    public void save(ObjectNode snapshot) {
        long userId = snapshot.path("user_id").asLong();
        PerfSnapshot s = repo.findByUserIdAndKind(userId, KIND).orElse(null);
        if (s == null) {
            s = new PerfSnapshot(userId, KIND, Instant.now(), snapshot);
        } else {
            s.setScannedAt(Instant.now());
            s.setData(snapshot);
        }
        repo.save(s);
    }

    // ---------- marcações incrementais ----------

    /** Marca itens no cache imediatamente, antes da confirmação do ML. */
    public boolean markValidating(long userId, List<String> itemIds, String source) {
        ObjectNode snapshot = load(userId);
        if (snapshot == null) return false;
        Set<String> cleanIds = cleanSet(itemIds);
        Set<String> pending = validatingIds(snapshot);
        pending.addAll(cleanIds);
        setValidatingIds(snapshot, pending);
        String now = nowIso();
        for (JsonNode item : snapshot.path("items")) {
            if (cleanIds.contains(item.path("id").asText())) {
                ObjectNode o = (ObjectNode) item;
                o.put("validation_status", "validating");
                o.put("validation_source", source);
                o.put("validation_started_at", now);
            }
        }
        save(snapshot);
        return true;
    }

    public Set<String> cachedIssueKeys(long userId, String itemId) {
        ObjectNode snapshot = load(userId);
        if (snapshot == null) return Set.of();
        for (JsonNode row : snapshot.path("items")) {
            if (itemId.equals(row.path("id").asText())) {
                Set<String> keys = new LinkedHashSet<>();
                for (JsonNode issue : row.path("issues")) {
                    String k = issue.path("key").asText(null);
                    if (k != null && !k.isBlank()) keys.add(k);
                }
                return keys;
            }
        }
        return Set.of();
    }

    /** Encerra o estado pendente sem alterar o último resultado conhecido. */
    public void markValidationFailed(long userId, String itemId, String message) {
        ObjectNode snapshot = load(userId);
        if (snapshot == null) return;
        Set<String> pending = validatingIds(snapshot);
        pending.remove(itemId);
        setValidatingIds(snapshot, pending);
        for (JsonNode item : snapshot.path("items")) {
            if (itemId.equals(item.path("id").asText())) {
                ObjectNode o = (ObjectNode) item;
                o.put("validation_status", "confirmed");
                o.put("validation_error", message);
                o.put("validated_at", nowIso());
                break;
            }
        }
        save(snapshot);
    }

    /** Insere/substitui um item e recalcula os agregados do snapshot. */
    public void applyItemAudit(long userId, String itemId, ObjectNode audited, String validationStatus) {
        ObjectNode snapshot = load(userId);
        if (snapshot == null) return;
        ArrayNode items = snapshot.withArray("items");
        int index = indexOfItem(items, itemId);
        if (audited == null) {
            if (index >= 0) items.remove(index);
        } else {
            audited.put("validation_status", validationStatus);
            audited.put("validated_at", nowIso());
            audited.remove("validation_error");
            if (index < 0) items.add(audited);
            else items.set(index, audited);
        }
        Set<String> pending = validatingIds(snapshot);
        if (!"validating".equals(validationStatus)) pending.remove(itemId);
        setValidatingIds(snapshot, pending);
        snapshot.set("summary", summarize(items));
        save(snapshot);
    }

    // ---------- agregação ----------

    public static ObjectNode summarize(JsonNode items) {
        Map<String, ObjectNode> issues = new LinkedHashMap<>();
        int incomplete = 0;
        int analyzed = 0;
        int attributes = 0;
        for (JsonNode item : items) {
            analyzed++;
            JsonNode itemIssues = item.path("issues");
            boolean hasIssues = itemIssues.isArray() && !itemIssues.isEmpty();
            if (hasIssues) incomplete++;
            boolean hasAttr = false;
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode issue : itemIssues) {
                if ("attribute".equals(issue.path("type").asText())) hasAttr = true;
                String key = issue.path("key").asText();
                if (key.isEmpty() || !seen.add(key)) continue;
                ObjectNode entry = issues.computeIfAbsent(key, k -> {
                    ObjectNode e = M.createObjectNode();
                    e.put("key", key);
                    e.put("label", issue.path("label").asText());
                    e.put("type", issue.path("type").asText());
                    e.put("count", 0);
                    return e;
                });
                entry.put("count", entry.path("count").asInt() + 1);
            }
            if (hasAttr) attributes++;
        }

        List<ObjectNode> issueList = new ArrayList<>(issues.values());
        issueList.sort(Comparator.<ObjectNode>comparingInt(r -> -r.path("count").asInt())
                .thenComparing(r -> r.path("label").asText()));
        ArrayNode issueArr = M.createArrayNode();
        issueList.forEach(issueArr::add);

        ObjectNode out = M.createObjectNode();
        out.put("analyzed", analyzed);
        out.put("incomplete", incomplete);
        out.put("complete", Math.max(0, analyzed - incomplete));
        out.put("attributes", attributes);
        out.put("description", issues.containsKey("description") ? issues.get("description").path("count").asInt() : 0);
        out.put("clip", issues.containsKey("clip") ? issues.get("clip").path("count").asInt() : 0);
        out.put("pictures", issues.containsKey("pictures") ? issues.get("pictures").path("count").asInt() : 0);
        out.set("issues", issueArr);
        return out;
    }

    // ---------- auditoria ----------

    /** Revalida somente um anúncio, usado após edições e webhooks. */
    public ObjectNode auditOne(long userId, String itemId) {
        MeliResponse itemResp = client.get("/items/" + itemId, Map.of("include_attributes", "all"), userId);
        if (itemResp.status() == 404) return null;
        if (itemResp.status() != 200 || itemResp.data() == null) {
            throw new IllegalStateException("Falha consultando o anuncio " + itemId + " (HTTP " + itemResp.status() + ")");
        }
        JsonNode item = itemResp.data();
        JsonNode sellerId = item.get("seller_id");
        if (sellerId != null && sellerId.isNumber() && sellerId.asLong() != userId) return null;
        String status = item.path("status").asText(null);
        if (!"active".equals(status) && !"paused".equals(status)) return null;

        MeliResponse catalogResp = client.get("/catalog_quality/status",
                Map.of("item_id", itemId, "v", "3"), userId);
        if (catalogResp.status() != 200 || catalogResp.data() == null) {
            throw new IllegalStateException("Falha consultando os atributos de " + itemId
                    + " (HTTP " + catalogResp.status() + ")");
        }
        Set<String> missingAttrs = missingFromAdoption(catalogResp.data().path("adoption_status"));
        ObjectNode audited = auditItem(item, userId, missingAttrs);

        // Performance e descrição são endpoints independentes. Se um deles estiver
        // indisponível, preserva só os problemas daquela fonte até a próxima
        // tentativa, em vez de sinalizar resolução.
        ObjectNode snapshot = load(userId);
        JsonNode previous = null;
        if (snapshot != null) {
            for (JsonNode row : snapshot.path("items")) {
                if (itemId.equals(row.path("id").asText())) { previous = row; break; }
            }
        }
        Map<String, JsonNode> issues = new LinkedHashMap<>();
        for (JsonNode issue : audited.path("issues")) issues.put(issue.path("key").asText(), issue);
        if (previous != null) {
            boolean perfAvailable = audited.path("performance_available").asBoolean();
            boolean descChecked = audited.path("description_checked").asBoolean();
            for (JsonNode issue : previous.path("issues")) {
                String type = issue.path("type").asText();
                boolean preserve = ((type.equals("clip") || type.equals("picture")) && !perfAvailable)
                        || (type.equals("description") && !descChecked);
                String key = issue.path("key").asText(null);
                if (preserve && key != null && !key.isBlank()) issues.put(key, issue);
            }
        }
        audited.set("issues", sortedIssues(issues.values()));
        return audited;
    }

    /** Executa a auditoria completa, salvando checkpoints a cada lote. */
    public ObjectNode build(long userId, String nickname) {
        String startedAt = nowIso();
        ObjectNode previous = load(userId);
        ArrayNode previousItems = previous != null && previous.path("items").isArray()
                ? (ArrayNode) previous.get("items") : M.createArrayNode();
        boolean keepPreviousVisible = !previousItems.isEmpty();

        ObjectNode snapshot = M.createObjectNode();
        snapshot.put("user_id", userId);
        snapshot.put("nickname", nickname);
        snapshot.put("status", "running");
        snapshot.put("started_at", startedAt);
        snapshot.putNull("scanned_at");
        snapshot.put("processed", 0);
        snapshot.put("total", 0);
        snapshot.set("warnings", M.createArrayNode());
        snapshot.set("validating_item_ids", M.createArrayNode());
        snapshot.set("items", previousItems.deepCopy());
        snapshot.set("summary", summarize(previousItems));
        save(snapshot);

        List<String> ids = client.scanAllItems(userId, null, 100);
        List<JsonNode> raw = new ArrayList<>();
        for (JsonNode it : client.multiGetItems(ids, QUALITY_FIELDS, userId)) {
            String st = it.path("status").asText("");
            if (!it.path("id").asText("").isEmpty() && (st.equals("active") || st.equals("paused"))) {
                raw.add(it);
            }
        }
        snapshot.put("total", raw.size());
        save(snapshot);

        Map<String, Set<String>> missingByItem = new LinkedHashMap<>();
        String catalogWarning = catalogReport(userId, missingByItem);
        if (catalogWarning != null) snapshot.withArray("warnings").add(catalogWarning);

        ArrayNode auditedItems = M.createArrayNode();
        int batchSize = 50;
        for (int start = 0; start < raw.size(); start += batchSize) {
            List<JsonNode> batch = raw.subList(start, Math.min(start + batchSize, raw.size()));
            for (JsonNode item : batch) {
                Set<String> missing = missingByItem.getOrDefault(item.path("id").asText(""), Set.of());
                auditedItems.add(auditItem(item, userId, missing));
            }
            snapshot.put("processed", auditedItems.size());
            if (!keepPreviousVisible) {
                snapshot.set("items", auditedItems.deepCopy());
                snapshot.set("summary", summarize(auditedItems));
            }
            save(snapshot);
        }

        snapshot.put("status", "complete");
        snapshot.put("scanned_at", nowIso());
        snapshot.set("items", auditedItems);
        snapshot.set("summary", summarize(auditedItems));
        save(snapshot);
        log.info("quality: auditoria concluida user={} itens={}", userId, raw.size());
        return snapshot;
    }

    private String catalogReport(long userId, Map<String, Set<String>> out) {
        try {
            MeliResponse resp = client.get("/catalog_quality/status",
                    Map.of("seller_id", String.valueOf(userId), "include_items", "true", "v", "3"), userId);
            if (resp.status() != 200) {
                return "Qualidade de atributos indisponivel (HTTP " + resp.status() + ").";
            }
            JsonNode data = resp.data();
            if (data == null || !data.isObject()) return "Resposta invalida da qualidade de atributos.";
            for (JsonNode domain : data.path("domains")) {
                for (JsonNode item : domain.path("items")) {
                    String itemId = item.path("item_id").asText("");
                    if (itemId.isEmpty()) continue;
                    Set<String> clean = missingFromAdoption(item.path("adoption_status"));
                    if (!clean.isEmpty()) out.computeIfAbsent(itemId, k -> new LinkedHashSet<>()).addAll(clean);
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("quality: catalog report user={} falhou: {}", userId, e.getMessage());
            return "Não foi possível consultar os atributos incompletos.";
        }
    }

    private ObjectNode auditItem(JsonNode item, long userId, Set<String> missingAttrs) {
        String itemId = item.path("id").asText("");
        Map<String, ObjectNode> issues = new LinkedHashMap<>();
        for (String attrId : new TreeSet<>(missingAttrs)) {
            String key = "attribute:" + attrId;
            ObjectNode issue = M.createObjectNode();
            issue.put("key", key);
            issue.put("label", humanizeAttribute(attrId));
            issue.put("type", "attribute");
            issue.put("attribute_id", attrId);
            issues.put(key, issue);
        }

        // ponytail: sequencial (o gather do Python era limitado pelo mesmo semáforo do limiter).
        MeliResponse perfResp = safeGet("/item/" + itemId + "/performance", userId);
        MeliResponse descResp = safeGet("/items/" + itemId + "/description", userId);

        Double score = null;
        String level = null;
        boolean performanceAvailable = false;
        boolean videoApplicable = false;
        if (perfResp != null && perfResp.status() == 200 && perfResp.data() != null) {
            performanceAvailable = true;
            JsonNode perfData = perfResp.data();
            if (perfData.hasNonNull("score")) score = perfData.path("score").asDouble();
            level = firstText(perfData, "level_wording", "level");
            videoApplicable = performanceIssues(perfData, issues);
        }

        boolean descriptionChecked = descResp != null && (descResp.status() == 200 || descResp.status() == 404);
        if (descriptionChecked) {
            JsonNode descData = descResp.data();
            String plain = descData != null && descData.isObject() ? descData.path("plain_text").asText("") : "";
            if (plain == null || plain.isBlank()) {
                ObjectNode issue = M.createObjectNode();
                issue.put("key", "description");
                issue.put("label", "Sem descrição");
                issue.put("type", "description");
                issues.put("description", issue);
            }
        }

        ObjectNode out = M.createObjectNode();
        out.put("id", itemId);
        out.put("title", item.path("title").asText(""));
        out.put("sku", extractSku(item));
        out.set("thumbnail", nullable(item.get("thumbnail")));
        out.set("permalink", nullable(item.get("permalink")));
        out.set("status", nullable(item.get("status")));
        out.set("category_id", nullable(item.get("category_id")));
        out.set("listing_type_id", nullable(item.get("listing_type_id")));
        out.set("user_product_id", nullable(item.get("user_product_id")));
        if (score != null) out.put("score", score); else out.putNull("score");
        if (level != null) out.put("level", level); else out.putNull("level");
        out.put("performance_available", performanceAvailable);
        out.put("description_checked", descriptionChecked);
        out.put("video_applicable", videoApplicable);
        out.set("issues", sortedIssues(issues.values()));
        return out;
    }

    // ---------- parsing das fontes do ML ----------

    /** Extrai objetivos pendentes relevantes; retorna se vídeo é aplicável. */
    static boolean performanceIssues(JsonNode data, Map<String, ObjectNode> found) {
        boolean[] videoApplicable = {false};
        visitPerformance(data, found, videoApplicable);
        return videoApplicable[0];
    }

    private static void visitPerformance(JsonNode node, Map<String, ObjectNode> found, boolean[] videoApplicable) {
        if (node == null) return;
        if (node.isArray()) {
            for (JsonNode child : node) visitPerformance(child, found, videoApplicable);
            return;
        }
        if (!node.isObject()) return;

        String key = firstText(node, "key", "id");
        String title = node.path("title").asText(null);
        if (title == null) title = node.path("wordings").path("title").asText(null);
        if (title == null) title = node.path("name").asText("");
        String[] classified = classifyQualityKey(key == null ? "" : key, title == null ? "" : title);
        if (classified != null && classified[0].equals("clip")) videoApplicable[0] = true;
        if (classified != null && nodePending(node)) {
            ObjectNode issue = M.createObjectNode();
            issue.put("key", classified[0]);
            issue.put("label", classified[1]);
            issue.put("type", classified[2]);
            found.put(classified[0], issue);
        }
        for (String childKey : List.of("buckets", "variables", "rules", "actions", "goals")) {
            visitPerformance(node.get(childKey), found, videoApplicable);
        }
    }

    /** Converte uma regra pendente do /performance num problema da UI. */
    private static String[] classifyQualityKey(String key, String title) {
        String haystack = (key + " " + title).toUpperCase();
        if (containsAny(haystack, "DESCRIPTION", "DESCRICAO", "DESCRIÇÃO")) {
            return new String[]{"description", "Sem descrição", "description"};
        }
        if (containsAny(haystack, "VIDEO", "CLIP")) {
            return new String[]{"clip", "Sem clipe", "clip"};
        }
        if (containsAny(haystack, "PICTURE", "PHOTO", "IMAGE", "FOTO", "IMAGEM")) {
            return new String[]{"pictures", "Fotos a melhorar", "picture"};
        }
        return null;
    }

    private static boolean nodePending(JsonNode node) {
        String status = node.path("status").asText("").toUpperCase();
        if (!status.isEmpty()) return status.equals("PENDING");
        JsonNode progress = node.get("progress");
        if (progress == null || progress.isNull()) return false;
        try {
            return progress.asDouble() < 1;
        } catch (Exception e) {
            return false;
        }
    }

    static Set<String> missingFromAdoption(JsonNode adoption) {
        if (adoption == null || !adoption.isObject()) return Set.of();
        Set<String> missing = new LinkedHashSet<>();
        JsonNode all = adoption.path("all").path("missing_attributes");
        if (all.isArray()) {
            for (JsonNode a : all) addClean(missing, a);
        } else {
            for (String section : List.of("pi", "ft", "required")) {
                for (JsonNode a : adoption.path(section).path("missing_attributes")) addClean(missing, a);
            }
        }
        return missing;
    }

    // ---------- helpers ----------

    private static void addClean(Set<String> set, JsonNode node) {
        String v = node.asText("").trim();
        if (!v.isEmpty()) set.add(v);
    }

    private static String humanizeAttribute(String attributeId) {
        if (ATTRIBUTE_LABELS.containsKey(attributeId)) return ATTRIBUTE_LABELS.get(attributeId);
        String words = attributeId.replace("_", " ").trim().toLowerCase();
        if (words.isEmpty()) return "Atributo";
        return words.substring(0, 1).toUpperCase() + words.substring(1);
    }

    private MeliResponse safeGet(String path, long userId) {
        try {
            return client.get(path, userId);
        } catch (Exception e) {
            log.debug("quality: get {} user={} falhou: {}", path, userId, e.getMessage());
            return null;
        }
    }

    private static ArrayNode sortedIssues(java.util.Collection<? extends JsonNode> issues) {
        List<JsonNode> list = new ArrayList<>(issues);
        list.sort(Comparator.<JsonNode, String>comparing(i -> i.path("type").asText())
                .thenComparing(i -> i.path("label").asText()));
        ArrayNode out = M.createArrayNode();
        list.forEach(out::add);
        return out;
    }

    private static String extractSku(JsonNode item) {
        for (JsonNode attr : item.path("attributes")) {
            if ("SELLER_SKU".equals(attr.path("id").asText()) && attr.hasNonNull("value_name")) {
                return attr.get("value_name").asText().trim();
            }
        }
        String scf = item.path("seller_custom_field").asText(null);
        return scf == null ? "" : scf.trim();
    }

    private static Set<String> validatingIds(ObjectNode snapshot) {
        Set<String> out = new TreeSet<>();
        for (JsonNode id : snapshot.path("validating_item_ids")) {
            if (!id.asText("").isEmpty()) out.add(id.asText());
        }
        return out;
    }

    private static void setValidatingIds(ObjectNode snapshot, Set<String> ids) {
        ArrayNode arr = M.createArrayNode();
        new TreeSet<>(ids).forEach(arr::add);
        snapshot.set("validating_item_ids", arr);
    }

    private static int indexOfItem(ArrayNode items, String itemId) {
        for (int i = 0; i < items.size(); i++) {
            if (itemId.equals(items.get(i).path("id").asText())) return i;
        }
        return -1;
    }

    private static Set<String> cleanSet(List<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) if (id != null && !id.isBlank()) out.add(id);
        return out;
    }

    private static boolean containsAny(String haystack, String... tokens) {
        for (String t : tokens) if (haystack.contains(t)) return true;
        return false;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && !v.isNull() && !v.asText("").isBlank()) return v.asText();
        }
        return null;
    }

    private static JsonNode nullable(JsonNode node) {
        return node == null || node.isNull() ? M.nullNode() : node;
    }

    private static String nowIso() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
