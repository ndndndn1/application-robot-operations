package io.career262.fleet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.career262.fleet.TelemetryController.Response;
import io.career262.fleet.TelemetryController.Telemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.redis.core.StringRedisTemplate;

@Service
public class TelemetryService {
    static final Duration CACHE_TTL = Duration.ofHours(24);
    static final int RING_SIZE = 50;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RuleEngine engine = new RuleEngine();
    private final Counter criticalCounter;
    private final Counter cacheFailureCounter;
    private final Counter duplicateCounter;

    public TelemetryService(StringRedisTemplate redis, JdbcTemplate jdbc, ObjectMapper mapper,
                            MeterRegistry registry) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.mapper = mapper;
        criticalCounter = registry.counter("fleet_telemetry_ingest_total", "outcome", "critical");
        cacheFailureCounter = registry.counter("fleet_telemetry_cache_failures_total");
        duplicateCounter = registry.counter("fleet_telemetry_ingest_total", "outcome", "duplicate");
    }

    @Transactional
    public Response ingest(Telemetry input) {
        requireFinite(input);
        if (input.timestamp() > Instant.now().plusSeconds(300).toEpochMilli() / 1000.0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "timestamp must not be more than 5 minutes in the future");
        }
        String eventId = input.eventId() == null ? UUID.randomUUID().toString() : input.eventId().toLowerCase();
        String fingerprint = fingerprint(input);

        // Serialize each robot's read/evaluate/write path across application replicas.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0)) is null",
                Boolean.class, input.robotId());
        StoredEvent existing = findByEventId(eventId);
        if (existing != null) {
            if (!existing.payloadHash().equals(fingerprint)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "eventId already exists with a different telemetry payload");
            }
            duplicateCounter.increment();
            return decode(existing.responseJson(), true);
        }

        Telemetry previous = jdbc.query(
                "select payload::text from telemetry_event where robot_id=? "
                        + "order by observed_at desc, id desc limit 1",
                rs -> rs.next() ? read(rs.getString(1), Telemetry.class) : null,
                input.robotId());
        RuleEngine.Result result = engine.evaluate(toPacket(input),
                previous == null ? null : toPacket(previous), profileFor(input.modelId()));
        Response response = new Response(eventId, input, result.severity(), result.rules(),
                result.deltaMeters(), result.deltaBattery(), false);
        String responseJson = write(response);
        String payloadJson = write(input);
        jdbc.update("insert into telemetry_event(event_id,payload_hash,robot_id,model_id,observed_at,x,y,"
                        + "battery,severity,rules,payload,response_json) "
                        + "values (?::uuid,?,?,?,to_timestamp(?),?,?,?,?,?::text[],?::jsonb,?::jsonb)",
                eventId, fingerprint, input.robotId(), input.modelId(), input.timestamp(), input.x(), input.y(),
                input.battery(), result.severity(), result.rules().toArray(String[]::new), payloadJson, responseJson);

        boolean advancesSnapshot = previous == null || input.timestamp() >= previous.timestamp();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                publishCache(input.robotId(), payloadJson, responseJson, advancesSnapshot);
                if ("critical".equals(result.severity())) criticalCounter.increment();
            }
        });
        return response;
    }

    public List<Response> recent(String robotId, int limit) {
        try {
            List<String> cached = redis.opsForList().range("fleet:ring:" + robotId, 0, limit - 1);
            if (cached != null && !cached.isEmpty()) {
                return cached.stream().map(value -> decode(value, false)).toList();
            }
        } catch (RuntimeException exception) {
            cacheFailureCounter.increment();
        }
        return jdbc.query("select response_json::text from telemetry_event where robot_id=? "
                        + "order by observed_at desc, id desc limit ?",
                (rs, row) -> decode(rs.getString(1), false), robotId, limit);
    }

    private void publishCache(String robotId, String payload, String response, boolean advancesSnapshot) {
        try {
            if (advancesSnapshot) {
                redis.opsForValue().set("fleet:last:" + robotId, payload, CACHE_TTL);
            }
            String ring = "fleet:ring:" + robotId;
            redis.opsForList().leftPush(ring, response);
            redis.opsForList().trim(ring, 0, RING_SIZE - 1);
            redis.expire(ring, CACHE_TTL);
        } catch (RuntimeException exception) {
            cacheFailureCounter.increment();
        }
    }

    private StoredEvent findByEventId(String eventId) {
        return jdbc.query("select payload_hash,response_json::text from telemetry_event where event_id=?::uuid",
                rs -> rs.next() ? new StoredEvent(rs.getString(1), rs.getString(2)) : null, eventId);
    }

    static RuleEngine.Profile profileFor(String modelId) {
        return switch (modelId) {
            case "heavy" -> new RuleEngine.Profile(30, 12, 3.0);
            case "compact" -> new RuleEngine.Profile(20, 8, 4.0);
            case "standard" -> new RuleEngine.Profile(25, 10, 5.0);
            default -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "unsupported modelId");
        };
    }

    private static void requireFinite(Telemetry input) {
        if (!Double.isFinite(input.timestamp()) || !Double.isFinite(input.x())
                || !Double.isFinite(input.y()) || !Double.isFinite(input.battery())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "numeric telemetry fields must be finite");
        }
    }

    private String fingerprint(Telemetry input) {
        try {
            Telemetry withoutEventId = new Telemetry(input.robotId(), input.timestamp(), input.x(), input.y(),
                    input.battery(), input.taskState(), input.errorCodes(), input.modelId(), null);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(write(withoutEventId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static RuleEngine.Packet toPacket(Telemetry value) {
        return new RuleEngine.Packet(value.robotId(), value.timestamp(), value.x(), value.y(), value.battery(),
                value.taskState(), value.errorCodes(), value.modelId());
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private Response decode(String value, boolean duplicate) {
        Response stored = read(value, Response.class);
        return new Response(stored.eventId(), stored.telemetry(), stored.severity(), stored.rules(),
                stored.deltaMeters(), stored.deltaBattery(), duplicate);
    }

    private record StoredEvent(String payloadHash, String responseJson) {}
}
