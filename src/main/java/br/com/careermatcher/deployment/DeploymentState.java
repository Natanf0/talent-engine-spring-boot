package br.com.careermatcher.deployment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** The host owns the maintenance flag; it survives container replacement. */
@Component
public class DeploymentState {
    private final Path directory;
    private final byte[] token;
    private int activeRequests;
    private int pendingMatchings;

    public DeploymentState(@Value("${deployment.control-directory:}") String directory,
                           @Value("${deployment.control-token:}") String token) {
        this.directory = directory.isBlank() ? null : Path.of(directory);
        this.token = token.getBytes(StandardCharsets.UTF_8);
        if (enabled() && (this.token.length < 32 || !Files.isDirectory(this.directory)
                || !Files.isReadable(this.directory)))
            throw new IllegalStateException("Deployment control requires a readable directory and a token of at least 32 bytes.");
    }

    public boolean enabled() { return directory != null; }

    public boolean authorized(String supplied) {
        return enabled() && supplied != null
                && MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8));
    }

    private boolean draining() {
        // Unknown/unreadable state fails closed. Never use a cached view of this file.
        return enabled() && (!Files.isDirectory(directory) || !Files.isReadable(directory)
                || !Files.notExists(directory.resolve("maintenance")));
    }

    public synchronized boolean enterRequest() {
        if (draining()) return false;
        activeRequests++;
        return true;
    }

    public synchronized void leaveRequest() { activeRequests--; }
    public synchronized void matchingAccepted() { pendingMatchings++; }
    public synchronized void matchingFinished() { pendingMatchings--; }

    public synchronized Status status() {
        return new Status(1, draining(), activeRequests, pendingMatchings);
    }

    public record Status(int protocol, boolean draining, int activeRequests, int pendingMatchings) {}
}
