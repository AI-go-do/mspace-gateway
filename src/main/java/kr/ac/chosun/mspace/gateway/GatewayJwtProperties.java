package kr.ac.chosun.mspace.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * core와 같은 JWT 설정. core가 HS256으로 서명한 토큰을 여기서 먼저 확인한다.
 *
 * @param secret core와 같은 JWT_SECRET (32바이트 이상)
 * @param issuer core가 토큰에 넣는 iss (mspace-core)
 */
@ConfigurationProperties("mspace.jwt")
public record GatewayJwtProperties(String secret, String issuer) {
}
