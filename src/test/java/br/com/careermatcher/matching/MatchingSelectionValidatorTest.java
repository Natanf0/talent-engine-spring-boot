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
        when(repository.findTalentPoolByIds(List.of(7L, 2L), 200)).thenReturn(List.of(
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
        order.verify(repository).findTalentPoolByIds(List.of(7L, 2L), 200);
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
        verify(repository, never()).findTalentPoolByIds(anyList(), anyInt());
    }

    @Test void usesConfiguredBatchSize() throws Exception {
        new MatchingSelectionValidator(repository, json, actor, 7)
                .validate(json.readTree("{\"candidateIds\":[1],\"vacancyIds\":[2]}"));
        verify(repository).findByIds(VACANCY, List.of(2L), 7);
        verify(repository).findTalentPoolByIds(List.of(1L), 7);
    }

    @ParameterizedTest @ValueSource(ints = {0, -1})
    void rejectsInvalidBatchSize(int size) {
        assertThatThrownBy(() -> new MatchingSelectionValidator(repository, json, actor, size))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("matching.selection.batch-size");
    }

    @Test void resolvesAllCandidatesOnServerAndAppliesExclusionsWithoutClientIds() throws Exception {
        var parsed=validator.parse(json.readTree("{\"allCandidates\":true,\"excludedCandidateIds\":[2],\"vacancyIds\":[9]}"));
        assertThat(parsed.allCandidates()).isTrue();
        verifyNoInteractions(repository);
        when(repository.talentPoolIds()).thenReturn(List.of(7L,2L,3L));
        when(repository.findByIds(VACANCY,List.of(9L),200)).thenReturn(List.of(json.createObjectNode().put("id",9)));
        when(repository.findTalentPoolByIds(List.of(7L,3L),200)).thenReturn(List.of(json.createObjectNode().put("id",7),json.createObjectNode().put("id",3)));
        assertThat(validator.resolve(parsed).candidates()).extracting(c->c.id()).containsExactly(7L,3L);
        verify(repository).talentPoolIds();
    }

    @ParameterizedTest @ValueSource(strings={
        "{\"allCandidates\":\"true\",\"vacancyIds\":[9]}",
        "{\"allCandidates\":true,\"candidateIds\":[1],\"vacancyIds\":[9]}",
        "{\"allCandidates\":false,\"excludedCandidateIds\":[1],\"candidateIds\":[2],\"vacancyIds\":[9]}",
        "{\"allCandidates\":true,\"excludedCandidateIds\":[1,1],\"vacancyIds\":[9]}",
        "{\"allCandidates\":true,\"excludedCandidateIds\":[\"1\"],\"vacancyIds\":[9]}"
    })
    void rejectsAmbiguousOrMalformedAllSelection(String body) throws Exception {
        assertThatThrownBy(()->validator.parse(json.readTree(body))).isInstanceOf(ValidationException.class);
        verifyNoInteractions(repository);
    }

    @Test void emptyAllSelectionCannotStartCalculation() throws Exception {
        when(repository.talentPoolIds()).thenReturn(List.of(1L));
        assertThatThrownBy(()->validator.validate(json.readTree("{\"allCandidates\":true,\"excludedCandidateIds\":[1],\"vacancyIds\":[9]}")))
                .isInstanceOf(ValidationException.class);
        verify(repository,never()).findTalentPoolByIds(anyList(),anyInt());
    }

    @Test void candidatesCannotRunMatching() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(actor).requireStaff();
        assertThatThrownBy(() -> validator.validate(json.createObjectNode())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }
}
