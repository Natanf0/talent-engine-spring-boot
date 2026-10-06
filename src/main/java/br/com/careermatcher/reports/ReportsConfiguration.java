package br.com.careermatcher.reports;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.time.Duration;

@Configuration
public class ReportsConfiguration {
    @Bean("reportsRestClient")
    RestClient reportsRestClient(RestClient.Builder builder, @Value("${reports.base-url:http://localhost:8000}") String baseUrl) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5));
        requests.setReadTimeout(Duration.ofSeconds(60));
        return builder.baseUrl(baseUrl).requestFactory(requests).build();
    }
}
