package kr.ac.chosun.mspace.gateway;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import reactor.core.publisher.Mono;

/**
 * 인증·인가를 게이트웨이에서 먼저 한다.
 * <ul>
 *   <li>공개 API({@link PublicPaths}): 토큰 없이 통과. 토큰이 붙어 와도 보지 않는다 (만료 토큰으로 refresh하는 경우)</li>
 *   <li>나머지 /api/**: 맞는 JWT가 있어야 통과, 없거나 틀리면 401</li>
 *   <li>/api 밖(화면 파일): 통과</li>
 * </ul>
 * core도 지금처럼 토큰을 한 번 더 확인한다 (core 코드는 바꾸지 않음).
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        ServerWebExchangeMatcher publicPaths = PublicPaths.matcher();
        return http
                // 상태를 안 가지는 프록시. 쿠키 세션이 없어서 CSRF 토큰도 쓰지 않는다
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(ex -> ex
                        .matchers(publicPaths).permitAll()
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().permitAll())
                .oauth2ResourceServer(o -> o
                        .bearerTokenConverter(skipOn(publicPaths))
                        .jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(GatewayJwtProperties props) {
        if (props.secret() == null || props.secret().getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET이 없거나 32바이트보다 짧아요. core와 같은 값을 넣어 주세요");
        }
        SecretKeySpec key = new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));   // 만료(exp) + 발급자(iss)
        return decoder;
    }

    /** 공개 API에서는 Authorization 헤더를 읽지 않는다. 그 밖에서는 보통의 Bearer 토큰 읽기 */
    private static ServerAuthenticationConverter skipOn(ServerWebExchangeMatcher publicPaths) {
        ServerBearerTokenAuthenticationConverter bearer = new ServerBearerTokenAuthenticationConverter();
        return exchange -> publicPaths.matches(exchange)
                .flatMap(m -> m.isMatch() ? Mono.empty() : bearer.convert(exchange));
    }
}
