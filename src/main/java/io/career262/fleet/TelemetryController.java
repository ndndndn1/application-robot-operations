package io.career262.fleet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api")
@Validated
public class TelemetryController {
    public record Telemetry(
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*") String robotId,
            @NotNull @DecimalMin(value = "0", inclusive = false) Double timestamp,
            @NotNull @DecimalMin("-1000000") @DecimalMax("1000000") Double x,
            @NotNull @DecimalMin("-1000000") @DecimalMax("1000000") Double y,
            @NotNull @DecimalMin("0") @DecimalMax("100") Double battery,
            @NotBlank @Pattern(regexp = "idle|moving|docking|charging|error") String taskState,
            @NotNull @Size(max = 32) List<@NotBlank @Size(max = 80)
                    @Pattern(regexp = "[A-Z][A-Z0-9_:-]*") String> errorCodes,
            @NotBlank @Pattern(regexp = "standard|compact|heavy") String modelId,
            @Pattern(regexp = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                    String eventId) {}

    public record Response(String eventId, Telemetry telemetry, String severity, List<String> rules,
                           Double deltaMeters, Double deltaBattery, boolean duplicate) {}

    private final TelemetryService service;

    public TelemetryController(TelemetryService service) {
        this.service = service;
    }

    @PostMapping("/telemetry")
    public Response ingest(@Valid @RequestBody Telemetry input) {
        return service.ingest(input);
    }

    @GetMapping("/robots/{robotId}/recent")
    public Map<String, Object> recent(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,79}") String robotId,
            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 50");
        }
        return Map.of("robotId", robotId, "items", service.recent(robotId, limit));
    }
}
