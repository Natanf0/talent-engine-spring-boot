package br.com.careermatcher.reports;

import java.util.List;

/** The only payload accepted by the PDF service; all values come from the authorized snapshot. */
public record ReportPayload(long companyId, String executionId, List<Vacancy> vacancies) {
    public record Candidate(long id, String name, String professionalTitle, String phone, String email) {}
    public record Vacancy(long id, String role, String seniority, String city, String modalidade,
                          String description, int allocatedCount, List<Candidate> candidates) {}
}
