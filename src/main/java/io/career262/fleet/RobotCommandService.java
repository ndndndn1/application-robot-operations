package io.career262.fleet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.career262.fleet.RobotCommandController.CommandRequest;
import io.career262.fleet.RobotCommandController.CommandResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
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
                ? UUID.randomUUID().toString() : input.commandId().toLowerCase();
        JsonNode request = mapper.valueToTree(new CommandRequest(input.contractVersion(), commandId,
                input.robotId(), input.issuedAt(), input.expiresAt(), input.expectedStateVersion(),
                input.action()));
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
                        + "request_json,response_json) values (?::uuid,?,?,?,?,?::jsonb,?::jsonb)",
                commandId, hash, input.robotId(), targetMode, statusOf(response),
                write(request), write(response));
        submittedCounter.increment();
        return new CommandResult(commandId, targetMode, false, response);
    }

    public CommandResult status(String commandId) {
        StoredCommand stored = find(commandId.toLowerCase());
        if (stored == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "command not found");
        }
        JsonNode remote = gateway.status(commandId.toLowerCase());
        return new CommandResult(commandId.toLowerCase(), stored.targetMode(), false, remote);
    }

    @Transactional
    public CommandResult cancel(String commandId) {
        String normalized = commandId.toLowerCase();
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, normalized);
        StoredCommand stored = find(normalized);
        if (stored == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "command not found");
        }
        JsonNode response = gateway.cancel(normalized);
        jdbc.update("update robot_command set status=?,response_json=?::jsonb,updated_at=now() "
                        + "where command_id=?::uuid", statusOf(response), write(response), normalized);
        return new CommandResult(normalized, stored.targetMode(), false, response);
    }

    private StoredCommand find(String commandId) {
        return jdbc.query("select request_hash,target_mode,response_json::text from robot_command "
                        + "where command_id=?::uuid",
                rs -> rs.next() ? new StoredCommand(rs.getString(1), rs.getString(2), rs.getString(3))
                        : null, commandId);
    }

    private static void validateAction(JsonNode action) {
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
        if (action.size() > 8) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "action contains too many fields");
        }
    }

    private String fingerprint(JsonNode request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(write(request).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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

    private record StoredCommand(String requestHash, String targetMode, String responseJson) {}
}
