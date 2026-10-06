package br.com.careermatcher.matching.execution;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class R2ExecutionStorageTest {
    @TempDir Path directory;
    private HttpServer server;
    private S3Client client;
    private R2ExecutionStorage storage;
    private final List<Request> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private record Request(String method, String path, String contentType, String encoding, byte[] body) {}

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("Content-Encoding"), exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(responseStatus.get(), -1);
            exchange.close();
        });
        server.start();
        var properties = new R2Properties("http://127.0.0.1:" + server.getAddress().getPort(),
                "reports", "test-access", "test-secret", Duration.ofSeconds(2));
        client = new ExecutionStorageConfiguration().executionS3Client(properties);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("s3", client);
        storage = new R2ExecutionStorage(beans.getBeanProvider(S3Client.class), properties);
    }
    @AfterEach void close() { if (client != null) client.close(); if (server != null) server.stop(0); }

    @Test void sendsCompressedBytesWithExpectedKeyAndDeletesSameObject() throws Exception {
        Path file = directory.resolve("execution.json.gz");
        try (var gzip = new java.util.zip.GZIPOutputStream(Files.newOutputStream(file))) {
            gzip.write("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String key = "companies/1/executions/RUN-example.json.gz";
        storage.put(storage.bucket(), key, file, "application/gzip");
        storage.delete(storage.bucket(), key);
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst().method()).isEqualTo("PUT");
        assertThat(requests.getFirst().path()).isEqualTo("/reports/" + key);
        assertThat(requests.getFirst().contentType()).isEqualTo("application/gzip");
        assertThat(requests.getFirst().encoding()).isNull();
        assertThat(requests.getFirst().body()).isEqualTo(Files.readAllBytes(file));
        assertThat(requests.getLast().method()).isEqualTo("DELETE");
        assertThat(requests.getLast().path()).isEqualTo("/reports/" + key);
    }

    @Test void sdkDoesNotMultiplyTheServiceRetryCount() throws Exception {
        Path file = Files.writeString(directory.resolve("test.gz"), "payload");
        responseStatus.set(503);
        assertThatThrownBy(() -> storage.put(storage.bucket(), "test.gz", file, "application/gzip"))
                .isInstanceOf(S3Exception.class);
        assertThat(requests).hasSize(1);
    }
}
