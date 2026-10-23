package br.com.careermatcher.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AiExtractionProperties.class)
public class AiExtractionConfiguration {
    @Bean("deepSeekRestClient")
    RestClient deepSeekRestClient(RestClient.Builder builder, AiExtractionProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.deepseek().connectTimeout());
        factory.setReadTimeout(properties.deepseek().readTimeout());
        return builder.baseUrl(properties.deepseek().baseUrl()).requestFactory(factory).build();
    }
}
