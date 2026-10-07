package br.com.careermatcher.configurations;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${auth.allowed-origins:http://localhost:5500,http://localhost:5501,http://localhost:4200}") String origins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(false);
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::strip).filter(s->!s.isBlank()).toList());
        config.setAllowedHeaders(List.of("Authorization","Content-Type"));
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        config.setExposedHeaders(List.of("Content-Disposition"));
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
