package io.career262.fleet.mqtt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A bounded-size audit record, never raw untrusted payloads or an in-memory dead-letter queue. */
@Service
@Profile("mqtt")
public class MqttRejectionStore {
    private final JdbcTemplate jdbc;
    public MqttRejectionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void record(String topic, byte[] payload, int qos, boolean retained, String reason, String eventId) {
        // Invalid JSON strings (including escaped NUL/unpaired surrogates) must never poison
        // PostgreSQL's text encoder. Keep only valid UUID metadata; the raw-body hash is sufficient
        // to locate every rejected source record without trusting its eventId.
        String safeEventId = eventId != null && eventId.matches(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}") ? eventId : null;
        StringBuilder safeTopic = new StringBuilder();
        topic.codePoints().limit(256).forEach(point -> safeTopic.appendCodePoint(
                point == 0 || (point >= 0xD800 && point <= 0xDFFF) ? 0xFFFD : point));
        String payloadHash = sha256(payload);
        String key = sha256((topic + "\n" + payloadHash + "\n" + qos + "\n" + retained + "\n" + reason)
                .getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                insert into mqtt_rejection(rejection_key,topic,payload_sha256,payload_bytes,qos,retained,reason,event_id)
                values (?,?,?,?,?,?,?,?)
                on conflict(rejection_key) do update set deliveries=mqtt_rejection.deliveries+1,last_seen=clock_timestamp()
                """, key, safeTopic.toString(), payloadHash, payload.length,
                qos, retained, reason, safeEventId);
    }

    static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
