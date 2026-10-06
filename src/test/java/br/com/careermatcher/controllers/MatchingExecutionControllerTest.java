package br.com.careermatcher.controllers;

import br.com.careermatcher.DTOs.crud.CrudDtos.PageView;
import br.com.careermatcher.matching.execution.*;
import br.com.careermatcher.models.MatchingExecution;
import br.com.careermatcher.repositories.MatchingExecutionRepository;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MatchingExecutionControllerTest {
    private final MatchingExecutionRepository repository = mock(MatchingExecutionRepository.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        var service = new MatchingExecutionService(() -> 17, repository, null, null, null, null, null);
        mvc = standaloneSetup(new MatchingExecutionController(service)).setControllerAdvice(new CrudExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS))).build();
    }

    @Test void returnsMetadataWithDefaultPaginationAndNoStorageSecrets() throws Exception {
        when(repository.findPage(17, 0, 20)).thenReturn(new PageView<>(List.of(new MatchingExecution(
                9L, "RUN-1", Instant.parse("2026-09-20T16:06:00Z"), 23, 70, "private-bucket",
                "companies/17/executions/RUN-1.json.gz", "gzip", 1)), 0, 20, 1, 1));
        mvc.perform(get("/api/matching/executions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(9))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-09-20T16:06:00Z"))
                .andExpect(jsonPath("$.content[0].vacanciesQuantity").value(23))
                .andExpect(jsonPath("$.content[0].candidateQuantity").value(70))
                .andExpect(jsonPath("$.content[0].compression").value("gzip"))
                .andExpect(jsonPath("$.content[0].schemaVersion").value(1))
                .andExpect(jsonPath("$.content[0].bucket").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));
        verify(repository).findPage(17, 0, 20);
    }

    @Test void rejectsInvalidPaginationBeforeQueryingDatabase() throws Exception {
        for (String parameters : List.of("page=-1", "size=0", "size=101", "page=abc")) {
            mvc.perform(get("/api/matching/executions?" + parameters)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
        verifyNoInteractions(repository);
    }

    @Test void emptyHistoryAndMissingCompanyHaveDistinctResponses() throws Exception {
        when(repository.findPage(17, 0, 20)).thenReturn(new PageView<>(List.of(), 0, 20, 0, 0));
        mvc.perform(get("/api/matching/executions")).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        when(repository.findPage(17, 0, 20)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada."));
        mvc.perform(get("/api/matching/executions")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Empresa não encontrada."));
    }

    @Test void unavailableDatabaseReturnsRetryMessage() throws Exception {
        when(repository.findPage(17, 0, 20)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private error"));
        mvc.perform(get("/api/matching/executions")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Tente novamente")));
    }
}
