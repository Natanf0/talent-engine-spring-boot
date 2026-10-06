package br.com.careermatcher.reports;

import br.com.careermatcher.matching.execution.ExecutionObjectStorage;
import br.com.careermatcher.matching.execution.GzipExecutionCompressor;
import br.com.careermatcher.repositories.MatchingExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MatchingReportServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final byte[] PDF = "%PDF-1.7\nreport\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static RSAPublicKey publicKey;
    private static JwtEncoder encoder;
    private final MatchingExecutionRepository executions = mock(MatchingExecutionRepository.class);
    private final ExecutionSnapshotReader snapshots = mock(ExecutionSnapshotReader.class);
    private MockRestServiceServer server;
    private RestClient client;
    private MatchingReportService service;

    @BeforeAll static void signingKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        publicKey = (RSAPublicKey) pair.getPublic();
        var key = new RSAKey.Builder(publicKey).privateKey((RSAPrivateKey) pair.getPrivate()).keyID("test-reports").build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @BeforeEach void setup() throws Exception {
        var builder = RestClient.builder().baseUrl("http://reports.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        service = serviceUsing(snapshots);
        var execution = ExecutionSnapshotReaderTest.execution(2, "gzip");
        when(executions.find(17, "RUN-1")).thenReturn(Optional.of(execution));
        when(snapshots.read(17, execution)).thenReturn(new ReportPayload(17, "RUN-1", List.of(
                new ReportPayload.Vacancy(10, "Engenheiro", null, null, null, "Java", 1,
                        List.of(new ReportPayload.Candidate(20, "Ana", "Desenvolvedora", null, null))))));
    }

    @Test void authorizesTheExecutionInCurrentCompanyBeforeReadingStorage() {
        var storage = mock(ExecutionObjectStorage.class);
        var reader = new ExecutionSnapshotReader(storage, List.of(new GzipExecutionCompressor()), new ObjectMapper());
        when(executions.find(17, "OTHER-COMPANY-RUN")).thenReturn(Optional.empty());
        var error = assertThrows(ResponseStatusException.class,
                () -> serviceUsing(reader).generate("OTHER-COMPANY-RUN"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(executions).find(17, "OTHER-COMPANY-RUN");
        verifyNoInteractions(storage);
        server.verify();
    }

    @Test void sendsAuthorizedSnapshotAndRealSignedServiceTokenWithOneMinuteLifetime() {
        server.expect(requestTo("http://reports.test/api/v1/reports/matching-pdf"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_PDF_VALUE))
                .andExpect(content().json("""
                        {"companyId":17,"executionId":"RUN-1","vacancies":[{"id":10,"role":"Engenheiro",
                          "seniority":null,"city":null,"modalidade":null,"description":"Java","allocatedCount":1,
                          "candidates":[{"id":20,"name":"Ana","professionalTitle":"Desenvolvedora","phone":null,"email":null}]}]}
                        """, true))
                .andExpect(request -> {
                    try {
                        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                        assertNotNull(authorization);
                        assertTrue(authorization.startsWith("Bearer "));
                        var jwt = SignedJWT.parse(authorization.substring(7));
                        assertEquals(JWSAlgorithm.RS256, jwt.getHeader().getAlgorithm());
                        assertTrue(jwt.verify(new RSASSAVerifier(publicKey)));
                        var claims = jwt.getJWTClaimsSet();
                        assertEquals("talentengine-test", claims.getIssuer());
                        assertEquals("talentengine-springboot", claims.getSubject());
                        assertEquals(List.of("talentengine-reports"), claims.getAudience());
                        assertEquals("matching:pdf", claims.getStringClaim("scope"));
                        assertEquals(17L, claims.getLongClaim("companyId"));
                        assertEquals("RUN-1", claims.getStringClaim("executionId"));
                        assertEquals(NOW, claims.getIssueTime().toInstant());
                        assertEquals(NOW.plusSeconds(60), claims.getExpirationTime().toInstant());
                        assertNull(claims.getClaim("role"));
                    } catch (Exception error) { throw new AssertionError(error); }
                }).andRespond(withSuccess(PDF, MediaType.APPLICATION_PDF));
        assertArrayEquals(PDF, service.generate("RUN-1"));
        server.verify();
    }

    @Test void mapsSnapshotFailureToGatewayErrorWithoutCallingReports() throws Exception {
        when(snapshots.read(eq(17L), any())).thenThrow(new IOException("private-bucket/secret-key"));
        var error = assertThrows(ResponseStatusException.class, () -> service.generate("RUN-1"));
        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
        assertFalse(error.getReason().contains("secret-key"));
        server.verify();
    }

    @ParameterizedTest @ValueSource(ints = {301, 401, 403, 404, 422, 500, 503})
    void mapsEveryUpstreamFailureIncludingAuthenticationToBadGateway(int status) {
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_PDF).body(PDF));
        assertBadGateway();
    }

    @Test void mapsNetworkFailureToBadGateway() {
        server.expect(anything()).andRespond(withException(new IOException("private upstream error")));
        assertBadGateway();
    }

    @ParameterizedTest @ValueSource(strings = {"application/json", "text/html", "application/octet-stream"})
    void rejectsUnexpectedMediaTypesEvenWhenBodyLooksLikePdf(String contentType) {
        server.expect(anything()).andRespond(withSuccess(PDF, MediaType.parseMediaType(contentType)));
        assertBadGateway();
    }

    @Test void rejectsMissingContentType() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.OK).body(PDF));
        assertBadGateway();
    }

    @ParameterizedTest @ValueSource(strings = {"", "%PDF", "{\"error\":\"private\"}", "<!DOCTYPE html>"})
    void rejectsBodiesWithoutPdfSignature(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_PDF));
        assertBadGateway();
    }

    @Test void boundsUpstreamPdfEvenWithAValidSignature() {
        var input = new SequenceInputStream(new ByteArrayInputStream(PDF),
                ExecutionSnapshotReaderTest.repeatedBytes(64 * 1024 * 1024));
        server.expect(anything()).andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_PDF)
                .body(new InputStreamResource(input)));
        assertBadGateway();
    }

    private void assertBadGateway() {
        var error = assertThrows(ResponseStatusException.class, () -> service.generate("RUN-1"));
        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
        assertEquals("Serviço de relatórios indisponível. Tente novamente.", error.getReason());
        server.verify();
    }

    private MatchingReportService serviceUsing(ExecutionSnapshotReader reader) {
        return new MatchingReportService(() -> 17, executions, reader, client, encoder,
                Clock.fixed(NOW, ZoneOffset.UTC), "talentengine-test");
    }
}
