package io.career262.fleet.mqtt;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("fleet.mqtt")
@Validated
public record MqttSettings(
        @NotBlank String brokerUri,
        @NotBlank @Size(max = 100) String clientId,
        @Min(60) @Max(604800) long sessionExpirySeconds,
        @Min(1024) @Max(32768) int maxPayloadBytes) {}
