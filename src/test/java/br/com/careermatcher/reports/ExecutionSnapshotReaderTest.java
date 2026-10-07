package br.com.careermatcher.reports;

import br.com.careermatcher.matching.execution.ExecutionObjectStorage;
import br.com.careermatcher.matching.execution.GzipExecutionCompressor;
import br.com.careermatcher.models.MatchingExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionSnapshotReaderTest {
    private final ExecutionObjectStorage storage = mock(ExecutionObjectStorage.class);
    private final ExecutionSnapshotReader reader = new ExecutionSnapshotReader(storage,
            List.of(new GzipExecutionCompressor()), new ObjectMapper());

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void readsSupportedSnapshotsWithStructuredRequirementsAndOptionalContacts(int version) throws Exception {
        useJson("""
                [{"id":10,"role":"Engenheiro","seniority":"SENIOR","city":"Recife","modalidade":"REMOTO",
                  "description":"Equipe de plataforma",
                  "skillRequirements":[{"required":true,"skill":{"name":"Java"}},
                    {"required":false,"skill":{"name":"Docker"}},{"required":true,"skill":{"name":"SQL"}}],
                  "experienceRequirement":{"required":true,"experience":{"role":"Backend","seniority":"PLENO",
                    "description":"APIs","durationMonths":24,"skills":[{"skill":{"name":"Neo4j"}}]}},
                  "languageRequirements":[{"required":false,"language":{"name":"Inglês"},"level":3}],
                  "undergraduateRequirements":[{"required":true,"undergraduate":{"field":"Computação","undergraduateType":"BACHARELADO"}}],
                  "graduateRequirements":[{"required":false,"graduate":{"degree":"MASTERS","field":"Computação","subarea":"IA"}}],
                  "candidates":[{"id":20,"name":"Ana","professionalTitle":"Desenvolvedora","role":"campo antigo",
                    "phone":"81999999999","email":"ana@example.test"},{"id":21,"name":"Bruno"}]}]
                """);

        var payload = reader.read(17, execution(version, "gzip"));
        assertEquals(17, payload.companyId());
        assertEquals("RUN-1", payload.executionId());
        var vacancy = payload.vacancies().getFirst();
        assertEquals(10, vacancy.id());
        assertEquals("Engenheiro", vacancy.role());
        assertEquals("Recife", vacancy.city());
        assertEquals("REMOTO", vacancy.modalidade());
        assertEquals(2, vacancy.allocatedCount());
        assertEquals("Equipe de plataforma. "
                + "Para assumir esta posição, é necessário que o(a) candidato(a) possua Java, SQL como habilidades principais. "
                + "Como diferenciais, são desejáveis conhecimentos em Docker. "
                + "É obrigatório que possua 2 anos de experiência profissional comprovada como Backend. "
                + "Também é obrigatório que seja bacharel em Computação. "
                + "Considerando esta posição, é recomendado que tenha como formação acadêmica mestrado em Computação — IA. "
                + "Por fim, é indicado que tenha nível intermediário em Inglês.", vacancy.description());
        assertEquals(new ReportPayload.Candidate(20, "Ana", "Desenvolvedora", "81999999999", "ana@example.test"),
                vacancy.candidates().getFirst());
        assertEquals(new ReportPayload.Candidate(21, "Bruno", null, null, null), vacancy.candidates().getLast());
        verify(storage).get("private-bucket", "companies/17/executions/RUN-1.json.gz");
    }

    @ParameterizedTest
    @CsvSource({"0, 0 meses", "1, 1 mês", "12, 12 meses", "13, 1 ano e 1 mês", "18, 1 ano e 6 meses", "24, 2 anos"})
    void describesExactExperienceDurationWithoutRounding(int months, String expected) throws Exception {
        String description = descriptionFor("""
                "experienceRequirement":{"required":true,"experience":{"role":"Analista","durationMonths":%d}}
                """.formatted(months));
        assertEquals("É obrigatório que possua " + expected + " de experiência profissional comprovada como Analista.",
                description);
    }

    @Test void recommendsDesiredExperience() throws Exception {
        assertEquals("É recomendado que possua 1 ano e 6 meses de experiência profissional comprovada como Analista.",
                descriptionFor("""
                        "experienceRequirement":{"required":false,"experience":{"role":"Analista","durationMonths":18}}
                        """));
    }

    @Test void translatesEveryQualificationAndLanguageLevelWhileRespectingIndividualNecessity() throws Exception {
        String description = descriptionFor("""
                "undergraduateRequirements":[
                  {"required":true,"undergraduate":{"undergraduateType":"BACHARELADO","field":"Computação"}},
                  {"required":false,"undergraduate":{"undergraduateType":"LICENCIATURA","field":"Matemática"}},
                  {"required":true,"undergraduate":{"undergraduateType":"TECNOLOGO","field":"Redes"}}],
                "graduateRequirements":[
                  {"required":false,"graduate":{"degree":"SPECIALIZATION","field":"Computação","subarea":"IA"}},
                  {"required":true,"graduate":{"degree":"MASTERS","field":"Estatística"}},
                  {"required":false,"graduate":{"degree":"MBA","field":"Gestão"}},
                  {"required":true,"graduate":{"degree":"PHD","field":"Física"}}],
                "languageRequirements":[
                  {"required":false,"language":{"name":"Francês"},"level":1.0},
                  {"required":true,"language":{"name":"Espanhol"},"level":3.0},
                  {"required":false,"language":{"name":"Inglês"},"level":6.0},
                  {"required":true,"language":{"name":"Português"},"level":9.0}]
                """);
        assertEquals("É obrigatório que seja bacharel em Computação; é desejável que seja licenciado(a) em Matemática; "
                + "é obrigatório que seja tecnólogo(a) em Redes. "
                + "Considerando esta posição, é recomendado que tenha como formação acadêmica especialização em Computação — IA; "
                + "é obrigatório que tenha como formação acadêmica mestrado em Estatística; "
                + "é recomendado que tenha como formação acadêmica MBA em Gestão; "
                + "é obrigatório que tenha como formação acadêmica doutorado em Física. "
                + "Por fim, é indicado que tenha nível iniciante em Francês; é obrigatório que tenha nível intermediário em Espanhol; "
                + "é indicado que tenha nível avançado em Inglês; é obrigatório que tenha nível fluente em Português.", description);
    }

    @Test void usesReadableFragmentsForPartialRequirements() throws Exception {
        assertEquals("É recomendado que possua experiência profissional comprovada como Analista. "
                + "Também é desejável que possua graduação em Computação; é obrigatório que seja bacharel. "
                + "Considerando esta posição, é recomendado que tenha como formação acadêmica pós-graduação em IA; "
                + "é obrigatório que tenha como formação acadêmica mestrado. "
                + "Por fim, é indicado que tenha conhecimentos em Inglês.", descriptionFor("""
                "experienceRequirement":{"experience":{"role":"Analista"}},
                "undergraduateRequirements":[{"undergraduate":{"field":"Computação"}},
                  {"required":true,"undergraduate":{"undergraduateType":"BACHARELADO"}}],
                "graduateRequirements":[{"graduate":{"subarea":"IA"}},
                  {"required":true,"graduate":{"degree":"MASTERS"}}],
                "languageRequirements":[{"language":{"name":"Inglês"}},{"required":true,"level":9}]
                """));
        assertEquals("É recomendado que possua 12 meses de experiência profissional comprovada.", descriptionFor("""
                "experienceRequirement":{"experience":{"durationMonths":12}}
                """));
    }

    @Test void omitsEmptyRequirementsAndAvoidsConnectorsWithoutEarlierContent() throws Exception {
        assertEquals("Nenhum requisito informado.", descriptionFor("""
                "description":"  ", "skillRequirements":[{"skill":{"name":"  "}},{"required":true}],
                "experienceRequirement":{"experience":{}}, "undergraduateRequirements":[{"undergraduate":{}}],
                "graduateRequirements":[{}], "languageRequirements":[{"language":{"name":" "},"level":3}]
                """));
        assertEquals("É indicado que tenha nível avançado em Inglês.", descriptionFor("""
                "languageRequirements":[{"language":{"name":"Inglês"},"level":6}]
                """));
        assertEquals("Como diferenciais, são desejáveis conhecimentos em Docker.", descriptionFor("""
                "skillRequirements":[{"skill":{"name":"Docker"}}]
                """));
        assertEquals("Para assumir esta posição, é necessário que o(a) candidato(a) possua Java como habilidade principal.",
                descriptionFor("""
                        "skillRequirements":[{"required":true,"skill":{"name":"Java"}}]
                        """));
    }

    @Test void normalizesWhitespaceAndPreservesFreeDescriptionPunctuation() throws Exception {
        assertEquals("Equipe de plataforma. Trabalho remoto! Por fim, é indicado que tenha conhecimentos em Inglês.",
                descriptionFor("""
                        "description":"  Equipe  de plataforma.\\nTrabalho\\tremoto!  ",
                        "languageRequirements":[{"language":{"name":"  Inglês "}}]
                        """));
        assertEquals("Equipe de plataforma.", descriptionFor("\"description\":\"Equipe de plataforma\""));
    }

    @ParameterizedTest
    @CsvSource({"MESTRADO, mestrado", "ESPECIALIZACAO, especialização", "DOUTORADO, doutorado"})
    void translatesLegacyGraduateLabels(String label, String expected) throws Exception {
        assertEquals("Considerando esta posição, é recomendado que tenha como formação acadêmica " + expected + " em Computação.",
                descriptionFor("""
                        "graduateRequirements":[{"graduate":{"degree":"%s","field":"Computação"}}]
                        """.formatted(label)));
    }

    @Test void preservesUnknownLabelsWithoutInventingTheirMeaning() throws Exception {
        assertEquals("É desejável que possua graduação do tipo OUTRO em Computação. "
                + "Considerando esta posição, é recomendado que tenha como formação acadêmica Pesquisa em IA. "
                + "Por fim, é indicado que tenha nível C1 em Inglês; é obrigatório que tenha nível 2.5 em Espanhol.",
                descriptionFor("""
                        "undergraduateRequirements":[{"undergraduate":{"undergraduateType":"OUTRO","field":"Computação"}}],
                        "graduateRequirements":[{"graduate":{"degree":"Pesquisa","field":"IA"}}],
                        "languageRequirements":[{"language":{"name":"Inglês"},"level":"C1"},
                          {"required":true,"language":{"name":"Espanhol"},"level":2.5}]
                        """));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "-1", "1.5", "\"desconhecido\""})
    void omitsUnusableExperienceDurations(String duration) throws Exception {
        assertEquals("É recomendado que possua experiência profissional comprovada como Analista.", descriptionFor("""
                "experienceRequirement":{"experience":{"role":"Analista","durationMonths":%s}}
                """.formatted(duration)));
    }

    @Test void readsHistoricalRoleOnlyForVersionOne() throws Exception {
        useJson("""
                [{"id":10,"role":"Engenheiro","candidates":[{"id":20,"name":"Ana","role":"Analista"}]}]
                """);
        var historical = reader.read(17, execution(1, "gzip")).vacancies().getFirst();
        assertEquals("Analista", historical.candidates().getFirst().professionalTitle());
        assertEquals("Nenhum requisito informado.", historical.description());
        assertNull(reader.read(17, execution(2, "gzip")).vacancies().getFirst().candidates().getFirst().professionalTitle());
    }

    @Test void acceptsEmptyExecutionAndVacancyWithoutCandidates() throws Exception {
        useJson("[]");
        assertTrue(reader.read(17, execution(2, "gzip")).vacancies().isEmpty());
        useJson("[{\"id\":10,\"role\":\"Engenheiro\"}]");
        var vacancy = reader.read(17, execution(2, "gzip")).vacancies().getFirst();
        assertEquals(0, vacancy.allocatedCount());
        assertTrue(vacancy.candidates().isEmpty());
    }

    @Test void rejectsUnsupportedMetadataBeforeReadingObjectStorage() {
        assertThrows(IOException.class, () -> reader.read(17, execution(3, "gzip")));
        assertThrows(IOException.class, () -> reader.read(17, execution(2, "zip")));
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}", "null", "[] []", "[null]",
            "[{\"id\":-1,\"role\":\"Engenheiro\"}]", "[{\"id\":1.5,\"role\":\"Engenheiro\"}]",
            "[{\"id\":9223372036854775808,\"role\":\"Engenheiro\"}]", "[{\"id\":\"10\",\"role\":\"Engenheiro\"}]",
            "[{\"id\":10,\"role\":\" \"}]", "[{\"id\":10,\"role\":\"Engenheiro\",\"candidates\":{}}]",
            "[{\"id\":10,\"role\":\"Engenheiro\",\"skillRequirements\":false}]",
            "[{\"id\":10,\"role\":\"Engenheiro\",\"languageRequirements\":{}}]",
            "[{\"id\":10,\"role\":\"Engenheiro\",\"undergraduateRequirements\":false}]",
            "[{\"id\":10,\"role\":\"Engenheiro\",\"graduateRequirements\":{}}]",
            "[{\"id\":10,\"role\":\"Engenheiro\",\"experienceRequirement\":{\"experience\":{\"skills\":{}}}}]",
            "[{\"id\":10,\"role\":\"Engenheiro\",\"candidates\":[{\"id\":20}]}]"})
    void rejectsMalformedOrInvalidSnapshot(String json) throws Exception {
        useJson(json);
        assertThrows(IOException.class, () -> reader.read(17, execution(2, "gzip")));
    }

    @Test void rejectsInvalidGzipAndClosesTheObjectStream() throws Exception {
        var input = spy(new ByteArrayInputStream("not gzip".getBytes(StandardCharsets.UTF_8)));
        when(storage.get(anyString(), anyString())).thenReturn(input);
        assertThrows(IOException.class, () -> reader.read(17, execution(2, "gzip")));
        verify(input).close();
    }

    @Test void boundsCompressedInputAndClosesItOnFailure() throws Exception {
        var input = spy(repeatedBytes(16 * 1024 * 1024 + 1));
        when(storage.get(anyString(), anyString())).thenReturn(input);
        var error = assertThrows(IOException.class, () -> reader.read(17, execution(2, "gzip")));
        assertTrue(error.getMessage().contains("limite"));
        verify(input).close();
    }

    @Test void boundsExpandedGzipToPreventCompressionBombs() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output); var input = repeatedBytes(64 * 1024 * 1024 + 1)) {
            input.transferTo(gzip);
        }
        byte[] compressed = output.toByteArray();
        assertTrue(compressed.length < 100_000);
        when(storage.get(anyString(), anyString())).thenReturn(new ByteArrayInputStream(compressed));
        var error = assertThrows(IOException.class, () -> reader.read(17, execution(2, "gzip")));
        assertTrue(error.getMessage().contains("limite"));
    }

    private void useJson(String json) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        byte[] compressed = output.toByteArray();
        when(storage.get(anyString(), anyString())).thenAnswer(invocation -> new ByteArrayInputStream(compressed));
    }

    private String descriptionFor(String fields) throws IOException {
        useJson("[{\"id\":10,\"role\":\"Analista\"," + fields + "}]");
        return reader.read(17, execution(2, "gzip")).vacancies().getFirst().description();
    }

    static MatchingExecution execution(int version, String compression) {
        return new MatchingExecution(9L, "RUN-1", Instant.parse("2026-10-03T12:00:00Z"), 1, 2,
                "private-bucket", "companies/17/executions/RUN-1.json.gz", compression, version);
    }

    static InputStream repeatedBytes(int length) {
        return new InputStream() {
            private int remaining = length;
            @Override public int read() { return remaining-- > 0 ? ' ' : -1; }
            @Override public int read(byte[] buffer, int offset, int count) {
                if (remaining <= 0) return -1;
                int actual = Math.min(remaining, count);
                java.util.Arrays.fill(buffer, offset, offset + actual, (byte) ' ');
                remaining -= actual;
                return actual;
            }
        };
    }
}
