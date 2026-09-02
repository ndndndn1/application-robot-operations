package io.career262.fleet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
@Validated
public class PerceptionOperationsController {
    private static final String ID = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
    private static final String SHA256 = "[a-f0-9]{64}";

    public record CalibrationRequest(
            @JsonProperty("schema_version") @NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
            @JsonProperty("calibration_id") @NotBlank @Pattern(regexp = ID) String calibrationId,
            @JsonProperty("robot_id") @NotBlank @Pattern(regexp = ID) String robotId,
            @JsonProperty("product_id") @NotBlank @Pattern(regexp = ID) String productId,
            @JsonProperty("sensor_rig_id") @NotBlank @Pattern(regexp = ID) String sensorRigId,
            @JsonProperty("parent_frame") @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_/]{0,127}")
                    String parentFrame,
            @JsonProperty("child_frame") @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_/]{0,127}")
                    String childFrame,
            @NotNull JsonNode intrinsics,
            @NotNull JsonNode extrinsics,
            @JsonProperty("source_artifact_sha256") @NotBlank @Pattern(regexp = SHA256)
                    String sourceArtifactSha256,
            @NotBlank @Size(max = 64) String method,
            @JsonProperty("software_version") @NotBlank @Size(max = 64) String softwareVersion,
            @JsonProperty("observed_at") @NotNull Instant observedAt) {}

    public record PolicyRequest(
            @JsonProperty("schema_version") @NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
            @JsonProperty("policy_id") @NotBlank @Pattern(regexp = ID) String policyId,
            @NotBlank @Pattern(regexp = "bc_rnn") String algorithm,
            @JsonProperty("target_product_id") @NotBlank @Pattern(regexp = ID) String targetProductId,
            @JsonProperty("capability_profile_digest") @NotBlank
                    @Pattern(regexp = "sha256:[a-f0-9]{64}") String capabilityProfileDigest,
            @JsonProperty("model_sha256") @NotBlank @Pattern(regexp = SHA256) String modelSha256,
            @JsonProperty("dataset_sha256") @NotBlank @Pattern(regexp = SHA256) String datasetSha256,
            @JsonProperty("input_schema_sha256") @NotBlank @Pattern(regexp = SHA256) String inputSchemaSha256,
            @JsonProperty("output_schema_sha256") @NotBlank @Pattern(regexp = SHA256) String outputSchemaSha256,
            @JsonProperty("success_rate") @DecimalMin("0") @DecimalMax("1") double successRate,
            @JsonProperty("stress_success_rate") @DecimalMin("0") @DecimalMax("1")
                    double stressSuccessRate) {}

    public record PerceptionResultRequest(
            @JsonProperty("schema_version") @NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
            @JsonProperty("result_id") @NotBlank @Pattern(regexp = ID) String resultId,
            @JsonProperty("robot_id") @NotBlank @Pattern(regexp = ID) String robotId,
            @JsonProperty("scene_sequence") @PositiveOrZero long sceneSequence,
            @JsonProperty("calibration_id") @NotBlank @Pattern(regexp = ID) String calibrationId,
            @JsonProperty("calibration_sha256") @NotBlank @Pattern(regexp = SHA256)
                    String calibrationSha256,
            @JsonProperty("policy_id") @NotBlank @Pattern(regexp = ID) String policyId,
            @JsonProperty("policy_sha256") @NotBlank @Pattern(regexp = SHA256) String policySha256,
            @JsonProperty("input_artifact_sha256") @NotBlank @Pattern(regexp = SHA256)
                    String inputArtifactSha256,
            @NotNull JsonNode pose,
            @NotNull JsonNode grasps,
            @JsonProperty("produced_at") @NotNull Instant producedAt) {}

    public record ExecutionIntentRequest(
            @JsonProperty("schema_version") @NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
            @JsonProperty("intent_id") @NotBlank @Pattern(regexp = ID) String intentId,
            @JsonProperty("result_id") @NotBlank @Pattern(regexp = ID) String resultId,
            @JsonProperty("grasp_id") @NotBlank @Pattern(regexp = ID) String graspId,
            @JsonProperty("robot_id") @NotBlank @Pattern(regexp = ID) String robotId,
            @JsonProperty("expected_state_version") @PositiveOrZero long expectedStateVersion,
            @NotNull @Future @JsonProperty("expires_at") Instant expiresAt,
            @NotBlank @Size(max = 128) String actor) {}

    public record ApprovalRequest(@NotBlank @Size(max = 128) String actor) {}

    private final PerceptionOperationsService service;

    PerceptionOperationsController(PerceptionOperationsService service) {
        this.service = service;
    }

    @PostMapping("/calibrations")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createCalibration(@Valid @RequestBody CalibrationRequest request) {
        return service.createCalibration(request);
    }

    @PostMapping("/calibrations/{id}/approve")
    Map<String, Object> approveCalibration(@PathVariable @Pattern(regexp = ID) String id,
                                           @Valid @RequestBody ApprovalRequest request) {
        return service.approveCalibration(id, request.actor());
    }

    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createPolicy(@Valid @RequestBody PolicyRequest request) {
        return service.createPolicy(request);
    }

    @PostMapping("/policies/{id}/promote")
    Map<String, Object> promotePolicy(@PathVariable @Pattern(regexp = ID) String id,
                                      @Valid @RequestBody ApprovalRequest request) {
        return service.promotePolicy(id, request.actor());
    }

    @PostMapping("/policies/{id}/rollback")
    Map<String, Object> rollbackPolicy(@PathVariable @Pattern(regexp = ID) String id,
                                       @Valid @RequestBody ApprovalRequest request) {
        return service.rollbackPolicy(id, request.actor());
    }

    @PostMapping("/perception-results")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> recordPerception(@Valid @RequestBody PerceptionResultRequest request) {
        return service.recordPerception(request);
    }

    @PostMapping("/execution-intents")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createIntent(@Valid @RequestBody ExecutionIntentRequest request) {
        return service.createIntent(request);
    }

    @PostMapping("/execution-intents/{id}/approve")
    Map<String, Object> approveIntent(@PathVariable @Pattern(regexp = ID) String id,
                                      @Valid @RequestBody ApprovalRequest request) {
        return service.approveIntent(id, request.actor());
    }

    @GetMapping("/execution-intents/{id}")
    Map<String, Object> intent(@PathVariable @Pattern(regexp = ID) String id) {
        return service.intent(id);
    }
}
