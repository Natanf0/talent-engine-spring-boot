package br.com.careermatcher.services;

import br.com.careermatcher.DTOs.matching.AllocationResult;
import br.com.careermatcher.DTOs.crud.CrudDtos;
import br.com.careermatcher.DTOs.matching.MatchingRunResponse;
import br.com.careermatcher.DTOs.matching.AllocationResponse;
import br.com.careermatcher.DTOs.matching.CandidateDTO;
import br.com.careermatcher.DTOs.matching.VacancyDTO;
import br.com.careermatcher.interfaces.MatchingService;
import br.com.careermatcher.matching.HRMatchingAlgorithm;
import br.com.careermatcher.matching.DynamicCutoffService;
import br.com.careermatcher.matching.MatchingResponseMapper;
import br.com.careermatcher.matching.SelectedParticipantsMapper;
import br.com.careermatcher.matching.execution.MatchingExecutionService;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Vacancy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class TalentEngineService implements MatchingService {

    private final RankerService rankerService;
    private final HRMatchingAlgorithm hrMatchingAlgorithm;
    private final MatchingResponseMapper matchingResponseMapper;
    private final DynamicCutoffService dynamicCutoffService;
    private final MatchingExecutionService executions;
    private final SelectedParticipantsMapper selectedParticipantsMapper;

    @Override
    public MatchingRunResponse generateShortlists(List<CrudDtos.CandidateView> selectedCandidates,
                                                   List<CrudDtos.VacancyView> selectedVacancies,
                                                   int k, double toleranceFactor, double absoluteMinScore) {
        if (k <= 0) {
            throw new IllegalArgumentException("O tamanho do shortlist (K) deve ser maior que zero.");
        }
        long start = System.currentTimeMillis();
        DynamicCutoffService.validateParameters(toleranceFactor, absoluteMinScore);
        System.out.println("DynamicCutoffService.validateParameters(toleranceFactor, absoluteMinScore); levou " + (start - System.currentTimeMillis())/1000 + " s");

        long startCandidates = System.currentTimeMillis();
        List<Candidate> candidates = selectedCandidates.stream().map(selectedParticipantsMapper::candidate).toList();
        System.out.println("List<Candidate> candidates = selectedCandidates.stream().map(selectedParticipantsMapper::candidate).toList(); levou " + (startCandidates - System.currentTimeMillis())/1000 + " s");

        long startVacancies = System.currentTimeMillis();
        List<Vacancy> vacancies = selectedVacancies.stream().map(selectedParticipantsMapper::vacancy).toList();
        System.out.println("List<Vacancy> vacancies = selectedVacancies.stream().map(selectedParticipantsMapper::vacancy).toList(); levou " + (startVacancies - System.currentTimeMillis())/1000 + " s");

        return runMatching(candidates, vacancies, selectedCandidates, k, toleranceFactor, absoluteMinScore);
    }

    private MatchingRunResponse runMatching(List<Candidate> candidates, List<Vacancy> vacancies,
                                            List<CrudDtos.CandidateView> candidateViews,
                                            int k, double toleranceFactor, double absoluteMinScore) {
        long start = System.currentTimeMillis();
        System.out.println("Iniciando runMatching");
        var execution = executions.begin();
        Map<Candidate, List<Vacancy>> candidatePreferences =
                rankerService.createCandidatePreferenceLists(candidates, vacancies);
        System.out.println("createcandidatePreferedceList levou " + (start - System.currentTimeMillis())/1000 + " s");

        long startVacSc = System.currentTimeMillis();
        Map<Vacancy, Map<Candidate, Double>> vacancyScores =
                rankerService.calculateVacancyScores(vacancies, candidates);

        System.out.println("calculateVacancyScores levou " + (startVacSc - System.currentTimeMillis())/1000 + " s");

        long startcandidateDTOs = System.currentTimeMillis();
        // Ranker maps retain the deterministic participant order, including empty lists.
        List<CandidateDTO> candidateDTOs = candidatePreferences.keySet().stream()
                .map(candidate -> new CandidateDTO(participantId(candidate.getId()), candidate.getName(),
                        candidate.getAbout(), candidate.getProfessionalTitle(), candidate.getPhone(), null))
                .toList();
        System.out.println("candidateDTOs levou " + (startcandidateDTOs - System.currentTimeMillis())/1000 + " s");
        long startvacancyDTOs = System.currentTimeMillis();
        List<VacancyDTO> vacancyDTOs = vacancyScores.keySet().stream()
                .map(vacancy -> new VacancyDTO(participantId(vacancy.getId()), vacancy.getRole(),
                        vacancy.getCity(), vacancy.getSeniority(), vacancy.getModalidade()))
                .toList();
        System.out.println("vacancyDTOs levou " + (startvacancyDTOs - System.currentTimeMillis())/1000 + " s");
        long startrawScores= System.currentTimeMillis();
        Map<String, Map<String, Double>> rawScores = new LinkedHashMap<>();
        vacancyScores.forEach((vacancy, scores) -> {
            Map<String, Double> candidateScores = new LinkedHashMap<>();
            scores.forEach((candidate, score) -> candidateScores.put(participantId(candidate.getId()), score));
            rawScores.put(participantId(vacancy.getId()), candidateScores);
        });
        System.out.println("rawScores levou " + (startrawScores - System.currentTimeMillis())/1000 + " s");
        long startvacancyPreferences= System.currentTimeMillis();
        Map<String, List<String>> vacancyPreferences = dynamicCutoffService.applyDynamicCutoff(
                rawScores, toleranceFactor, absoluteMinScore);
        System.out.println("vacancyPreferences levou " + (startvacancyPreferences - System.currentTimeMillis())/1000 + " s");

        long startintersectBlock= System.currentTimeMillis();
        // Intersect acceptable pairs while retaining each side's independent ranking.
        Map<String, Set<String>> eligibleCandidates = new LinkedHashMap<>();
        vacancyPreferences.forEach((id, choices) -> eligibleCandidates.put(id, new HashSet<>(choices)));
        Map<String, List<String>> candidatePreferenceIds =
                preferenceIds(candidatePreferences, Candidate::getId, Vacancy::getId);
        candidatePreferenceIds.replaceAll((candidateId, choices) -> choices.stream()
                .filter(vacancyId -> eligibleCandidates.get(vacancyId).contains(candidateId)).toList());
        Map<String, Set<String>> eligibleVacancies = new LinkedHashMap<>();
        candidatePreferenceIds.forEach((id, choices) -> eligibleVacancies.put(id, new HashSet<>(choices)));
        vacancyPreferences.replaceAll((vacancyId, choices) -> choices.stream()
                .filter(candidateId -> eligibleVacancies.get(candidateId).contains(vacancyId)).toList());

        System.out.println("Intersect acceptable pairs while retaining each side's independent ranking levou " + (startintersectBlock - System.currentTimeMillis())/1000 + " s");
        long startHR= System.currentTimeMillis();
        AllocationResult result = hrMatchingAlgorithm.executeMatching(
                candidateDTOs, vacancyDTOs, candidatePreferenceIds, vacancyPreferences, k);

        System.out.println("hrMatchingAlgorithm.executeMatching levou " + (startHR - System.currentTimeMillis())/1000 + " s");
        AllocationResponse response = matchingResponseMapper.toResponse(result, candidates, vacancies);
        return executions.complete(execution, response, candidateViews, vacancies.size());
    }

    private static <L, R> Map<String, List<String>> preferenceIds(
            Map<L, List<R>> preferences, Function<L, Long> leftId, Function<R, Long> rightId
    ) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        preferences.forEach((participant, choices) -> result.put(participantId(leftId.apply(participant)),
                choices.stream().map(choice -> participantId(rightId.apply(choice))).toList()));
        return result;
    }

    private static String participantId(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Participantes do matching devem possuir ID persistido.");
        }
        return id.toString();
    }

}
