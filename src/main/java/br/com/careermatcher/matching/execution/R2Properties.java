package br.com.careermatcher.matching.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("storage.r2")
public record R2Properties(String endpoint, String bucket, String accessKey, String secretKey,
                           Duration attemptTimeout) {
    public R2Properties {
        if (attemptTimeout == null) attemptTimeout = Duration.ofSeconds(30);
        if (attemptTimeout.isNegative() || attemptTimeout.isZero())
            throw new IllegalArgumentException("O timeout R2 deve ser positivo.");
    }
    // Never render credentials in configuration errors or logs.
    @Override public String toString() { return "R2Properties[redacted]"; }
}
