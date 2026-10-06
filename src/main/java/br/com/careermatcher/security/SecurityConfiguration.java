package br.com.careermatcher.security;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.*;
import java.time.*;
import java.util.Base64;

@Configuration
public class SecurityConfiguration {
    @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean public ApplicationRunner userSchema(UserRepository users) { return ignored -> users.initialize(); }
    @Bean public JwtEncoder jwtEncoder(ResourceLoader resources, @Value("${auth.private-key:}") String privateLocation,
                                      @Value("${auth.public-key:}") String publicLocation) throws Exception {
        var factory=KeyFactory.getInstance("RSA");
        var publicKey=(RSAPublicKey)factory.generatePublic(new X509EncodedKeySpec(pem(resources,publicLocation,"PUBLIC KEY")));
        var privateKey=(RSAPrivateKey)factory.generatePrivate(new PKCS8EncodedKeySpec(pem(resources,privateLocation,"PRIVATE KEY")));
        if (publicKey.getModulus().bitLength()<2048 || !publicKey.getModulus().equals(privateKey.getModulus()))
            throw new IllegalStateException("Configure um par RSA correspondente com pelo menos 2048 bits.");
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder(publicKey).privateKey(privateKey).build())));
    }
    @Bean public JwtDecoder jwtDecoder(ResourceLoader resources, @Value("${auth.public-key:}") String location,
                                      @Value("${auth.issuer:talentengine}") String issuer, Clock clock) throws Exception {
        var key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pem(resources,location,"PUBLIC KEY")));
        var decoder=NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        var timestamps=new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> claims=jwt -> jwt.getExpiresAt()!=null && jwt.getIssuedAt()!=null && jwt.getSubject()!=null
                && !jwt.getSubject().isBlank() && jwt.getAudience().equals(java.util.List.of("talentengine-api"))
                && jwt.getExpiresAt().equals(jwt.getIssuedAt().plusSeconds(3600))
                && jwt.getExpiresAt().isAfter(clock.instant()) && !jwt.getIssuedAt().isAfter(clock.instant())
                ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,new JwtIssuerValidator(issuer),claims));
        return decoder;
    }
    // A stale bearer token must not prevent access to a shared vacancy URL.
    @Bean @Order(1)
    public SecurityFilterChain publicVacancyFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(new RegexRequestMatcher("^/api/careermatcher/vagas/[0-9]+(?:\\?.*)?$", "GET"))
                .csrf(csrf -> csrf.disable()).cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean @Order(2) public SecurityFilterChain securityFilterChain(HttpSecurity http, UserAuthenticationConverter converter) throws Exception {
        return http.csrf(csrf->csrf.disable()).cors(cors->{})
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth->auth
                    .requestMatchers(HttpMethod.POST,"/api/auth/login","/api/auth/register/candidate","/api/auth/invitations/validate","/api/auth/invitations/complete").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMINISTRADOR")
                    .requestMatchers("/api/me/**").hasRole("CANDIDATO")
                    .requestMatchers("/api/careermatcher/**","/api/matching/**").hasAnyRole("ADMINISTRADOR","GERENTE","RECRUTADOR")
                    .requestMatchers("/api/auth/me").authenticated()
                    .anyRequest().denyAll())
                .oauth2ResourceServer(oauth->oauth.jwt(jwt->jwt.jwtAuthenticationConverter(converter)))
                .build();
    }
    private static byte[] pem(ResourceLoader resources,String location,String kind) throws Exception {
        if (location==null || location.isBlank()) throw new IllegalStateException("Configure AUTH_PRIVATE_KEY e AUTH_PUBLIC_KEY com recursos PEM externos.");
        try(var stream=resources.getResource(location).getInputStream()) {
            String value=new String(stream.readAllBytes(),StandardCharsets.US_ASCII)
                    .replace("-----BEGIN "+kind+"-----","").replace("-----END "+kind+"-----","").replaceAll("\\s","");
            return Base64.getDecoder().decode(value);
        }
    }
}
