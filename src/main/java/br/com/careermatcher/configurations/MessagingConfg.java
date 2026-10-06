package br.com.careermatcher.configurations;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class MessagingConfg {
    @Bean
    public SimpleMessageConverter messageConverter() {
        SimpleMessageConverter converter = new SimpleMessageConverter();
        converter.setAllowedListPatterns(List.of(
                "br.com.careermatcher.DTOs.*",
                "br.com.careermatcher.enums.SENIORITY",
                "java.lang.Enum"
        ));
        return converter;

    }
}
