package br.com.careermatcher.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("ai.extraction")
public record AiExtractionProperties(boolean enabled, long maxPdfBytes, int maxPages, int maxTextCharacters,
                                     int maxConcurrent, Deepseek deepseek) {
    public record Deepseek(String apiKey, String baseUrl, String model, Duration connectTimeout,
                           Duration readTimeout, int maxOutputTokens) {}
    public AiExtractionProperties {
        if (maxPdfBytes < 1 || maxPdfBytes > Integer.MAX_VALUE - 1 || maxPages < 1 || maxTextCharacters < 1 || maxConcurrent < 1)
            throw new IllegalArgumentException("Limites de extração de IA inválidos.");
        if (deepseek == null || deepseek.baseUrl() == null || deepseek.model() == null
                || deepseek.connectTimeout().isNegative() || deepseek.connectTimeout().isZero()
                || deepseek.readTimeout().isNegative() || deepseek.readTimeout().isZero() || deepseek.maxOutputTokens() < 1)
            throw new IllegalArgumentException("Configuração DeepSeek inválida.");
        if (enabled && (deepseek.apiKey() == null || deepseek.apiKey().isBlank()))
            throw new IllegalStateException("Configure DEEPSEEK_API_KEY para habilitar o preenchimento com IA.");
    }
}
