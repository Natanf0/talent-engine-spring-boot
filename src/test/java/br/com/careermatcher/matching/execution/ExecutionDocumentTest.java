package br.com.careermatcher.matching.execution;

import br.com.careermatcher.DTOs.crud.CrudDtos.*;
import br.com.careermatcher.DTOs.matching.*;
import br.com.careermatcher.matching.*;
import br.com.careermatcher.models.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.assertj.core.api.Assertions.*;

class ExecutionDocumentTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test void compressedDocumentRetainsCompleteGraphAndOnlyAllocatedCandidates() throws Exception {
        ObjectNode input = fixture("candidate");
        input.remove("password");
        input.put("id", 7).put("personalWebsiteURL", "https://ana.example.com");
        CandidateView view = json.treeToValue(input, CandidateView.class);
        var participants = new SelectedParticipantsMapper();
        Candidate candidate = participants.candidate(view);
        ObjectNode vacancyInput = fixture("vacancy").put("id", 42);
        ((ObjectNode) vacancyInput.at("/undergraduateRequirements/0/undergraduate")).putArray("projects");
        ((ObjectNode) vacancyInput.at("/graduateRequirements/0/graduate")).putArray("projects");
        VacancyView vacancyView = json.treeToValue(vacancyInput, VacancyView.class);
        Vacancy vacancy = participants.vacancy(vacancyView);
        var mapping = new MatchingResponseMapper();
        var details = mapping.mapVacancy(vacancy);
        var allocation = new AllocationResponse(List.of(
                new AllocationResponse.Shortlist(details, List.of(mapping.mapCandidate(candidate))),
                new AllocationResponse.Shortlist(mapping.mapVacancy(participants.vacancy(
                        new VacancyView(43L, "Designer", "Recife", vacancyView.seniority(), vacancyView.modalidade(),
                                List.of(), null, List.of(), List.of(), List.of()))), List.of())), Set.of("8"), 1);
        Candidate unassigned = new Candidate("Unassigned", "Private", "Dev", "hidden");
        unassigned.setId(8L);
        var document = new ExecutionDocumentMapper().map(allocation, List.of(view, ExecutionTestSupport.view(unassigned)));
        var writer = new CompressedExecutionWriter(json, List.of(new GzipExecutionCompressor()), "gzip", directory);
        Path archive = writer.write(document);
        JsonNode root;
        try (var gzip = new GZIPInputStream(Files.newInputStream(archive))) { root = json.readTree(gzip); }
        assertThat(root.isArray()).isTrue();
        assertThat(root.size()).isEqualTo(2);
        JsonNode expectedVacancy = json.readTree(json.writeValueAsBytes(vacancyView));
        ObjectNode actualVacancy = ((ObjectNode) root.get(0)).deepCopy();
        actualVacancy.remove("candidates");
        assertThat(actualVacancy).isEqualTo(expectedVacancy);
        assertThat(root.get(0).path("candidates").size()).isEqualTo(1);
        assertThat(root.get(0).path("candidates").get(0)).isEqualTo(json.readTree(json.writeValueAsBytes(view)));
        assertThat(root.get(1).path("candidates").isEmpty()).isTrue();
        assertThat(root.toString()).doesNotContain("password", "secret", "Unassigned", "decisions", "participants");
        assertThat(root.at("/0/candidates/0/resume/undergraduate/completionDate").asText()).isEqualTo("2030-12-01T00:00:00Z");
        Files.delete(archive);
    }

    @Test void compressorCanBeReplacedByConfiguration() throws Exception {
        ExecutionCompressor plain = new ExecutionCompressor() {
            public String format() { return "test-plain"; }
            public String extension() { return ".json"; }
            public String contentType() { return "application/json"; }
            public OutputStream compress(OutputStream output) { return output; }
        };
        var writer = new CompressedExecutionWriter(json, List.of(new GzipExecutionCompressor(), plain), "test-plain", directory);
        Path file = writer.write(List.of());
        assertThat(Files.readString(file)).isEqualTo("[]");
        assertThat(writer.format()).isEqualTo("test-plain");
        assertThat(writer.extension()).isEqualTo(".json");
        assertThat(writer.contentType()).isEqualTo("application/json");
        Files.delete(file);
        assertThatThrownBy(() -> new CompressedExecutionWriter(json, List.of(plain), "unknown", directory))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void compressorFailureRemovesPartialFile() {
        ExecutionCompressor broken = new ExecutionCompressor() {
            public String format() { return "broken"; }
            public String extension() { return ".broken"; }
            public String contentType() { return "application/octet-stream"; }
            public OutputStream compress(OutputStream output) throws IOException {
                output.write(1);
                throw new IOException("compression failed");
            }
        };
        var writer = new CompressedExecutionWriter(json, List.of(broken), "broken", directory);
        assertThatThrownBy(() -> writer.write(List.of())).isInstanceOf(IOException.class);
        assertThat(directory).isEmptyDirectory();
    }

    @Test void serializationFailureAlsoRemovesPartialArchive() {
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(ExecutionVacancy.class, new JsonSerializer<ExecutionVacancy>() {
            @Override public void serialize(ExecutionVacancy value, com.fasterxml.jackson.core.JsonGenerator generator,
                                            SerializerProvider provider) throws IOException {
                generator.writeStartObject();
                throw new IOException("serialization failed");
            }
        });
        var writer = new CompressedExecutionWriter(json.copy().registerModule(module),
                List.of(new GzipExecutionCompressor()), "gzip", directory);
        assertThatThrownBy(() -> writer.write(List.of(new ExecutionVacancy(null, List.of()))))
                .isInstanceOf(IOException.class);
        assertThat(directory).isEmptyDirectory();
    }

    private ObjectNode fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/crud/" + name + ".json")) {
            return (ObjectNode) json.readTree(input);
        }
    }
}
