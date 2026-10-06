package br.com.careermatcher.services;

import br.com.careermatcher.DTOs.matching.AllocationResponse;
import br.com.careermatcher.enums.Modalidade;
import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.matching.HRMatchingAlgorithm;
import br.com.careermatcher.matching.DynamicCutoffService;
import br.com.careermatcher.matching.ExecutionTestSupport;
import br.com.careermatcher.matching.SelectedParticipantsMapper;
import br.com.careermatcher.DTOs.crud.CrudDtos.*;
import br.com.careermatcher.matching.MatchingResponseMapper;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.rankers.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_OUT;

@ResourceLock(SYSTEM_OUT)
class TalentEngineServiceTest {

    private List<Candidate> selectedCandidates = List.of();
    private List<Vacancy> selectedVacancies = List.of();
    private final RankerService rankerService = new RankerService(
            new SeniorityRanker(), new ExperienceRanker(), new SkillRanker(), new LanguageRanker(),
            new UndergraduateRanker(), new GraduateRanker(), new LocationRanker(), new ModalityRanker());
    private final TalentEngineService service = new TalentEngineService(
            rankerService, new HRMatchingAlgorithm(),
            new MatchingResponseMapper(), new DynamicCutoffService(), ExecutionTestSupport.successfulExecutions(), new SelectedParticipantsMapper());
    private String printedOutput;

    @Test
    void ranksSelectedParticipantsAndAllocatesExclusiveShortlistsWithPublicDetailsAndEmptyVacancies() {
        Candidate first = candidate(1L);
        Candidate second = candidate(2L);
        Candidate third = candidate(3L);
        Candidate fourth = candidate(4L);
        Candidate fifth = candidate(5L);
        Vacancy remote = vacancy(10L, SENIORITY.ESTAGIARIO, Modalidade.REMOTO);
        Vacancy onSite = vacancy(20L, SENIORITY.ESTAGIARIO, Modalidade.PRESENCIAL);
        Vacancy incompatible = vacancy(30L, SENIORITY.SENIOR, Modalidade.REMOTO);
        selectedCandidates = List.of(fifth, third, second, fourth, first);
        selectedVacancies = List.of(incompatible, onSite, remote);

        AllocationResponse result = captureOutput(() -> run(2, 0.65, 0.0));

        assertThat(result.shortlists()).extracting(shortlist -> shortlist.vacancy().id())
                .containsExactly("10", "20", "30");
        assertThat(shortlist(result, "10")).extracting(AllocationResponse.CandidateDetails::id)
                .containsExactly("1", "2");
        assertThat(shortlist(result, "20")).extracting(AllocationResponse.CandidateDetails::id)
                .containsExactly("3", "4");
        assertThat(shortlist(result, "30")).isEmpty();
        assertThat(result.unassignedCandidateIds()).containsExactly("5");
        assertThat(result.totalAllocated()).isEqualTo(4);
        assertThat(shortlist(result, "10").getFirst())
                .isEqualTo(new AllocationResponse.CandidateDetails(
                        "1", first.getName(), first.getAbout(), first.getProfessionalTitle(), null));
        assertThat(result.shortlists().getFirst().vacancy())
                .isEqualTo(new AllocationResponse.VacancyDetails(
                        "10", remote.getRole(), remote.getCity(), remote.getSeniority(), remote.getModalidade(),
                        List.of(), null, List.of(), List.of(), List.of()));
        assertNoParticipantDataInOutput();
    }

    @Test
    void kOneMatchesGaleShapleyWhenCutoffRetainsTheSamePreferences() {
        selectedCandidates = List.of(candidate(3L), candidate(1L), candidate(2L));
        selectedVacancies = List.of(
                vacancy(20L, SENIORITY.ESTAGIARIO, Modalidade.PRESENCIAL),
                vacancy(10L, SENIORITY.ESTAGIARIO, Modalidade.REMOTO),
                vacancy(30L, SENIORITY.SENIOR, Modalidade.REMOTO));

        Map<Candidate, Vacancy> reference = new GaleShapley().match(selectedCandidates,
                rankerService.createCandidatePreferenceLists(selectedCandidates, selectedVacancies), selectedVacancies,
                rankerService.createVacancyPreferenceLists(selectedVacancies, selectedCandidates));
        AllocationResponse hr = captureOutput(() -> run(1, 0.65, 0.0));
        Map<String, String> expected = new LinkedHashMap<>();
        reference.forEach((candidate, vacancy) -> expected.put(candidate.getId().toString(), vacancy.getId().toString()));
        Map<String, String> actual = new LinkedHashMap<>();
        hr.shortlists().forEach(shortlist -> shortlist.candidates().forEach(candidate ->
                actual.put(candidate.id(), shortlist.vacancy().id())));

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(hr.unassignedCandidateIds()).containsExactly("3");
    }

    @Test
    void noVacanciesLeavesEveryCandidateUnassigned() {
        selectedCandidates = List.of(candidate(2L), candidate(1L));
        selectedVacancies = List.of();

        AllocationResponse result = captureOutput(() -> run(5));

        assertThat(result.shortlists()).isEmpty();
        assertThat(result.unassignedCandidateIds()).containsExactly("1", "2");
        assertThat(result.totalAllocated()).isZero();
        assertNoParticipantDataInOutput();
    }

    @Test
    void noCandidatesPreservesEveryVacancyAsAnEmptyShortlist() {
        selectedCandidates = List.of();
        selectedVacancies = List.of(
                vacancy(10L, SENIORITY.ESTAGIARIO, Modalidade.REMOTO));

        AllocationResponse result = captureOutput(() -> run(5));

        assertThat(result.shortlists()).hasSize(1);
        assertThat(shortlist(result, "10")).isEmpty();
        assertThat(result.unassignedCandidateIds()).isEmpty();
        assertThat(result.totalAllocated()).isZero();
        assertNoParticipantDataInOutput();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsInvalidCapacity(int k) {
        assertThatThrownBy(() -> run(k)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsParticipantsWithoutPersistentIds() {
        selectedCandidates = List.of(candidate(null));
        selectedVacancies = List.of();

        assertThatThrownBy(() -> run(1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ID persistido");
    }

    private AllocationResponse run(int k) {
        return service.generateShortlists(selectedCandidates.stream().map(ExecutionTestSupport::view).toList(),
                selectedVacancies.stream().map(ExecutionTestSupport::view).toList(), k).allocation();
    }
    private AllocationResponse run(int k, double factor, double floor) {
        return service.generateShortlists(selectedCandidates.stream().map(ExecutionTestSupport::view).toList(),
                selectedVacancies.stream().map(ExecutionTestSupport::view).toList(), k, factor, floor).allocation();
    }

    private void assertNoParticipantDataInOutput() {
        // Timing diagnostics are allowed; participant details must stay out of console output.
        assertThat(printedOutput).doesNotContain("test-secret");
        selectedCandidates.forEach(candidate -> assertThat(printedOutput)
                .doesNotContain(candidate.getName(), candidate.getAbout(),
                        candidate.getProfessionalTitle(), candidate.getPhone()));
        selectedVacancies.forEach(vacancy -> assertThat(printedOutput)
                .doesNotContain(vacancy.getRole(), vacancy.getCity()));
    }

    private <T> T captureOutput(Supplier<T> action) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            T result = action.get();
            printedOutput = bytes.toString(StandardCharsets.UTF_8);
            return result;
        } finally {
            System.setOut(original);
        }
    }

    private static List<AllocationResponse.CandidateDetails> shortlist(
            AllocationResponse result, String vacancyId
    ) {
        return result.shortlists().stream()
                .filter(shortlist -> shortlist.vacancy().id().equals(vacancyId))
                .findFirst().orElseThrow().candidates();
    }

    private static Candidate candidate(Long id) {
        Candidate candidate = new Candidate("Candidate " + id, "About " + id, "Developer", "11999999999");
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }

    private static Vacancy vacancy(Long id, SENIORITY seniority, Modalidade modalidade) {
        Vacancy vacancy = new Vacancy("Developer", "São Paulo", seniority, modalidade);
        ReflectionTestUtils.setField(vacancy, "id", id);
        return vacancy;
    }
}
