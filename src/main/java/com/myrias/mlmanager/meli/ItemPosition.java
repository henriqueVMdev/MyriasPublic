package com.myrias.mlmanager.meli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Posições de peça automotiva do Mercado Livre.
 *
 * Os mesmos 4 ids chegam por três caminhos — {@code value_id}, {@code value_name}
 * e o título do anúncio — e antes viviam em quatro tabelas espalhadas por
 * MeliBulkService e MeliCloneService, que já tinham divergido (uma resolvia por
 * value_id, a outra não). Tabela única: id, rótulo, radical do nome e o regex do
 * título ficam lado a lado.
 */
enum ItemPosition {
    DIANTEIRA("13701104", "Dianteira", "dianteir",
            "\\bdianteir[oa]s?\\b|\\bdiant\\.?\\b|\\bfrontal\\b|\\bfront\\b"),
    TRASEIRA("13701105", "Traseira", "traseir",
            "\\btraseir[oa]s?\\b|\\btras\\.?\\b|\\brear\\b"),
    ESQUERDA("2262158", "Esquerda", "esquerd",
            "\\besquerd[oa]s?\\b|\\besq\\.?\\b|\\bl\\.?h\\.?\\b|\\bleft\\b"),
    DIREITA("2262160", "Direita", "direit",
            "\\bdireit[oa]s?\\b|\\bdir\\.?\\b|\\br\\.?h\\.?\\b|\\bright\\b");

    private final String id;
    private final String label;
    private final String stem;
    private final Pattern titlePattern;

    ItemPosition(String id, String label, String stem, String titleRegex) {
        this.id = id;
        this.label = label;
        this.stem = stem;
        this.titlePattern = Pattern.compile(titleRegex);
    }

    String id() { return id; }

    String label() { return label; }

    /** Como o ML espera a posição no payload de compatibilidades. */
    Map<String, Object> asValue() {
        return Map.of("value_id", id, "value_name", label);
    }

    /** Reconhece pelo nome (radical, tolerante a gênero) e, se falhar, pelo value_id. */
    static ItemPosition resolve(Map<String, Object> position) {
        Object rawName = position.get("value_name");
        String stem = (rawName == null ? "" : String.valueOf(rawName))
                .toLowerCase(Locale.ROOT).strip().replaceAll("[aeiou]$", "");
        for (ItemPosition p : values()) {
            if (stem.endsWith(p.stem)) return p;
        }
        Object rawId = position.get("value_id");
        String id = rawId == null ? "" : String.valueOf(rawId);
        for (ItemPosition p : values()) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /** Posições que o próprio título já denuncia ("Farol Dianteiro Direito"). */
    static List<ItemPosition> fromTitle(String title) {
        String text = title == null ? "" : title.toLowerCase(Locale.ROOT);
        List<ItemPosition> out = new ArrayList<>();
        for (ItemPosition p : values()) {
            if (p.titlePattern.matcher(text).find()) out.add(p);
        }
        return out;
    }
}
