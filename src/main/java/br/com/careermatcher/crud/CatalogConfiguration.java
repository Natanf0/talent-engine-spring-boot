package br.com.careermatcher.crud;
import br.com.careermatcher.repositories.CatalogRepository;
import org.springframework.context.annotation.*;
import org.springframework.boot.ApplicationRunner;
@Configuration
public class CatalogConfiguration {
    @Bean ApplicationRunner languageCatalogInitializer(CatalogRepository repository) {
        return args -> repository.seedLanguages();
    }
}
