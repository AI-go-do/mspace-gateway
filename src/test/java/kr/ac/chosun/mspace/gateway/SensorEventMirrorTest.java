package kr.ac.chosun.mspace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 센서 값 사본 넘기기. 가짜 core는 장치 토큰이 "good"이면 200, 아니면 401을 준다.
 * 가짜 climate는 받은 본문을 줄에 쌓는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SensorEventMirrorTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    static final BlockingQueue<String> CORE_GOT = new LinkedBlockingQueue<>();
    static final BlockingQueue<String> CLIMATE_GOT = new LinkedBlockingQueue<>();
    static final HttpServer CORE = server(true);
    static final HttpServer CLIMATE = server(false);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("mspace.jwt.secret", () -> SECRET);
        r.add("management.server.port", () -> "0");
        r.add("CORE_URI", () -> "http://127.0.0.1:" + CORE.getAddress().getPort());
        r.add("CLIMATE_URI", () -> "http://127.0.0.1:" + CLIMATE.getAddress().getPort());
        r.add("mspace.climate.mirror-url",
                () -> "http://127.0.0.1:" + CLIMATE.getAddress().getPort() + "/internal/sensor-events");
    }

    @AfterAll
    static void stop() {
        CORE.stop(0);
        CLIMATE.stop(0);
    }

    @BeforeEach
    void clear() {
        CORE_GOT.clear();
        CLIMATE_GOT.clear();
    }

    @Value("${local.server.port}")
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("core가 받은 센서 값은 같은 본문 그대로 climate에도 간다")
    void acceptedEventIsMirrored() throws Exception {
        String body = "{\"eventId\":\"e1\",\"roomId\":1,\"kind\":\"CO2\",\"ppm\":812}";
        HttpResponse<String> res = post("/api/sensor-events", "good", body);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(CORE_GOT.poll(5, TimeUnit.SECONDS)).isEqualTo(body);
        assertThat(CLIMATE_GOT.poll(5, TimeUnit.SECONDS)).isEqualTo("/internal/sensor-events " + body);
    }

    @Test
    @DisplayName("core가 거절한(장치 토큰이 틀린) 값은 climate에 보내지 않는다")
    void rejectedEventIsNotMirrored() throws Exception {
        HttpResponse<String> res = post("/api/sensor-events", "bad", "{\"eventId\":\"e2\"}");

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(CLIMATE_GOT.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("16KiB보다 큰 본문은 core로만 가고 사본은 만들지 않는다")
    void bigBodyIsNotMirrored() throws Exception {
        String big = "{\"eventId\":\"e3\",\"pad\":\"" + "x".repeat(20 * 1024) + "\"}";
        assertThat(post("/api/sensor-events", "good", big).statusCode()).isEqualTo(200);
        assertThat(CORE_GOT.poll(5, TimeUnit.SECONDS)).isEqualTo(big);
        assertThat(CLIMATE_GOT.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("센서 말고 다른 장치 길(정리 확인)은 사본을 만들지 않는다")
    void otherDevicePathIsNotMirrored() throws Exception {
        assertThat(post("/api/rooms/2/tidy-checks", "good", "{}").statusCode()).isEqualTo(200);
        assertThat(CLIMATE_GOT.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("/api/climate/**는 core가 아니라 climate로 가고, 토큰이 없으면 401")
    void climateRoute() throws Exception {
        HttpResponse<String> noToken = http.send(HttpRequest.newBuilder(uri("/api/climate/rooms")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(noToken.statusCode()).isEqualTo(401);

        String token = GatewayRoutingAndAuthTest.token(SECRET, "mspace-core",
                java.time.Instant.now().plusSeconds(600));
        HttpResponse<String> ok = http.send(HttpRequest.newBuilder(uri("/api/climate/rooms"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(CLIMATE_GOT.poll(5, TimeUnit.SECONDS)).startsWith("/api/climate/rooms");
    }

    private HttpResponse<String> post(String path, String deviceToken, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + deviceToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static HttpServer server(boolean core) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int status = 200;
                if (core) {
                    String auth = ex.getRequestHeaders().getFirst("Authorization");
                    status = "Bearer good".equals(auth) ? 200 : 401;
                    if (ex.getRequestURI().getPath().equals("/api/sensor-events")) {
                        CORE_GOT.add(body);
                    }
                } else {
                    CLIMATE_GOT.add(ex.getRequestURI().getPath() + (body.isEmpty() ? "" : " " + body));
                }
                ex.sendResponseHeaders(status, -1);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
