package br.com.careermatcher.reports;

import br.com.careermatcher.matching.execution.CurrentCompany;
import br.com.careermatcher.repositories.MatchingExecutionRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.time.Clock;
import java.util.List;

@Service
public class MatchingReportService {
    private static final int MAX_PDF_BYTES = 64 * 1024 * 1024;
    private final CurrentCompany company;
    private final MatchingExecutionRepository executions;
    private final ExecutionSnapshotReader snapshots;
    private final RestClient reports;
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;

    public MatchingReportService(CurrentCompany company, MatchingExecutionRepository executions,
                                 ExecutionSnapshotReader snapshots, @Qualifier("reportsRestClient") RestClient reports,
                                 JwtEncoder encoder, Clock clock, @Value("${auth.issuer:talentengine}") String issuer) {
        this.company = company; this.executions = executions; this.snapshots = snapshots;
        this.reports = reports; this.encoder = encoder; this.clock = clock; this.issuer = issuer;
    }

    public byte[] generate(String executionId) {
        long companyId = company.id();
        // Authorize before reading any object. Object keys are never accepted from the caller.
        var execution = executions.find(companyId, executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execução não encontrada."));
        ReportPayload payload;
        try { payload = snapshots.read(companyId, execution); }
        catch (IOException | RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível ler a execução para gerar o relatório.", error);
        }
        var now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject("talentengine-springboot")
                .audience(List.of("talentengine-reports")).issuedAt(now).expiresAt(now.plusSeconds(60))
                .claim("scope", "matching:pdf").claim("companyId", companyId).claim("executionId", executionId).build();
        try {
            String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
            return reports.post().uri("/api/v1/reports/matching-pdf").contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_PDF).headers(headers -> headers.setBearerAuth(token)).body(payload)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()
                                || response.getHeaders().getContentType() == null
                                || !MediaType.APPLICATION_PDF.isCompatibleWith(response.getHeaders().getContentType()))
                            throw new IOException("Resposta inválida do serviço de relatórios.");
                        byte[] pdf = response.getBody().readNBytes(MAX_PDF_BYTES + 1);
                        if (pdf.length > MAX_PDF_BYTES || pdf.length < 5
                                || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F' || pdf[4] != '-')
                            throw new IOException("PDF inválido recebido do serviço de relatórios.");
                        return pdf;
                    });
        } catch (RuntimeException error) {
            // In particular, a downstream 401 must not invalidate the browser's valid user session.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Serviço de relatórios indisponível. Tente novamente.", error);
        }
    }
}
