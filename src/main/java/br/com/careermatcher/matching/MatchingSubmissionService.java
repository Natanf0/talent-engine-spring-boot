package br.com.careermatcher.matching;

import br.com.careermatcher.interfaces.MatchingService;
import br.com.careermatcher.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

@Service
public class MatchingSubmissionService {
    private static final Logger log = LoggerFactory.getLogger(MatchingSubmissionService.class);
    private final TaskExecutor executor;
    private final MatchingSelectionValidator selections;
    private final MatchingService matching;
    private final CurrentActor actor;

    public MatchingSubmissionService(@Qualifier("matchingExecutor") TaskExecutor executor,
            MatchingSelectionValidator selections, MatchingService matching, CurrentActor actor) {
        this.executor = executor;
        this.selections = selections;
        this.matching = matching;
        this.actor = actor;
    }

    public void submit(MatchingSelectionValidator.SelectedIds ids, int k, double factor, double floor) {
        long companyId = actor.requireCompany();
        String requestId = UUID.randomUUID().toString();
        // A separate context survives HTTP request cleanup; the wrapper restores/clears it after work.
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
        try {
            executor.execute(new DelegatingSecurityContextRunnable(() -> {
                long start = System.nanoTime();
                log.info("Matching started: requestId={}, companyId={}", requestId, companyId);
                try {
                    var selection = selections.resolve(ids);
                    var result = matching.generateShortlists(selection.candidates(), selection.vacancies(), k, factor, floor);
                    log.info("Matching completed: requestId={}, companyId={}, executionId={}, elapsedMs={}",
                            requestId, companyId, result.executionId(), (System.nanoTime() - start) / 1_000_000);
                } catch (RuntimeException failure) {
                    log.error("Matching failed: requestId={}, companyId={}, failureType={}, elapsedMs={}",
                            requestId, companyId, failure.getClass().getSimpleName(), (System.nanoTime() - start) / 1_000_000);
                }
            }, context));
        } catch (RejectedExecutionException rejected) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Não foi possível aceitar a execução agora. Tente novamente em instantes.");
        }
        log.info("Matching accepted: requestId={}, companyId={}", requestId, companyId);
    }
}
