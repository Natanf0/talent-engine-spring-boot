package br.com.careermatcher.services;

import br.com.careermatcher.DTOs.matching.AllocationResponse;
import br.com.careermatcher.enums.Modalidade;
import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.matching.DynamicCutoffService;
import br.com.careermatcher.matching.ExecutionTestSupport;
import br.com.careermatcher.matching.SelectedParticipantsMapper;
import br.com.careermatcher.DTOs.crud.CrudDtos.*;
import br.com.careermatcher.matching.HRMatchingAlgorithm;
import br.com.careermatcher.matching.MatchingResponseMapper;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.rankers.RankingResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_OUT;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ResourceLock(SYSTEM_OUT)
class TalentEngineDynamicCutoffTest {
    private final RankerService ranker = spy(new RankerService(null, null, null, null, null, null, null, null));
    private final HRMatchingAlgorithm hr = spy(new HRMatchingAlgorithm());
    private final SelectedParticipantsMapper participants = mock(SelectedParticipantsMapper.class);
    private final TalentEngineService service = new TalentEngineService(
            ranker, hr,
            new MatchingResponseMapper(), new DynamicCutoffService(), ExecutionTestSupport.successfulExecutions(), participants);
    private final List<Candidate> candidateList = new ArrayList<>();
    private final List<Vacancy> vacancyList = new ArrayList<>();

    @Test
    void defaultsCutTheDropAndKeepPrunedCandidatesUnassigned() {
        fixture(new double[][]{{28, 26, 25, 8, 3}});

        AllocationResponse response = quietly(() -> run(10));

        assertThat(response.shortlists().getFirst().candidates())
                .extracting(AllocationResponse.CandidateDetails::id).containsExactly("1", "2", "3");
        assertThat(response.unassignedCandidateIds()).containsExactly("4", "5");
        assertThat(response.totalAllocated()).isEqualTo(3);
        for (Candidate candidate : candidateList) {
            verify(ranker, times(1)).rankCandidateForVacancy(candidate, vacancyList.getFirst());
        }
    }

    @Test
    void keepsCandidatePreferenceAndDoesNotRefillAfterLosingTheLeader() {
        fixture(new double[][]{{28, 8, 3}, {26, 8, 3}});
        // The technical score favors vacancy 10; the candidate prefers remote vacancy 20.
        doReturn(new RankingResult(40, Map.of())).when(ranker)
                .rankVacancyForCandidate(candidateList.getFirst(), vacancyList.get(1));

        AllocationResponse response = quietly(() -> run(10));

        assertThat(response.shortlists().getFirst().candidates()).isEmpty();
        assertThat(response.shortlists().get(1).candidates())
                .extracting(AllocationResponse.CandidateDetails::id).containsExactly("1");
        assertThat(response.unassignedCandidateIds()).containsExactly("2", "3");
        verify(hr).executeMatching(anyList(), anyList(),
                eq(Map.of("1", List.of("20", "10"), "2", List.of(), "3", List.of())),
                eq(Map.of("10", List.of("1"), "20", List.of("1"))), eq(10), any());
    }

    @Test
    void removesPairsUnacceptableToCandidateFromBothPreferenceMaps() {
        fixture(new double[][]{{28, 26}});
        doReturn(new RankingResult(0, Map.of())).when(ranker)
                .rankVacancyForCandidate(candidateList.getFirst(), vacancyList.getFirst());

        AllocationResponse response = quietly(() -> run(10));

        assertThat(response.unassignedCandidateIds()).containsExactly("1");
        verify(hr).executeMatching(anyList(), anyList(),
                eq(Map.of("1", List.of(), "2", List.of("10"))),
                eq(Map.of("10", List.of("2"))), eq(10), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 10})
    void hrLimitsCapacityWithoutOverlapAcrossUnprunedClusters(int k) {
        fixture(new double[][]{
                {30, 29, 28, 27, 26, 25, 24, 23, 22, 21},
                {30, 29, 28, 27, 26, 25, 24, 23, 22, 21}});

        AllocationResponse response = quietly(() -> run(k));

        assertThat(response.shortlists()).allSatisfy(shortlist -> assertThat(shortlist.candidates()).hasSizeLessThanOrEqualTo(k));
        assertThat(response.shortlists().stream().flatMap(shortlist -> shortlist.candidates().stream())
                .map(AllocationResponse.CandidateDetails::id).toList()).doesNotHaveDuplicates();
        assertThat(response.totalAllocated()).isEqualTo(Math.min(10, 2 * k));
    }

    @Test
    void customCutoffChangesEligibilityAndRetainsNumericTieOrder() {
        fixture(new double[][]{{28, 26, 25, 8, 3}});
        AllocationResponse response = quietly(() -> run(10, 0.1, 3));
        assertThat(response.totalAllocated()).isEqualTo(5);

        // Build a fresh fixture with tied scores and deliberately reversed input order.
        fixture(new double[][]{{20, 20, 20, 20, 20, 20, 20, 20, 20, 20}});
        response = quietly(() -> run(10));
        assertThat(response.shortlists().getFirst().candidates()).extracting(AllocationResponse.CandidateDetails::id)
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    }

    @Test
    void highVolumeLeavesResidualCandidatesUnassignedInsteadOfFillingCapacity() {
        double[][] scores = new double[25][250];
        for (int v = 0; v < 25; v++) {
            for (int c = 0; c < 250; c++) {
                scores[v][c] = c / 3 == v ? 28 - c % 3 : 3;
            }
        }
        fixture(scores);

        AllocationResponse response = quietly(() -> run(10));

        assertThat(response.shortlists()).hasSize(25).allSatisfy(s -> assertThat(s.candidates()).hasSize(3));
        assertThat(response.totalAllocated()).isEqualTo(75);
        assertThat(response.unassignedCandidateIds()).hasSize(175);
        assertThat(response.shortlists().stream().flatMap(s -> s.candidates().stream())
                .map(AllocationResponse.CandidateDetails::id).toList()).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidFactorBeforeLoadingEntities(double factor) {
        assertThatThrownBy(() -> run(10, factor, 5))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(participants, hr);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidFloorBeforeLoadingEntities(double floor) {
        assertThatThrownBy(() -> run(10, 0.65, floor))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(participants, hr);
    }

    private void fixture(double[][] scores) {
        candidateList.clear();
        vacancyList.clear();
        for (int c = 0; c < scores[0].length; c++) {
            Candidate candidate = new Candidate("Candidate " + c, "About", "Developer", "phone");
            ReflectionTestUtils.setField(candidate, "id", (long) c + 1);
            candidateList.add(candidate);
        }
        for (int v = 0; v < scores.length; v++) {
            Vacancy vacancy = new Vacancy("Developer", "Recife", SENIORITY.PLENO,
                    v == 1 ? Modalidade.REMOTO : Modalidade.PRESENCIAL);
            ReflectionTestUtils.setField(vacancy, "id", (long) (v + 1) * 10);
            vacancyList.add(vacancy);
        }
        Map<Candidate, Integer> candidateIndexes = new IdentityHashMap<>();
        Map<Vacancy, Integer> vacancyIndexes = new IdentityHashMap<>();
        for (int c = 0; c < candidateList.size(); c++) candidateIndexes.put(candidateList.get(c), c);
        for (int v = 0; v < vacancyList.size(); v++) vacancyIndexes.put(vacancyList.get(v), v);
        org.mockito.stubbing.Answer<RankingResult> answer = invocation -> new RankingResult(
                scores[vacancyIndexes.get(invocation.getArgument(1))]
                        [candidateIndexes.get(invocation.getArgument(0))], Map.of());
        doAnswer(answer).when(ranker).rankCandidateForVacancy(any(), any());
        doAnswer(answer).when(ranker).rankVacancyForCandidate(any(), any());
        doAnswer(call -> {
            CandidateView view = call.getArgument(0);
            return candidateList.stream().filter(item -> item.getId().equals(view.id())).findFirst().orElseThrow();
        }).when(participants).candidate(any());
        doAnswer(call -> {
            VacancyView view = call.getArgument(0);
            return vacancyList.stream().filter(item -> item.getId().equals(view.id())).findFirst().orElseThrow();
        }).when(participants).vacancy(any());
    }

    private AllocationResponse run(int k) {
        return run(k, DynamicCutoffService.DEFAULT_TOLERANCE_FACTOR, DynamicCutoffService.DEFAULT_ABSOLUTE_MIN_SCORE);
    }
    private AllocationResponse run(int k, double factor, double floor) {
        return service.generateShortlists(candidateList.reversed().stream().map(ExecutionTestSupport::view).toList(),
                vacancyList.reversed().stream().map(ExecutionTestSupport::view).toList(), k, factor, floor).allocation();
    }

    private static <T> T quietly(Supplier<T> action) {
        PrintStream original = System.out;
        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(sink);
            return action.get();
        } finally {
            System.setOut(original);
        }
    }
}
