package io.career262.fleet.mqtt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.career262.fleet.TelemetryController.Telemetry;
import io.career262.fleet.TelemetryService;
import jakarta.validation.Validator;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mqtt")
public class MqttTelemetryHandler {
    public record Disposition(String outcome, String eventId) {}
    private final TelemetryService telemetry;
    private final MqttRejectionStore rejections;
    private final Validator validator;
    private final ObjectReader reader;
    private final MqttSettings settings;

    public MqttTelemetryHandler(TelemetryService telemetry, MqttRejectionStore rejections,
                                Validator validator, ObjectMapper mapper, MqttSettings settings) {
        this.telemetry = telemetry;
        this.rejections = rejections;
        this.validator = validator;
        this.settings = settings;
        this.reader = mapper.readerFor(Telemetry.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** Returns only after the telemetry transaction OR rejection transaction committed. */
    public Disposition handle(String topic, byte[] payload, int qos, boolean retained) {
        if (payload.length > settings.maxPayloadBytes()) return reject(topic,payload,qos,retained,"oversized",null);
        if (retained) return reject(topic,payload,qos,true,"retained",null);
        if (qos != 1) return reject(topic,payload,qos,false,"qos",null);
        Telemetry input;
        try {
            input = reader.readValue(payload);
        } catch (IOException exception) {
            return reject(topic,payload,qos,false,"invalid_json",null);
        }
        if (input == null) return reject(topic,payload,qos,false,"invalid_json",null);
        if (input.eventId() == null || input.eventId().isBlank()) {
            return reject(topic,payload,qos,false,"missing_event_id",input.eventId());
        }
        // The very same bean constraints used by HTTP @Valid, including nested errorCodes.
        if (!validator.validate(input).isEmpty()) {
            return reject(topic,payload,qos,false,"validation",input.eventId());
        }
        if (!topic.equals("robots/" + input.robotId() + "/telemetry")) {
            return reject(topic,payload,qos,false,"topic_robot_mismatch",input.eventId());
        }
        try {
            var response = telemetry.ingest(input);
            return new Disposition(response.duplicate() ? "duplicate" : "accepted", response.eventId());
        } catch (ResponseStatusException exception) {
            if (!exception.getStatusCode().is4xxClientError()) throw exception;
            // Ingest's proxy has rolled back before this separate rejection transaction starts.
            return reject(topic,payload,qos,false,
                    exception.getStatusCode().value() == 409 ? "conflict" : "invalid_telemetry", input.eventId());
        }
        // DB/network/unexpected failures escape: no disposition, no ACK, session redelivery.
    }

    private Disposition reject(String topic, byte[] body, int qos, boolean retained, String reason, String eventId) {
        rejections.record(topic, body, qos, retained, reason, eventId);
        return new Disposition("rejected", eventId);
    }
}
