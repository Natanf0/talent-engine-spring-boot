package br.com.careermatcher.services;

import br.com.careermatcher.DTOs.company.CompanyDtos.CompanyAboutRequest;
import br.com.careermatcher.DTOs.company.CompanyDtos.CompanyView;
import br.com.careermatcher.security.CurrentActor;
import br.com.careermatcher.validations.TextSanitizer;
import br.com.careermatcher.validations.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CompanyService {
    private static final int ABOUT_LIMIT = 5000;
    private final Neo4jClient client;
    private final CurrentActor actor;
    private final TextSanitizer sanitizer;

    @Transactional(readOnly = true)
    public CompanyView find() {
        return read(actor.requireAdministrator());
    }

    public CompanyView update(CompanyAboutRequest request) {
        long companyId = actor.requireAdministrator();
        String about = clean(request == null ? null : request.about());
        if (client.query("MATCH (company:COMPANY) WHERE id(company)=$companyId "
                        + "SET company.about=$about RETURN id(company) AS id")
                .bind(companyId).to("companyId")
                .bind(about).to("about")
                .fetch().one().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada.");
        }
        return read(companyId);
    }

    private CompanyView read(long companyId) {
        return client.query("MATCH (company:COMPANY) WHERE id(company)=$companyId "
                        + "RETURN id(company) AS id, company.name AS name, company.about AS about")
                .bind(companyId).to("companyId").fetch().one()
                .map(row -> new CompanyView(
                        ((Number) row.get("id")).longValue(),
                        row.get("name") == null ? null : row.get("name").toString(),
                        row.get("about") == null ? null : row.get("about").toString()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada."));
    }

    private String clean(String value) {
        if (value == null) return null;
        String sanitized = sanitizer.html(value);
        if (sanitized.codePointCount(0, sanitized.length()) > ABOUT_LIMIT)
            throw new ValidationException(List.of(new ValidationException.FieldError(
                    "about", "Máximo de " + ABOUT_LIMIT + " caracteres.")));
        return sanitizer.plain(sanitized).isBlank() ? null : sanitized;
    }
}
