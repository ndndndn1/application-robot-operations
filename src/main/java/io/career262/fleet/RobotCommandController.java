package io.career262.fleet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/v1/commands")
@Validated
public class RobotCommandController {
    public record CommandRequest(
            @JsonProperty("contract_version")
            @NotBlank @Pattern(regexp = "1\\.0\\.0") String contractVersion,
            @JsonProperty("command_id")
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    String commandId,
            @JsonProperty("robot_id")
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*")
                    String robotId,
            @JsonProperty("issued_at") @NotNull Instant issuedAt,
            @JsonProperty("expires_at") @NotNull Instant expiresAt,
            @JsonProperty("expected_state_version") @NotNull @PositiveOrZero Long expectedStateVersion,
            @NotNull JsonNode action) {}

    public record CommandResult(
            @JsonProperty("command_id") String commandId,
            @JsonProperty("target_mode") String targetMode,
            boolean duplicate,
            JsonNode record) {}

    private final RobotCommandService service;

    RobotCommandController(RobotCommandService service) {
        this.service = service;
    }

    @PostMapping
    CommandResult submit(@Valid @RequestBody CommandRequest request) {
        return service.submit(request);
    }

    @GetMapping("/{commandId}")
    CommandResult status(@PathVariable
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
            String commandId) {
        return service.status(commandId);
    }

    @PostMapping("/{commandId}/cancel")
    CommandResult cancel(@PathVariable
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
            String commandId) {
        return service.cancel(commandId);
    }
}
