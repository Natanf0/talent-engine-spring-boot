package br.com.careermatcher.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Runs before Spring Security; the internal endpoint uses a separate host-only secret. */
@Component
@Order(-110)
public class DeploymentFilter extends OncePerRequestFilter {
    private final DeploymentState state;
    private final ObjectMapper mapper;
    private final Set<String> origins;

    public DeploymentFilter(DeploymentState state, ObjectMapper mapper,
                            @Value("${auth.allowed-origins:}") String origins) {
        this.state = state;
        this.mapper = mapper;
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).collect(Collectors.toSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        String path = request.getRequestURI();
        if (path.equals("/internal/deploy/status")) {
            response.setHeader("Cache-Control", "no-store");
            if (!state.enabled()) { response.setStatus(404); return; }
            if (!state.authorized(request.getHeader("X-Deploy-Token"))) { response.setStatus(401); return; }
            if (!request.getMethod().equals("GET")) { response.setStatus(405); return; }
            response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(), state.status());
            return;
        }
        // Gate every application route, including encoded aliases, except the health probe.
        if (!state.enabled() || path.equals("/actuator/health")) {
            chain.doFilter(request, response);
            return;
        }
        if (!state.enterRequest()) {
            response.setStatus(503);
            response.setHeader("Retry-After", "30");
            response.setHeader("Cache-Control", "no-store");
            String origin = request.getHeader("Origin");
            if (origin != null && origins.contains(origin)) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Vary", "Origin");
            }
            response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(), java.util.Map.of("message",
                    "Atualização em andamento. Tente novamente em instantes."));
            return;
        }
        // Current API handlers are synchronous, including PDF generation and matching admission.
        try { chain.doFilter(request, response); }
        finally { state.leaveRequest(); }
    }
}
