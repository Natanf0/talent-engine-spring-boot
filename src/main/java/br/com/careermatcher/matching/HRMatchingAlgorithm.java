package br.com.careermatcher.matching;

import br.com.careermatcher.DTOs.matching.AllocationResult;
import br.com.careermatcher.DTOs.matching.CandidateDTO;
import br.com.careermatcher.DTOs.matching.VacancyDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Consumer;

/** Resident-oriented Hospitals/Residents matching with strict preference rankings. */
@Component
public class HRMatchingAlgorithm {

    /**
     * Builds exclusive shortlists using the resident-oriented Apply & Delete algorithm.
     * Preference order defines rank, including any score tie-breaks decided by the caller.
     * Only pairs appearing on both sides are acceptable. Unknown preference IDs are ignored.
     * Inputs are never modified; all mutable algorithm state belongs to this invocation.
     *
     * @param k maximum number of candidates allocated to each vacancy in this execution
     */
    public AllocationResult executeMatching(
            List<CandidateDTO> candidates,
            List<VacancyDTO> vacancies,
            Map<String, List<String>> candidatePreferences,
            Map<String, List<String>> vacancyPreferences,
            int k
    ) {
        return executeMatching(candidates, vacancies, candidatePreferences, vacancyPreferences, k, ignored -> {});
    }

    public AllocationResult executeMatching(
            List<CandidateDTO> candidates, List<VacancyDTO> vacancies,
            Map<String, List<String>> candidatePreferences, Map<String, List<String>> vacancyPreferences,
            int k, Consumer<HrEvent> observer
    ) {
        if (k <= 0) {
            throw new IllegalArgumentException("O tamanho do shortlist (K) deve ser maior que zero.");
        }

        Map<String, CandidateDTO> candidateMap = indexParticipants(candidates, CandidateDTO::id, "candidato");
        Map<String, VacancyDTO> vacancyMap = indexParticipants(vacancies, VacancyDTO::id, "vaga");

        Map<String, HospitalState> hospitals = new LinkedHashMap<>();
        for (String vacancyId : vacancyMap.keySet()) {
            LinkedHashSet<String> preferences = knownPreferences(vacancyPreferences, vacancyId, candidateMap.keySet());
            hospitals.put(vacancyId, new HospitalState(List.copyOf(preferences)));
        }

        Map<String, LinkedHashSet<String>> pendingPreferences = new HashMap<>();
        Trace trace = new Trace(observer, candidatePreferences, hospitals, k);
        Queue<String> freeCandidates = new ArrayDeque<>();
        for (String candidateId : candidateMap.keySet()) {
            LinkedHashSet<String> preferences = knownPreferences(candidatePreferences, candidateId, vacancyMap.keySet());
            preferences.removeIf(vacancyId -> !hospitals.get(vacancyId).ranks.containsKey(candidateId));
            pendingPreferences.put(candidateId, preferences);
            if (!preferences.isEmpty()) {
                freeCandidates.add(candidateId);
            } else {
                trace.emit("NO_OPTIONS", candidateId, null, null, "Candidato sem opções mutuamente aceitáveis na entrada do HR.");
            }
        }

        while (!freeCandidates.isEmpty()) {
            String candidateId = freeCandidates.remove();
            LinkedHashSet<String> preferences = pendingPreferences.get(candidateId);
            if (preferences.isEmpty()) {
                trace.emit("OPTIONS_EXHAUSTED", candidateId, null, null, "Todas as opções foram consumidas ou excluídas.");
                continue;
            }

            // Consume the next proposal, so deleting other options cannot shift a pointer.
            String vacancyId = preferences.removeFirst();
            HospitalState hospital = hospitals.get(vacancyId);
            trace.emit("PROPOSAL", candidateId, vacancyId, null, "Proposta para a primeira opção ainda disponível.");
            hospital.assigned.add(candidateId);
            trace.emit("PROVISIONAL_ACCEPTANCE", candidateId, vacancyId, null, "Aceitação provisória; ainda sujeita à capacidade e às propostas seguintes.");

            if (hospital.assigned.size() > k) {
                String rejectedId = hospital.assigned.remove();
                trace.emit("CAPACITY_REJECTION", rejectedId, vacancyId, candidateId,
                        "Rejeitado por ser o pior rank entre os K+1 aceitos; causa é a proposta que excedeu a capacidade.");
                if (!pendingPreferences.get(rejectedId).isEmpty()) {
                    freeCandidates.add(rejectedId);
                    trace.emit("REQUEUED", rejectedId, vacancyId, candidateId, "Candidato volta à fila para tentar a próxima opção disponível.");
                } else {
                    trace.emit("OPTIONS_EXHAUSTED", rejectedId, vacancyId, candidateId, "Candidato rejeitado sem opções restantes.");
                }
            }

            if (hospital.assigned.size() == k) {
                int firstWorseRank = hospital.ranks.get(hospital.assigned.element()) + 1;
                // The worst accepted rank can only improve. Visit each deleted pair once.
                for (int rank = firstWorseRank; rank < hospital.firstDeletedRank; rank++) {
                    String worseCandidateId = hospital.preferences.get(rank);
                    if (pendingPreferences.get(worseCandidateId).remove(vacancyId)) {
                        trace.emit("DELETE_STEP", worseCandidateId, vacancyId, hospital.assigned.element(),
                                "Opção removida: rank pior que o pior candidato aceito na vaga cheia.");
                    }
                }
                hospital.firstDeletedRank = firstWorseRank;
            }
        }

        Map<VacancyDTO, List<CandidateDTO>> shortlists = new LinkedHashMap<>();
        Set<String> unassignedIds = new LinkedHashSet<>(candidateMap.keySet());
        for (Map.Entry<String, VacancyDTO> entry : vacancyMap.entrySet()) {
            HospitalState hospital = hospitals.get(entry.getKey());
            List<String> assignedIds = new ArrayList<>(hospital.assigned);
            assignedIds.sort(Comparator.comparingInt(hospital.ranks::get));
            List<CandidateDTO> shortlist = assignedIds.stream().map(candidateMap::get).toList();
            shortlists.put(entry.getValue(), shortlist);
            unassignedIds.removeAll(assignedIds);
            for (String assignedId : assignedIds) {
                trace.emit("FINAL_ALLOCATION", assignedId, entry.getKey(), null, "Alocação confirmada ao encerrar o HR.");
            }
        }

        for (String unassignedId : unassignedIds) {
            trace.emit("FINAL_UNASSIGNED", unassignedId, null, null, "Candidato terminou sem alocação.");
        }

        return new AllocationResult(shortlists, unassignedIds, candidateMap.size() - unassignedIds.size());
    }

    private static <T> Map<String, T> indexParticipants(List<T> participants, Function<T, String> idOf, String kind) {
        if (participants == null) {
            throw new IllegalArgumentException("A lista de " + kind + " não pode ser nula.");
        }
        Map<String, T> indexed = new LinkedHashMap<>();
        for (T participant : participants) {
            String id = participant == null ? null : idOf.apply(participant);
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Todo participante do tipo " + kind + " deve possuir ID.");
            }
            if (indexed.putIfAbsent(id, participant) != null) {
                throw new IllegalArgumentException("ID duplicado de " + kind + ": " + id);
            }
        }
        return indexed;
    }

    private static LinkedHashSet<String> knownPreferences(
            Map<String, List<String>> preferences,
            String participantId,
            Set<String> knownIds
    ) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        List<String> entries = preferences == null ? null : preferences.get(participantId);
        if (entries != null) {
            for (String id : entries) {
                if (id != null && knownIds.contains(id)) {
                    result.add(id);
                }
            }
        }
        return result;
    }

    private static final class Trace {
        private final Consumer<HrEvent> observer;
        private final Map<String, List<String>> preferences;
        private final Map<String, HospitalState> hospitals;
        private final int capacity;
        private long sequence;

        private Trace(Consumer<HrEvent> observer, Map<String, List<String>> preferences,
                      Map<String, HospitalState> hospitals, int capacity) {
            this.observer = observer;
            this.preferences = preferences == null ? Map.of() : preferences;
            this.hospitals = hospitals;
            this.capacity = capacity;
        }

        private void emit(String type, String candidateId, String vacancyId, String cause, String text) {
            HospitalState hospital = hospitals.get(vacancyId);
            List<String> choices = preferences.get(candidateId);
            int index = choices == null || vacancyId == null ? -1 : choices.indexOf(vacancyId);
            Integer rank = hospital == null ? null : hospital.ranks.get(candidateId);
            Integer causeRank = hospital == null ? null : hospital.ranks.get(cause);
            observer.accept(new HrEvent(++sequence, type, candidateId, vacancyId, cause,
                    index < 0 ? null : index + 1, rank == null ? null : rank + 1,
                    causeRank == null ? null : causeRank + 1, hospital == null ? 0 : hospital.assigned.size(), capacity, text));
        }
    }

    /** Mutable state for one vacancy, created afresh for each matching invocation. */
    private static final class HospitalState {
        private final List<String> preferences;
        private final Map<String, Integer> ranks = new HashMap<>();
        private final PriorityQueue<String> assigned;
        private int firstDeletedRank;

        private HospitalState(List<String> preferences) {
            this.preferences = preferences;
            for (int rank = 0; rank < preferences.size(); rank++) {
                ranks.put(preferences.get(rank), rank);
            }
            assigned = new PriorityQueue<>(Comparator.comparingInt((String id) -> ranks.get(id)).reversed());
            firstDeletedRank = preferences.size();
        }
    }
}
