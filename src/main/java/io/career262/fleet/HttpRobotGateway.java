package io.career262.fleet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
final class HttpRobotGateway implements RobotGateway {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private final URI baseUri;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String targetMode;
    private final boolean allowReal;
    private final String identity;

    HttpRobotGateway(
            @Value("${robot.gateway-url}") String gatewayUrl,
            @Value("${robot.target-mode:mock}") String targetMode,
            @Value("${robot.allow-real:false}") boolean allowReal,
            ObjectMapper mapper) {
        this.baseUri = URI.create(gatewayUrl.endsWith("/") ? gatewayUrl : gatewayUrl + "/");
        this.targetMode = targetMode;
        this.allowReal = allowReal;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.identity = digest(this.baseUri.normalize().toString());
        if (!targetMode.equals("mock") && !targetMode.equals("real")) {
            throw new IllegalArgumentException("robot.target-mode must be mock or real");
        }
    }

    @Override
    public String identity() {
        return identity;
    }

    @Override
    public JsonNode submit(JsonNode request) {
        requireAuthorizedTarget();
        return exchange("commands", "POST", request);
    }

    @Override
    public JsonNode status(String commandId) {
        return exchange("commands/" + commandId, "GET", null);
    }

    @Override
    public JsonNode cancel(String commandId) {
        requireAuthorizedTarget();
        return exchange("commands/" + commandId + "/cancel", "POST", mapper.createObjectNode());
    }

    private void requireAuthorizedTarget() {
        if (targetMode.equals("real") && !allowReal) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "real robot commands require ROBOT_ALLOW_REAL=true after hardware safety approval");
        }
    }

    private JsonNode exchange(String path, String method, JsonNode body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve("v1/" + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("accept", "application/json")
                    .header("x-robot-target-mode", targetMode);
            if (body == null) {
                builder.GET();
            } else {
                builder.header("content-type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                HttpStatus status = HttpStatus.resolve(response.statusCode());
                throw new ResponseStatusException(status == null ? HttpStatus.BAD_GATEWAY : status,
                        "robot gateway rejected request: " + bounded(response.body()));
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "robot gateway request interrupted", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "robot gateway unavailable", exception);
        }
    }

    private static String bounded(String value) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), 512));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
