package br.com.careermatcher.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class DeploymentFilterTest {
    @TempDir Path directory;
    private final String token = "a-deployment-secret-with-at-least-32-bytes";
    private DeploymentState state() { return new DeploymentState(directory.toString(), token); }
    private DeploymentFilter filter(DeploymentState state) {
        return new DeploymentFilter(state, new ObjectMapper(), "https://talentengine.com.br");
    }

    @Test void countsRequestsAndQueuedWorkUntilCompletionEvenWhenMaintenanceBegins() throws Exception {
        var state = state();
        filter(state).doFilter(new MockHttpServletRequest("POST", "/api/matching/run"),
                new MockHttpServletResponse(), (request, response) -> {
                    assertThat(state.status().activeRequests()).isEqualTo(1);
                    Files.createFile(directory.resolve("maintenance"));
                    state.matchingAccepted();
                    assertThat(state.status().draining()).isTrue();
                });
        assertThat(state.status().activeRequests()).isZero();
        assertThat(state.status().pendingMatchings()).isEqualTo(1);
        state.matchingFinished();
        assertThat(state.status().pendingMatchings()).isZero();
    }

    @Test void restartedContainersRemainInMaintenanceAndHealthIsAvailable() throws Exception {
        Files.createFile(directory.resolve("maintenance"));
        var state = state();
        for (String path : new String[]{"/api/auth/login", "/api/matching/run", "/%61pi/matching/run"}) {
            var request = new MockHttpServletRequest("POST", path);
            request.addHeader("Origin", "https://talentengine.com.br");
            var response = new MockHttpServletResponse();
            filter(state).doFilter(request, response, (req, res) -> fail("Must not accept work"));
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader("Retry-After")).isEqualTo("30");
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://talentengine.com.br");
        }
        var health = new MockHttpServletResponse();
        filter(state).doFilter(new MockHttpServletRequest("GET", "/actuator/health"), health,
                (req, res) -> res.getWriter().write("UP"));
        assertThat(health.getContentAsString()).isEqualTo("UP");
        Files.delete(directory.resolve("maintenance"));
        assertThat(state.enterRequest()).isTrue();
        state.leaveRequest();
    }

    @Test void internalStatusRequiresItsOwnTokenAndDoesNotCountAsApplicationTraffic() throws Exception {
        var state = state();
        for (String supplied : new String[]{"wrong", token}) {
            var request = new MockHttpServletRequest("GET", "/internal/deploy/status");
            request.addHeader("X-Deploy-Token", supplied);
            var response = new MockHttpServletResponse();
            filter(state).doFilter(request, response, (req, res) -> fail("Internal endpoint must not enter MVC"));
            assertThat(response.getStatus()).isEqualTo(supplied.equals(token) ? 200 : 401);
            if (supplied.equals(token)) {
                var json = new ObjectMapper().readTree(response.getContentAsString());
                assertThat(json.path("protocol").asInt()).isEqualTo(1);
                assertThat(json.path("activeRequests").asInt()).isZero();
                assertThat(response.getContentAsString()).doesNotContain(token);
            }
        }
    }

    @Test void exceptionsReleaseRequestAndUnreadableControlDirectoryFailsClosed() throws Exception {
        var state = state();
        assertThatThrownBy(() -> filter(state).doFilter(new MockHttpServletRequest("GET", "/api/me/candidate"),
                new MockHttpServletResponse(), (req, res) -> { throw new IllegalStateException("test"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(state.status().activeRequests()).isZero();
        Files.delete(directory);
        assertThat(state.status().draining()).isTrue();
        assertThat(state.enterRequest()).isFalse();
    }

    @Test void disabledByDefaultAndRefusesWeakEnabledConfiguration() throws Exception {
        var disabled = new DeploymentState("", "");
        var response = new MockHttpServletResponse();
        filter(disabled).doFilter(new MockHttpServletRequest("GET", "/internal/deploy/status"), response,
                (req, res) -> fail("Endpoint must be hidden"));
        assertThat(response.getStatus()).isEqualTo(404);
        assertThatThrownBy(() -> new DeploymentState(directory.toString(), "short"))
                .isInstanceOf(IllegalStateException.class);
    }
}
