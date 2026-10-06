package br.com.careermatcher.reports;

import br.com.careermatcher.matching.execution.ExecutionCompressor;
import br.com.careermatcher.matching.execution.ExecutionObjectStorage;
import br.com.careermatcher.models.MatchingExecution;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.*;

@Component
public class ExecutionSnapshotReader {
    private static final int MAX_COMPRESSED = 16 * 1024 * 1024;
    private static final int MAX_EXPANDED = 64 * 1024 * 1024;
    private final ExecutionObjectStorage storage;
    private final List<ExecutionCompressor> compressors;
    private final ObjectReader reader;

    public ExecutionSnapshotReader(ExecutionObjectStorage storage, List<ExecutionCompressor> compressors, ObjectMapper mapper) {
        this.storage = storage;
        this.compressors = List.copyOf(compressors);
        this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public ReportPayload read(long companyId, MatchingExecution execution) throws IOException {
        if (execution.getSchemaVersion() != 1 && execution.getSchemaVersion() != 2)
            throw new IOException("Versão do documento não suportada.");
        var compressor = compressors.stream().filter(c -> c.format().equals(execution.getCompression())).findFirst()
                .orElseThrow(() -> new IOException("Compactação não suportada."));
        byte[] compressed;
        try (var stream = storage.get(execution.getBucket(), execution.getObjectKey())) {
            compressed = boundedBytes(stream, MAX_COMPRESSED);
        }
        JsonNode document;
        try (var stream = compressor.decompress(new ByteArrayInputStream(compressed))) {
            document = reader.readTree(boundedBytes(stream, MAX_EXPANDED));
        }
        if (document == null || !document.isArray()) throw new IOException("Documento de execução inválido.");
        List<ReportPayload.Vacancy> vacancies = new ArrayList<>();
        for (var vacancy : document) {
            List<ReportPayload.Candidate> candidates = new ArrayList<>();
            for (var candidate : array(vacancy.path("candidates"))) {
                String title = text(candidate, "professionalTitle");
                if (title == null && execution.getSchemaVersion() == 1) title = text(candidate, "role");
                candidates.add(new ReportPayload.Candidate(id(candidate), requiredText(candidate, "name"), title,
                        text(candidate, "phone"), text(candidate, "email")));
            }
            vacancies.add(new ReportPayload.Vacancy(id(vacancy), requiredText(vacancy, "role"),
                    text(vacancy, "seniority"), text(vacancy, "city"), text(vacancy, "modalidade"),
                    description(vacancy), candidates.size(), List.copyOf(candidates)));
        }
        return new ReportPayload(companyId, execution.getExecutionId(), List.copyOf(vacancies));
    }

    private static byte[] boundedBytes(InputStream stream, int maximum) throws IOException {
        byte[] data = stream.readNBytes(maximum + 1);
        if (data.length > maximum) throw new IOException("Documento excede o limite de leitura.");
        return data;
    }

    private static Iterable<JsonNode> array(JsonNode value) throws IOException {
        if (value.isMissingNode() || value.isNull()) return List.of();
        if (!value.isArray()) throw new IOException("Lista inválida no documento de execução.");
        return value;
    }

    private static long id(JsonNode value) throws IOException {
        var id = value.path("id");
        if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() < 0)
            throw new IOException("Identificador inválido no documento de execução.");
        return id.longValue();
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        String value = text(node, field);
        if (value == null || value.isBlank()) throw new IOException("Campo obrigatório ausente no documento.");
        return value;
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isValueNode() && !value.isNull() ? value.asText() : null;
    }

    private static String description(JsonNode vacancy) throws IOException {
        List<String> lines = new ArrayList<>();
        String description = text(vacancy, "description");
        if (description != null && !description.isBlank()) lines.add(description);
        for (var requirement : array(vacancy.path("skillRequirements")))
            add(lines, requirement, "Competência", text(requirement.path("skill"), "name"));
        var experienceRequirement = vacancy.path("experienceRequirement");
        var experience = experienceRequirement.path("experience");
        if (experience.isObject()) {
            List<String> values = new ArrayList<>();
            for (String field : List.of("role", "seniority", "description")) {
                String value = text(experience, field);
                if (value != null && !value.isBlank()) values.add(value);
            }
            String months = text(experience, "durationMonths");
            if (months != null) values.add(months + " meses");
            for (var skill : array(experience.path("skills"))) {
                String value = text(skill.path("skill"), "name");
                if (value != null) values.add(value);
            }
            add(lines, experienceRequirement, "Experiência", String.join("; ", values));
        }
        for (var requirement : array(vacancy.path("languageRequirements"))) {
            String name = text(requirement.path("language"), "name");
            String level = text(requirement, "level");
            if (name != null && level != null) name += " (nível " + level + ")";
            add(lines, requirement, "Idioma", name);
        }
        for (var requirement : array(vacancy.path("undergraduateRequirements")))
            add(lines, requirement, "Graduação", fields(requirement.path("undergraduate"), "field", "undergraduateType"));
        for (var requirement : array(vacancy.path("graduateRequirements")))
            add(lines, requirement, "Pós-graduação", fields(requirement.path("graduate"), "degree", "field", "subarea"));
        return lines.isEmpty() ? "Nenhum requisito informado." : String.join("\n", lines);
    }

    private static String fields(JsonNode node, String... names) {
        List<String> values = new ArrayList<>();
        for (String name : names) {
            String value = text(node, name);
            if (value != null && !value.isBlank()) values.add(value);
        }
        return String.join("; ", values);
    }

    private static void add(List<String> lines, JsonNode requirement, String kind, String value) {
        if (value == null || value.isBlank()) return;
        String necessity = requirement.path("required").asBoolean(false) ? "Obrigatório" : "Desejável";
        lines.add(necessity + " — " + kind + ": " + value);
    }
}
