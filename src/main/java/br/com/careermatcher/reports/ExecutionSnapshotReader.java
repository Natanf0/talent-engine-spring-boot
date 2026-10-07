package br.com.careermatcher.reports;

import br.com.careermatcher.matching.execution.ExecutionCompressor;
import br.com.careermatcher.matching.execution.ExecutionObjectStorage;
import br.com.careermatcher.models.MatchingExecution;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.math.BigDecimal;
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
        List<String> sentences = new ArrayList<>();
        addSentence(sentences, cleanText(vacancy, "description"));
        List<String> requiredSkills = new ArrayList<>();
        List<String> desiredSkills = new ArrayList<>();
        for (var requirement : array(vacancy.path("skillRequirements"))) {
            String name = cleanText(requirement.path("skill"), "name");
            if (name != null) (required(requirement) ? requiredSkills : desiredSkills).add(name);
        }
        if (!requiredSkills.isEmpty()) {
            addSentence(sentences, "Para assumir esta posição, é necessário que o(a) candidato(a) possua "
                    + String.join(", ", requiredSkills)
                    + (requiredSkills.size() == 1 ? " como habilidade principal" : " como habilidades principais"));
        }
        if (!desiredSkills.isEmpty()) {
            addSentence(sentences, "Como diferenciais, são desejáveis conhecimentos em "
                    + String.join(", ", desiredSkills));
        }

        var experienceRequirement = vacancy.path("experienceRequirement");
        var experience = experienceRequirement.path("experience");
        if (experience.isObject()) {
            // Keep validating this historical snapshot field even though it is not part of the prose.
            array(experience.path("skills"));
            String role = cleanText(experience, "role");
            String duration = duration(experience.path("durationMonths"));
            if (role != null || duration != null) {
                addSentence(sentences, "É " + (required(experienceRequirement) ? "obrigatório" : "recomendado")
                        + " que possua " + (duration == null ? "" : duration + " de ")
                        + "experiência profissional comprovada" + (role == null ? "" : " como " + role));
            }
        }

        List<String> undergraduateClauses = new ArrayList<>();
        for (var requirement : array(vacancy.path("undergraduateRequirements"))) {
            var undergraduate = requirement.path("undergraduate");
            String field = cleanText(undergraduate, "field");
            String type = cleanText(undergraduate, "undergraduateType");
            if (field == null && type == null) continue;
            String qualification = type == null ? "possua graduação" : switch (type.toUpperCase(Locale.ROOT)) {
                case "BACHARELADO" -> "seja bacharel";
                case "LICENCIATURA" -> "seja licenciado(a)";
                case "TECNOLOGO", "TECNÓLOGO" -> "seja tecnólogo(a)";
                default -> "possua graduação do tipo " + type;
            };
            undergraduateClauses.add("é " + (required(requirement) ? "obrigatório" : "desejável")
                    + " que " + qualification + (field == null ? "" : " em " + field));
        }
        addClauses(sentences, undergraduateClauses, "Também ");

        List<String> graduateClauses = new ArrayList<>();
        for (var requirement : array(vacancy.path("graduateRequirements"))) {
            var graduate = requirement.path("graduate");
            String degree = cleanText(graduate, "degree");
            String field = cleanText(graduate, "field");
            String subarea = cleanText(graduate, "subarea");
            if (degree == null && field == null && subarea == null) continue;
            String qualification = degree == null ? "pós-graduação" : switch (degree.toUpperCase(Locale.ROOT)) {
                case "SPECIALIZATION", "ESPECIALIZACAO", "ESPECIALIZAÇÃO" -> "especialização";
                case "MASTERS", "MESTRADO" -> "mestrado";
                case "MBA" -> "MBA";
                case "PHD", "DOUTORADO" -> "doutorado";
                default -> degree;
            };
            String area = field == null ? subarea : field + (subarea == null ? "" : " — " + subarea);
            graduateClauses.add("é " + (required(requirement) ? "obrigatório" : "recomendado")
                    + " que tenha como formação acadêmica " + qualification + (area == null ? "" : " em " + area));
        }
        if (!graduateClauses.isEmpty()) {
            addSentence(sentences, "Considerando esta posição, " + String.join("; ", graduateClauses));
        }

        List<String> languageClauses = new ArrayList<>();
        for (var requirement : array(vacancy.path("languageRequirements"))) {
            String name = cleanText(requirement.path("language"), "name");
            if (name == null) continue;
            String level = languageLevel(requirement.path("level"));
            languageClauses.add("é " + (required(requirement) ? "obrigatório" : "indicado")
                    + " que tenha " + (level == null ? "conhecimentos" : "nível " + level) + " em " + name);
        }
        addClauses(sentences, languageClauses, "Por fim, ");
        return sentences.isEmpty() ? "Nenhum requisito informado." : String.join(" ", sentences);
    }

    private static boolean required(JsonNode requirement) {
        return requirement.path("required").asBoolean(false);
    }

    private static String cleanText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) return null;
        value = value.replaceAll("[\\s\\p{Z}]+", " ").trim();
        return value.isEmpty() ? null : value;
    }

    private static String duration(JsonNode months) {
        if (!months.isValueNode() || months.isNull()) return null;
        final long count;
        try {
            count = new BigDecimal(months.asText()).longValueExact();
        } catch (NumberFormatException | ArithmeticException ignored) {
            return null;
        }
        if (count < 0) return null;
        if (count <= 12) return count + (count == 1 ? " mês" : " meses");
        long years = count / 12;
        long remainder = count % 12;
        return years + (years == 1 ? " ano" : " anos")
                + (remainder == 0 ? "" : " e " + remainder + (remainder == 1 ? " mês" : " meses"));
    }

    private static String languageLevel(JsonNode level) {
        if (!level.isValueNode() || level.isNull()) return null;
        String value = level.asText().trim();
        if (value.isEmpty()) return null;
        try {
            value = new BigDecimal(value).stripTrailingZeros().toString();
        } catch (NumberFormatException ignored) {
            // Unknown labels from historical snapshots remain readable.
        }
        return switch (value) {
            case "1" -> "iniciante";
            case "3" -> "intermediário";
            case "6" -> "avançado";
            case "9" -> "fluente";
            default -> value;
        };
    }

    private static void addClauses(List<String> sentences, List<String> clauses, String connector) {
        if (clauses.isEmpty()) return;
        String value = String.join("; ", clauses);
        addSentence(sentences, sentences.isEmpty() ? "É" + value.substring(1) : connector + value);
    }

    private static void addSentence(List<String> sentences, String value) {
        if (value == null || value.isBlank()) return;
        char last = value.charAt(value.length() - 1);
        sentences.add(last == '.' || last == '!' || last == '?' ? value : value + ".");
    }
}
