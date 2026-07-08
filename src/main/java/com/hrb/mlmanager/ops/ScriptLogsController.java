package com.hrb.mlmanager.ops;

import com.hrb.mlmanager.auth.PanelSecurity;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Lista, baixa e apaga CSVs gerados pelos scripts. Espelho de api/script_logs.py. */
@RestController
@RequestMapping("/api/script-logs")
public class ScriptLogsController {

    private static final Pattern FILENAME_RE = Pattern.compile("^([a-zA-Z0-9_]+)_(\\d{8})_(\\d{6})\\.csv$");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Map<String, String> SCRIPT_LABELS = Map.of(
            "sync_clones", "Sincronizar clones entre contas",
            "sync_errors", "Sincronizar clones - so erros",
            "bulk_dimensions", "Atualizar medidas em massa",
            "apply_positions", "Aplicar posicoes do titulo");

    private final Path logsDir;
    private final PanelSecurity security;

    public ScriptLogsController(@Value("${scripts.logs-dir:scripts/logs}") String logsDir,
                                PanelSecurity security) {
        this.logsDir = Path.of(logsDir).toAbsolutePath().normalize();
        this.security = security;
    }

    @GetMapping("")
    public Map<String, Object> listLogs(HttpServletRequest request) throws IOException {
        security.require(request, "planilhas");
        if (!Files.exists(logsDir)) return Map.of("logs", List.of());

        List<Map<String, Object>> entries = new ArrayList<>();
        try (var stream = Files.list(logsDir)) {
            for (Path p : stream.filter(path -> path.getFileName().toString().endsWith(".csv")).toList()) {
                String name = p.getFileName().toString();
                FileMeta meta = parseFilename(name);
                String scriptKey = meta == null ? stripCsv(name) : meta.scriptKey();
                Instant dt = meta == null
                        ? Files.getLastModifiedTime(p).toInstant()
                        : meta.dateTime().atZone(ZoneId.systemDefault()).toInstant();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("filename", name);
                row.put("script_key", scriptKey);
                row.put("label", SCRIPT_LABELS.getOrDefault(scriptKey, title(scriptKey)));
                row.put("datetime", dt.toString());
                row.put("size_bytes", Files.size(p));
                row.put("row_count", countDataRows(p));
                entries.add(row);
            }
        }
        entries.sort(Comparator.comparing(e -> String.valueOf(((Map<String, Object>) e).get("datetime"))).reversed());
        return Map.of("logs", entries);
    }

    @GetMapping("/{filename}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String filename,
                                                       HttpServletRequest request) {
        security.require(request, "planilhas");
        Path path = safePath(filename);
        if (!Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Arquivo nao encontrado");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new FileSystemResource(path));
    }

    @DeleteMapping("/{filename}")
    public Map<String, Object> delete(@PathVariable String filename, HttpServletRequest request) throws IOException {
        security.require(request, "planilhas");
        Path path = safePath(filename);
        if (!Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Arquivo nao encontrado");
        Files.delete(path);
        return Map.of("deleted", filename);
    }

    private Path safePath(String filename) {
        if (filename.contains("/") || filename.contains("\\") || filename.startsWith("..") || !filename.endsWith(".csv")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome de arquivo invalido");
        }
        Path path = logsDir.resolve(filename).normalize();
        if (!path.startsWith(logsDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Caminho fora do diretorio de logs");
        }
        return path;
    }

    private static FileMeta parseFilename(String name) {
        Matcher m = FILENAME_RE.matcher(name);
        if (!m.matches()) return null;
        try {
            return new FileMeta(m.group(1), LocalDateTime.parse(m.group(2) + m.group(3), FILE_TS));
        } catch (Exception e) {
            return null;
        }
    }

    private static long countDataRows(Path path) {
        try (var lines = Files.lines(path)) {
            return Math.max(0, lines.count() - 1);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String stripCsv(String name) {
        return name.endsWith(".csv") ? name.substring(0, name.length() - 4) : name;
    }

    private static String title(String key) {
        String[] parts = key.replace('_', ' ').split(" ");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            out.add(p.isBlank() ? p : Character.toUpperCase(p.charAt(0)) + p.substring(1));
        }
        return String.join(" ", out);
    }

    private record FileMeta(String scriptKey, LocalDateTime dateTime) {}
}
