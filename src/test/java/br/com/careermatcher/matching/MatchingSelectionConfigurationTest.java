package br.com.careermatcher.matching;

import br.com.careermatcher.repositories.GraphCrudRepository;
import br.com.careermatcher.security.CurrentActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static br.com.careermatcher.crud.GraphSchema.Kind.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MatchingSelectionConfigurationTest {
    private final GraphCrudRepository repository = mock(GraphCrudRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(GraphCrudRepository.class, () -> repository)
            .withBean(ObjectMapper.class, () -> mapper)
            .withBean(CurrentActor.class, () -> mock(CurrentActor.class))
            .withUserConfiguration(MatchingSelectionValidator.class);

    @Test void defaultsToTwoHundred() { assertBatchSize(context, 200); }

    @Test void acceptsConfiguredBatchSize() {
        assertBatchSize(context.withPropertyValues("matching.selection.batch-size=7"), 7);
    }

    @ParameterizedTest @ValueSource(ints = {0, -1})
    void invalidBatchSizePreventsStartup(int size) {
        context.withPropertyValues("matching.selection.batch-size=" + size).run(application -> {
            assertThat(application).hasFailed();
            assertThat(application.getStartupFailure())
                    .hasRootCauseMessage("matching.selection.batch-size deve ser maior que zero.");
        });
    }

    private void assertBatchSize(ApplicationContextRunner runner, int expected) {
        runner.run(application -> {
            assertThat(application).hasNotFailed();
            application.getBean(MatchingSelectionValidator.class)
                    .validate(mapper.readTree("{\"candidateIds\":[1],\"vacancyIds\":[2]}"));
            verify(repository).findByIds(VACANCY, List.of(2L), expected);
            verify(repository).findByIds(CANDIDATE, List.of(1L), expected);
        });
    }
}
