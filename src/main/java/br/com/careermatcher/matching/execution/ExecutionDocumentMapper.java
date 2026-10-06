package br.com.careermatcher.matching.execution;

import br.com.careermatcher.DTOs.crud.CrudDtos.*;
import br.com.careermatcher.DTOs.matching.*;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ExecutionDocumentMapper {
    public List<ExecutionVacancy> map(AllocationResponse allocation, List<CandidateView> candidates) {
        Map<String, CandidateView> byId = candidates.stream().collect(Collectors.toMap(
                candidate -> candidate.id().toString(), Function.identity()));
        return allocation.shortlists().stream().map(shortlist -> {
            var v = shortlist.vacancy();
            var vacancy = new VacancyView(Long.valueOf(v.id()), v.role(), v.city(), v.seniority(), v.modalidade(),
                    v.skillRequirements(), v.experienceRequirement(), v.languageRequirements(),
                    v.undergraduateRequirements(), v.graduateRequirements());
            var allocated = shortlist.candidates().stream().map(details -> {
                CandidateView candidate = byId.get(details.id());
                if (candidate == null) throw new IllegalArgumentException("Candidato alocado ausente da execução.");
                return new CandidateView(candidate.id(), candidate.name(), candidate.about(),
                        candidate.professionalTitle(), candidate.phone(), candidate.email(), candidate.linkedinURL(),
                        candidate.personalWebsiteURL(), details.resume());
            }).toList();
            return new ExecutionVacancy(vacancy, allocated);
        }).toList();
    }
}
