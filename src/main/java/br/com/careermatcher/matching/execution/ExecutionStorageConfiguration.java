package br.com.careermatcher.matching.execution;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ApplicationRunner;
import br.com.careermatcher.repositories.MatchingExecutionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(R2Properties.class)
public class ExecutionStorageConfiguration {
    @Bean public Clock executionClock() { return Clock.systemUTC(); }

    @Bean
    public ApplicationRunner executionSchema(MatchingExecutionRepository repository) {
        return ignored -> repository.initialize();
    }

    @Bean @Lazy
    public S3Client executionS3Client(R2Properties properties) {
        if (blank(properties.endpoint()) || blank(properties.accessKey()) || blank(properties.secretKey()))
            throw new IllegalStateException("Configure endpoint e credenciais R2.");
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(properties.attemptTimeout()))
                .overrideConfiguration(config -> config.retryStrategy(retry -> retry.maxAttempts(1))
                        .apiCallTimeout(properties.attemptTimeout()).apiCallAttemptTimeout(properties.attemptTimeout()))
                .build();
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
