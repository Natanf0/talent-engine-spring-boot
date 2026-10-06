package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.Modalidade;
import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Resume;
import br.com.careermatcher.models.Vacancy;
import org.springframework.test.util.ReflectionTestUtils;

final class RankerFixtures {

    private RankerFixtures() {
    }

    static Candidate candidate(Resume resume) {
        return candidate(null, "Candidate", resume);
    }

    static Candidate candidate(Long id, String name, Resume resume) {
        Candidate candidate = new Candidate(
                name, "About", "Developer", "0000");
        candidate.setResume(resume);
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }

    static Resume resume() {
        return new Resume();
    }

    static Vacancy vacancy(SENIORITY seniority) {
        return vacancy(null, "Developer", seniority, Modalidade.PRESENCIAL);
    }

    static Vacancy vacancy(Long id, String role, SENIORITY seniority, Modalidade modalidade) {
        Vacancy vacancy = new Vacancy(role, "Rio de Janeiro", seniority, modalidade);
        ReflectionTestUtils.setField(vacancy, "id", id);
        return vacancy;
    }
}
