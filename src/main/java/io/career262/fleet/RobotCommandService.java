package io.career262.fleet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.career262.fleet.RobotCommandController.CommandRequest;
import io.career262.fleet.RobotCommandController.CommandResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RobotCommandService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RobotGateway gateway;
    private final String targetMode;
    private final Counter submittedCounter;
    private final Counter duplicateCounter;

    RobotCommandService(JdbcTemplate jdbc, ObjectMapper mapper, RobotGateway gateway,
                        @Value("${robot.target-mode:mock}") String targetMode,
                        MeterRegistry registry) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.gateway = gateway;
        this.targetMode = targetMode;
        this.submittedCounter = registry.counter("robot_command_total", "outcome", "submitted");
        this.duplicateCounter = registry.counter("robot_command_total", "outcome", "duplicate");
    }

    @Transactional
    public CommandResult submit(CommandRequest input) {
        Instant now = Instant.now();
        if (input.expiresAt().isBefore(now) || !input.expiresAt().isAfter(input.issuedAt())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "expires_at must be in the future and later than issued_at");
        }
        if (input.issuedAt().isAfter(now.plusSeconds(300))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "issued_at must not be more than five minutes in the future");
        }
        validateAction(input.action());
        String commandId = input.commandId() == null
                ? UUID.randomUUID().toString() : input.commandId();
        JsonNode request = canonicalize(mapper.valueToTree(new CommandRequest(input.contractVersion(), commandId,
                input.robotId(), input.issuedAt(), input.expiresAt(), input.expectedStateVersion(),
                input.action())), mapper);
        String hash = fingerprint(request);
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, commandId);
        StoredCommand existing = find(commandId);
        if (existing != null) {
            if (!existing.requestHash().equals(hash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "command_id already exists with a different request");
            }
            duplicateCounter.increment();
            return new CommandResult(commandId, existing.targetMode(), true,
                    readTree(existing.responseJson()));
        }
        JsonNode response = gateway.submit(request);
        jdbc.update("insert into robot_command(command_id,request_hash,robot_id,target_mode,status,"
                        + "gateway_identity,request_json,response_json) "
                        + "values (?,?,?,?,?,?,?::jsonb,?::jsonb)",
                commandId, hash, input.robotId(), targetMode, statusOf(response), gateway.identity(),
                write(request), write(response));
        submittedCounter.increment();
        return new CommandResult(commandId, targetMode, false, response);
    }

    public CommandResult status(String commandId) {
        StoredCommand stored = find(commandId);
        if (stored == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "command not found");
        }
        requireOriginalTarget(stored);
        JsonNode remote = gateway.status(commandId);
        return new CommandResult(commandId, stored.targetMode(), false, remote);
    }

    @Transactional
    public CommandResult cancel(String commandId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, commandId);
        StoredCommand stored = find(commandId);
        if (stored == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "command not found");
        }
        requireOriginalTarget(stored);
        JsonNode response = gateway.cancel(commandId);
        jdbc.update("update robot_command set status=?,response_json=?::jsonb,updated_at=now() "
                        + "where command_id=?", statusOf(response), write(response), commandId);
        return new CommandResult(commandId, stored.targetMode(), false, response);
    }

    private StoredCommand find(String commandId) {
        return jdbc.query("select request_hash,target_mode,gateway_identity,response_json::text "
                        + "from robot_command "
                        + "where command_id=?",
                rs -> rs.next() ? new StoredCommand(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4))
                        : null, commandId);
    }

    static void validateAction(JsonNode action) {
        if (!action.isObject() || !action.hasNonNull("type")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "action must be an object containing type");
        }
        String type = action.path("type").asText();
        if (!type.equals("navigate") && !type.equals("manipulate")
                && !type.equals("protective_stop")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "action type must be navigate, manipulate, or protective_stop");
        }
        switch (type) {
            case "navigate" -> validateNavigate(action);
            case "manipulate" -> validateManipulate(action);
            case "protective_stop" -> validateProtectiveStop(action);
            default -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "unsupported action type");
        }
    }

    private static void validateNavigate(JsonNode action) {
        requireOnly(action, Set.of("type", "target", "max_speed_mps"), "navigate action");
        JsonNode target = action.get("target");
        if (target == null || !target.isObject()) {
            reject("navigate target must be an object");
        }
        requireOnly(target, Set.of("x_m", "y_m", "yaw_rad", "frame"), "navigate target");
        requireFinite(target, "x_m", -10_000, 10_000);
        requireFinite(target, "y_m", -10_000, 10_000);
        requireFinite(target, "yaw_rad", -3.141593, 3.141593);
        if (target.has("frame") && (!target.get("frame").isTextual()
                || !target.get("frame").asText().matches("[A-Za-z][A-Za-z0-9_/]{0,63}"))) {
            reject("navigate frame is invalid");
        }
        if (action.has("max_speed_mps")) {
            requireFinite(action, "max_speed_mps", Math.nextUp(0.0), 2.0);
        }
    }

    private static void validateManipulate(JsonNode action) {
        requireOnly(action, Set.of("type", "joint_positions_rad", "max_force_n"),
                "manipulate action");
        JsonNode joints = action.get("joint_positions_rad");
        if (joints == null || !joints.isArray() || joints.isEmpty() || joints.size() > 16) {
            reject("joint_positions_rad must contain 1 through 16 numeric values");
        }
        for (JsonNode joint : joints) {
            if (!joint.isNumber() || !Double.isFinite(joint.asDouble())) {
                reject("joint_positions_rad must contain only finite numbers");
            }
        }
        if (action.has("max_force_n")) {
            requireFinite(action, "max_force_n", Math.nextUp(0.0), 250.0);
        }
    }

    private static void validateProtectiveStop(JsonNode action) {
        requireOnly(action, Set.of("type", "reason"), "protective_stop action");
        JsonNode reason = action.get("reason");
        if (reason == null || !reason.isTextual() || reason.asText().isBlank()
                || reason.asText().length() > 256) {
            reject("protective_stop reason must contain 1 through 256 characters");
        }
    }

    private static void requireOnly(JsonNode object, Set<String> allowed, String label) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        names.removeAll(allowed);
        if (!names.isEmpty()) {
            reject(label + " contains unknown fields: " + names.stream().sorted().toList());
        }
    }

    private static void requireFinite(JsonNode object, String field, double minimum, double maximum) {
        JsonNode value = object.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())
                || value.asDouble() < minimum || value.asDouble() > maximum) {
            reject(field + " is missing, non-finite, or outside its allowed range");
        }
    }

    private static void reject(String detail) {
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, detail);
    }

    private void requireOriginalTarget(StoredCommand stored) {
        if (!stored.targetMode().equals(targetMode) || !stored.gatewayIdentity().equals(gateway.identity())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "command belongs to a different configured robot target");
        }
    }

    String fingerprint(JsonNode request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(write(request).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static JsonNode canonicalize(JsonNode value, ObjectMapper mapper) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            ArrayList<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name,
                    canonicalize(value.get(name), mapper)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonicalize(item, mapper)));
            return result;
        }
        return value.deepCopy();
    }

    private String statusOf(JsonNode response) {
        String status = response.path("status").asText("unknown");
        return status.substring(0, Math.min(status.length(), 32));
    }

    private String write(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JsonNode readTree(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record StoredCommand(String requestHash, String targetMode, String gatewayIdentity,
                                 String responseJson) {}
}
