package br.com.careermatcher.ai;
import java.time.Duration;
final class AiTestSupport {
    static AiExtractionProperties properties() { return properties(true, 5242880, 20, 60000); }
    static AiExtractionProperties properties(boolean enabled, long bytes, int pages, int chars) {
        return new AiExtractionProperties(enabled, bytes, pages, chars, 2,
                new AiExtractionProperties.Deepseek("test-secret", "https://deepseek.test", "deepseek-flash",
                        Duration.ofSeconds(5), Duration.ofSeconds(60), 8192));
    }
}
