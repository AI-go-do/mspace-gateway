package kr.ac.chosun.mspace.gateway;

import java.time.Duration;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 게이트웨이가 core·agent·web으로 요청을 보낼 때 쓰는 HTTP 클라이언트 설정.
 */
@Configuration
class HttpClientConfig {

    /** core 등의 주소(IP)를 기억하는 최대 시간 */
    static final Duration DNS_CACHE_MAX_TTL = Duration.ofSeconds(10);

    /**
     * 배포 때 core 컨테이너를 새로 만들면 IP가 바뀐다. Docker 내부 DNS는 TTL을 길게(600초) 주기 때문에
     * 그대로 따르면 게이트웨이가 몇 분 동안 옛 IP로 보내 실패한다. 예전 nginx의 resolver valid=10s와 같은 역할.
     */
    @Bean
    HttpClientCustomizer shortDnsCache() {
        return client -> client.resolver(spec -> spec.cacheMaxTimeToLive(DNS_CACHE_MAX_TTL));
    }
}
