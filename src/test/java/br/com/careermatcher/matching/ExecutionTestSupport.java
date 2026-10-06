package br.com.careermatcher.matching;

import br.com.careermatcher.DTOs.matching.MatchingRunResponse;
import br.com.careermatcher.DTOs.crud.CrudDtos.*;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.matching.execution.MatchingExecutionService;
import java.time.Instant;
import static org.mockito.Mockito.*;

/** Calculation tests stub persistence; storage guarantees have separate integration coverage. */
public final class ExecutionTestSupport {
    private ExecutionTestSupport() {}
    public static CandidateView view(Candidate candidate) {
        return new CandidateView(candidate.getId(), candidate.getName(),
                candidate.getAbout(), candidate.getProfessionalTitle(), candidate.getPhone(), null, null, null, null);
    }
    public static VacancyView view(Vacancy vacancy) {
        return new VacancyView(vacancy.getId(), vacancy.getRole(), vacancy.getCity(),
                vacancy.getSeniority(), vacancy.getModalidade(), null, null, null, null, null);
    }
    public static MatchingExecutionService successfulExecutions() {
        var executions = mock(MatchingExecutionService.class);
        var context = new MatchingExecutionService.Context(1, "RUN-test", Instant.EPOCH);
        when(executions.begin()).thenReturn(context);
        when(executions.complete(any(), any(), anyList(), anyInt())).thenAnswer(call ->
                new MatchingRunResponse(context.executionId(), context.createdAt(), call.getArgument(1)));
        return executions;
    }
}
