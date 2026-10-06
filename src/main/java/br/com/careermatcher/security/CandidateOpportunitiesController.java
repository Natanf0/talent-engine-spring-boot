package br.com.careermatcher.security;

import br.com.careermatcher.DTOs.crud.CrudDtos.PageView;
import br.com.careermatcher.DTOs.crud.CrudDtos.VacancySummary;
import br.com.careermatcher.repositories.CompanyInterestRepository;
import br.com.careermatcher.repositories.CompanyInterestRepository.PartnerCompany;
import br.com.careermatcher.services.VagaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class CandidateOpportunitiesController {
    private final CurrentActor actor;
    private final CompanyInterestRepository companies;
    private final VagaService vacancies;

    @GetMapping("/companies")
    public PageView<PartnerCompany> companies(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        return companies.findPage(page, size);
    }

    @PutMapping("/companies/{companyId}/interest")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void interested(@PathVariable long companyId) { companies.setInterest(companyId, true); }

    @DeleteMapping("/companies/{companyId}/interest")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void notInterested(@PathVariable long companyId) { companies.setInterest(companyId, false); }

    @GetMapping("/vacancies")
    public PageView<VacancySummary> vacancies(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "10") int size,
                                            @RequestParam(required = false) String search,
                                            @RequestParam(required = false) String sort,
                                            @RequestParam(defaultValue = "asc") String direction) {
        actor.requireCandidate();
        return vacancies.findPage(page, size, search, sort, direction);
    }
}
