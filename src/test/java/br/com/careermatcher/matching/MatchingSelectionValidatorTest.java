package br.com.careermatcher.matching;

import br.com.careermatcher.repositories.GraphCrudRepository;
import br.com.careermatcher.security.CurrentActor;
import br.com.careermatcher.validations.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static br.com.careermatcher.crud.GraphSchema.Kind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatchingSelectionValidatorTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final GraphCrudRepository repository = mock(GraphCrudRepository.class);
    private final CurrentActor actor = mock(CurrentActor.class);
    private final MatchingSelectionValidator validator = new MatchingSelectionValidator(repository, json, actor, 200);

    @Test void loadsAuthoritativeParticipantsAndPreservesSelectedOrder() throws Exception {
        when(repository.findByIds(CANDIDATE, List.of(7L, 2L), 200)).thenReturn(List.of(
                json.createObjectNode().put("id", 7).put("name", "Persisted")
                        .put("professionalTitle", "Developer").put("email", "account@example.com"),
                json.createObjectNode().put("id", 2).put("name", "Second")));
        when(repository.findByIds(VACANCY, List.of(9L), 200)).thenReturn(List.of(json.createObjectNode().put("id", 9).put("role", "Developer")));
        var selection = validator.validate(json.readTree("{\"candidateIds\":[7,2],\"vacancyIds\":[9]}"));
        assertThat(selection.candidates()).extracting(item -> item.id()).containsExactly(7L, 2L);
        assertThat(selection.candidates().getFirst().name()).isEqualTo("Persisted");
        assertThat(selection.candidates().getFirst().email()).isEqualTo("account@example.com");
        var order = inOrder(repository);
        order.verify(repository).findByIds(VACANCY, List.of(9L), 200);
        order.verify(repository).findByIds(CANDIDATE, List.of(7L, 2L), 200);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"candidateIds\":[],\"vacancyIds\":[1]}",
            "{\"candidateIds\":[1,1],\"vacancyIds\":[2]}", "{\"candidateIds\":[1],\"vacancyIds\":[-1]}",
            "{\"candidateIds\":[\"1\"],\"vacancyIds\":[2]}", "{\"candidateIds\":[1.5],\"vacancyIds\":[2]}",
            "{\"candidateIds\":[1],\"vacancyIds\":[2],\"companyId\":3}", "{\"candidates\":[],\"vacancies\":[]}"})
    void rejectsMalformedAndClientControlledSelectionsBeforeReads(String body) {
        assertThatThrownBy(() -> validator.validate(json.readTree(body))).isInstanceOf(ValidationException.class);
        verifyNoInteractions(repository);
    }

    @Test void unauthorizedVacancyFailsBeforeReadingCandidates() throws Exception {
        when(repository.findByIds(VACANCY, List.of(2L), 200)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> validator.validate(json.readTree("{\"candidateIds\":[1],\"vacancyIds\":[2]}")))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        verify(repository, never()).findByIds(eq(CANDIDATE), anyList(), anyInt());
    }

    @Test void usesConfiguredBatchSize() throws Exception {
        new MatchingSelectionValidator(repository, json, actor, 7)
                .validate(json.readTree("{\"candidateIds\":[1],\"vacancyIds\":[2]}"));
        verify(repository).findByIds(VACANCY, List.of(2L), 7);
        verify(repository).findByIds(CANDIDATE, List.of(1L), 7);
    }

    @ParameterizedTest @ValueSource(ints = {0, -1})
    void rejectsInvalidBatchSize(int size) {
        assertThatThrownBy(() -> new MatchingSelectionValidator(repository, json, actor, size))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("matching.selection.batch-size");
    }

    @Test void candidatesCannotRunMatching() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(actor).requireStaff();
        assertThatThrownBy(() -> validator.validate(json.createObjectNode())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }
}
