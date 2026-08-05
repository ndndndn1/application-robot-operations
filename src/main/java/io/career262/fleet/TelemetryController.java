package io.career262.fleet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class TelemetryController {
    public record Telemetry(@NotBlank String robotId, double timestamp, double x, double y,
                            @DecimalMin("0") @DecimalMax("100") double battery,
                            @NotBlank String taskState, List<String> errorCodes,
                            @NotBlank String modelId) {}
    public record Response(Telemetry telemetry, String severity, List<String> rules,
                           Double deltaMeters, Double deltaBattery) {}

    private static final Set<String> TASK_STATES = Set.of("idle", "moving", "docking", "charging", "error");
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RuleEngine engine = new RuleEngine();
    private final Counter criticalCounter;

    public TelemetryController(StringRedisTemplate redis, JdbcTemplate jdbc, ObjectMapper mapper, MeterRegistry registry) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.criticalCounter = registry.counter("fleet_telemetry_critical_total");
    }

    @PostMapping("/telemetry")
    public Response ingest(@Valid @RequestBody Telemetry input) throws JsonProcessingException {
        if (!TASK_STATES.contains(input.taskState())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown taskState");
        }
        String snapshotKey = "fleet:last:" + input.robotId();
        String raw = redis.opsForValue().get(snapshotKey);
        Telemetry previous = raw == null ? null : mapper.readValue(raw, Telemetry.class);
        RuleEngine.Profile profile = profileFor(input.modelId());
        RuleEngine.Packet packet = toPacket(input);
        RuleEngine.Result result = engine.evaluate(packet, previous == null ? null : toPacket(previous), profile);
        Response response = new Response(input, result.severity(), result.rules(), result.deltaMeters(), result.deltaBattery());
        String encoded = mapper.writeValueAsString(input);
        redis.opsForValue().set(snapshotKey, encoded);
        String ringKey = "fleet:ring:" + input.robotId();
        redis.opsForList().leftPush(ringKey, mapper.writeValueAsString(response));
        redis.opsForList().trim(ringKey, 0, 49);
        jdbc.update("insert into telemetry_event(robot_id,model_id,observed_at,x,y,battery,severity,rules) values (?,?,to_timestamp(?),?,?,?,?,?)",
                input.robotId(), input.modelId(), input.timestamp(), input.x(), input.y(), input.battery(), result.severity(), String.join(",", result.rules()));
        if (result.severity().equals("critical")) criticalCounter.increment();
        return response;
    }

    @GetMapping("/robots/{robotId}/recent")
    public Map<String, Object> recent(@PathVariable String robotId, @RequestParam(defaultValue = "20") int limit) {
        int bounded = Math.max(1, Math.min(limit, 50));
        List<String> rows = redis.opsForList().range("fleet:ring:" + robotId, 0, bounded - 1);
        return Map.of("robotId", robotId, "items", rows == null ? List.of() : rows);
    }

    private static RuleEngine.Packet toPacket(Telemetry value) {
        return new RuleEngine.Packet(value.robotId(), value.timestamp(), value.x(), value.y(), value.battery(),
                value.taskState(), value.errorCodes() == null ? List.of() : value.errorCodes(), value.modelId());
    }

    private static RuleEngine.Profile profileFor(String modelId) {
        return switch (modelId) {
            case "heavy" -> new RuleEngine.Profile(30, 12, 3.0);
            case "compact" -> new RuleEngine.Profile(20, 8, 4.0);
            default -> new RuleEngine.Profile(25, 10, 5.0);
        };
    }
}
