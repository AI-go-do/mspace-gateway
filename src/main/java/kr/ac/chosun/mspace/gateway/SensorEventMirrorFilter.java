package kr.ac.chosun.mspace.gateway;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 센서 값 사본 넘기기 (FR-DEV-101·106, 11/12 재웅).
 * 젯슨이 보낸 {@code POST /api/sensor-events}를 core에 그대로 넘기고, core가 받아 주면(2xx) 같은 본문을 climate(이상 탐지·자동 제어)에도 준다.
 * <ul>
 *   <li>장치 토큰 확인은 core가 한다. core가 거절한 값(401 등)은 climate에 보내지 않는다</li>
 *   <li>사본은 기다리지 않는다(장치 응답이 늦어지지 않게). climate가 꺼져 있거나 느려도 로그 한 줄만 남기고 버린다</li>
 *   <li>{@code CLIMATE_MIRROR_URL}이 비면 아무것도 하지 않는다(기본값)</li>
 *   <li>사본 주소는 도커 안쪽 주소다. climate의 받는 길은 게이트웨이 밖으로 열지 않는다</li>
 * </ul>
 * 로그에는 본문을 남기지 않는다(방 번호·값만 들어 있어도 습관으로).
 */
@Component
class SensorEventMirrorFilter implements GlobalFilter, Ordered {

    static final String PATH = "/api/sensor-events";
    static final Duration TIMEOUT = Duration.ofSeconds(3);

    private static final Logger log = LoggerFactory.getLogger(SensorEventMirrorFilter.class);

    private final String target;
    private final WebClient client;

    SensorEventMirrorFilter(@Value("${mspace.climate.mirror-url:}") String target) {
        this.target = target == null ? "" : target.trim();
        this.client = WebClient.create();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var req = exchange.getRequest();
        if (target.isEmpty() || req.getMethod() != HttpMethod.POST || !PATH.equals(req.getPath().value())) {
            return chain.filter(exchange);
        }
        return ServerWebExchangeUtils.cacheRequestBody(exchange, cached -> {
            byte[] body = copy(exchange.getAttribute(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR));
            return chain.filter(exchange.mutate().request(cached).build())
                    .then(Mono.fromRunnable(() -> mirror(exchange.getResponse().getStatusCode(), body)));
        });
    }

    private void mirror(HttpStatusCode status, byte[] body) {
        if (status == null || !status.is2xxSuccessful() || body.length == 0) {
            return;
        }
        client.post().uri(target)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .timeout(TIMEOUT)
                .subscribe(ok -> { }, e -> log.warn("climate 사본 실패: {}", e.getClass().getSimpleName()));
    }

    private static byte[] copy(Object cached) {
        if (!(cached instanceof DataBuffer buf)) {
            return new byte[0];
        }
        byte[] out = new byte[buf.readableByteCount()];
        buf.toByteBuffer(buf.readPosition(), java.nio.ByteBuffer.wrap(out), 0, out.length);
        return out;
    }

    /** RemoveCachedBodyFilter(가장 먼저) 다음, core로 보내는 라우팅 필터보다 앞 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
