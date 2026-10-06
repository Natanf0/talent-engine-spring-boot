package br.com.careermatcher.matching;

import br.com.careermatcher.DTOs.crud.CrudDtos;
import br.com.careermatcher.DTOs.matching.AllocationResponse;
import br.com.careermatcher.enums.*;
import br.com.careermatcher.rankers.*;
import br.com.careermatcher.services.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SelectedMatchingFlowTest {
    @Test void ranksOnlySelectedParticipantsAndPublishesLatestSnapshot() {
        var executions = ExecutionTestSupport.successfulExecutions();
        var rankers = new RankerService(new SeniorityRanker(), new ExperienceRanker(), new SkillRanker(),
                new LanguageRanker(), new UndergraduateRanker(), new GraduateRanker(), new LocationRanker(),
                new ModalityRanker());
        var service = new TalentEngineService(rankers,
                new HRMatchingAlgorithm(), new MatchingResponseMapper(), new DynamicCutoffService(),
                executions, new SelectedParticipantsMapper());
        var selected = List.of(new CrudDtos.CandidateView(7L, "Ana", "Java", "Developer", "+55 11 98888-7777",
                "ana@example.com", null, null, null));
        var opening = List.of(new CrudDtos.VacancyView(42L, "Developer", "São Paulo",
                SENIORITY.ESTAGIARIO, Modalidade.REMOTO, List.of(), null, List.of(), List.of(), List.of()));

        var result = service.generateShortlists(selected, opening, 1, .65, 0);
        assertThat(result.executionId()).startsWith("RUN-");
        assertThat(result.allocation().shortlists()).extracting(shortlist -> shortlist.vacancy().id())
                .containsExactly("42");
        assertThat(result.allocation().shortlists().getFirst().candidates())
                .extracting(AllocationResponse.CandidateDetails::id).containsExactly("7");
        verify(executions).begin();
        verify(executions).complete(any(), same(result.allocation()), argThat(items ->
                items.size() == 1 && items.getFirst().id() == 7L), eq(1));
    }
    @Test void missingCompanyStopsBeforeRanking() {
        var executions = mock(br.com.careermatcher.matching.execution.MatchingExecutionService.class);
        when(executions.begin()).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Empresa não encontrada."));
        var rankers = mock(RankerService.class);
        var service = new TalentEngineService(rankers,
                new HRMatchingAlgorithm(), new MatchingResponseMapper(), new DynamicCutoffService(),
                executions, new SelectedParticipantsMapper());
        assertThatThrownBy(() -> service.generateShortlists(List.of(), List.of(), 1, .65, 0))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(rankers);
        verify(executions, never()).complete(any(), any(), anyList(), anyInt());
    }

}
