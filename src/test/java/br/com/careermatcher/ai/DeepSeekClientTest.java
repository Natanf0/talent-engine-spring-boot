package br.com.careermatcher.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DeepSeekClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private DeepSeekClient client;
    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("https://deepseek.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DeepSeekClient(builder.build(), AiTestSupport.properties(), mapper);
    }
    @Test void sendsJsonContractWithDisabledThinkingAndParsesResponse() throws Exception {
        String body = envelope("{\"data\":{\"name\":\"Ana\"},\"warnings\":[]}", "stop");
        server.expect(requestTo("https://deepseek.test/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-secret"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.max_tokens").value(8192))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(call().data().path("name").asText()).isEqualTo("Ana");
        server.verify();
    }
    @Test void rejectsEmptyMalformedAndTruncatedResponses() throws Exception {
        for (String body : List.of("{}", "not-json", envelope("", "stop"), envelope("{}", "length"), envelope("[]", "stop"))) {
            setup(); server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(this::call).isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode().value()).isEqualTo(502));
            server.verify();
        }
    }
    @Test void mapsProviderFailuresWithoutExposingBodiesOrRetrying() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.PAYMENT_REQUIRED, HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.TOO_MANY_REQUESTS)) {
            setup(); server.expect(anything()).andRespond(withStatus(status).body("secret provider details"));
            assertThatThrownBy(this::call).isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode().value()).isEqualTo(status.value() == 429 ? 429 : 502);
                assertThat(exception.getReason()).doesNotContain("secret");
            });
            server.verify();
        }
    }
    @Test void mapsTimeout() {
        server.expect(anything()).andRespond(withException(new java.net.SocketTimeoutException()));
        assertThatThrownBy(this::call).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(504));
    }
    private AiExtractionDtos.CompletionResult call() { return client.extract(List.of(new AiExtractionDtos.Message("system", "json"))); }
    private String envelope(String content, String finish) throws Exception {
        var root = mapper.createObjectNode();
        var choice = root.putArray("choices").addObject(); choice.put("finish_reason", finish);
        choice.putObject("message").put("content", content);
        return mapper.writeValueAsString(root);
    }
}
