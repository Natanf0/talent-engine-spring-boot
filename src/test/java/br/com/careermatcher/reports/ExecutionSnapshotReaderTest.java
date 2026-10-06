package br.com.careermatcher.reports;

import br.com.careermatcher.matching.execution.ExecutionObjectStorage;
import br.com.careermatcher.matching.execution.GzipExecutionCompressor;
import br.com.careermatcher.models.MatchingExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

    @Test void readsCurrentSnapshotWithStructuredRequirementsAndOptionalContacts() throws Exception {
        useJson("""
                [{"id":10,"role":"Engenheiro","seniority":"SENIOR","city":"Recife","modalidade":"REMOTO",
                  "description":"Equipe de plataforma",
                  "skillRequirements":[{"required":true,"skill":{"name":"Java"}}],
                  "experienceRequirement":{"required":true,"experience":{"role":"Backend","seniority":"PLENO",
                    "description":"APIs","durationMonths":24,"skills":[{"skill":{"name":"Neo4j"}}]}},
                  "languageRequirements":[{"required":false,"language":{"name":"Inglês"},"level":3}],
                  "undergraduateRequirements":[{"required":true,"undergraduate":{"field":"Computação","undergraduateType":"BACHARELADO"}}],
                  "graduateRequirements":[{"required":false,"graduate":{"degree":"MESTRADO","field":"Computação","subarea":"IA"}}],
                  "candidates":[{"id":20,"name":"Ana","professionalTitle":"Desenvolvedora","role":"campo antigo",
                    "phone":"81999999999","email":"ana@example.test"},{"id":21,"name":"Bruno"}]}]
                """);

        var payload = reader.read(17, execution(2, "gzip"));
        assertEquals(17, payload.companyId());
        assertEquals("RUN-1", payload.executionId());
        var vacancy = payload.vacancies().getFirst();
        assertEquals(10, vacancy.id());
        assertEquals("Engenheiro", vacancy.role());
        assertEquals("Recife", vacancy.city());
        assertEquals("REMOTO", vacancy.modalidade());
        assertEquals(2, vacancy.allocatedCount());
        assertEquals("""
                Equipe de plataforma
                Obrigatório — Competência: Java
                Obrigatório — Experiência: Backend; PLENO; APIs; 24 meses; Neo4j
                Desejável — Idioma: Inglês (nível 3)
                Obrigatório — Graduação: Computação; BACHARELADO
                Desejável — Pós-graduação: MESTRADO; Computação; IA""", vacancy.description());
        assertEquals(new ReportPayload.Candidate(20, "Ana", "Desenvolvedora", "81999999999", "ana@example.test"),
                vacancy.candidates().getFirst());
        assertEquals(new ReportPayload.Candidate(21, "Bruno", null, null, null), vacancy.candidates().getLast());
        verify(storage).get("private-bucket", "companies/17/executions/RUN-1.json.gz");
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
