package io.career262.fleet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.career262.fleet.PerceptionOperationsController.CalibrationRequest;
import io.career262.fleet.PerceptionOperationsController.ExecutionIntentRequest;
import io.career262.fleet.PerceptionOperationsController.PerceptionResultRequest;
import io.career262.fleet.PerceptionOperationsController.PolicyRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PerceptionOperationsService {
    private static final double MIN_SUCCESS = 0.80;
    private static final double MIN_STRESS_SUCCESS = 0.70;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RobotGateway gateway;

    PerceptionOperationsService(JdbcTemplate jdbc, ObjectMapper mapper, RobotGateway gateway) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.gateway = gateway;
    }

    @Transactional
    public Map<String, Object> createCalibration(CalibrationRequest request) {
        validateCalibration(request);
        JsonNode canonical = canonical(request);
        String digest = digest(canonical);
        lock(request.calibrationId());
        Stored existing = stored("calibration_bundle", "calibration_id", request.calibrationId());
        if (existing != null) {
            requireSameDigest(existing, digest, "calibration_id");
            return response(request.calibrationId(), digest, existing.state());
        }
        jdbc.update("insert into calibration_bundle(calibration_id,digest,robot_id,product_id,"
                        + "sensor_rig_id,state,bundle_json) values (?,?,?,?,?,'draft',?::jsonb)",
                request.calibrationId(), digest, request.robotId(), request.productId(),
                request.sensorRigId(), write(canonical));
        return response(request.calibrationId(), digest, "draft");
    }

    @Transactional
    public Map<String, Object> approveCalibration(String id, String actor) {
        lock(id);
        int changed = jdbc.update("update calibration_bundle set state='approved',approved_by=?,"
                + "approved_at=now() where calibration_id=? and state in ('draft','approved')",
                actor, id);
        if (changed == 0) {
            throw notFoundOrConflict("calibration", id);
        }
        Stored stored = stored("calibration_bundle", "calibration_id", id);
        return response(id, stored.digest(), "approved");
    }

    @Transactional
    public Map<String, Object> createPolicy(PolicyRequest request) {
        JsonNode canonical = canonical(request);
        String digest = digest(canonical);
        lock(request.policyId());
        Stored existing = stored("policy_release", "policy_id", request.policyId());
        if (existing != null) {
            requireSameDigest(existing, digest, "policy_id");
            return response(request.policyId(), digest, existing.state());
        }
        jdbc.update("insert into policy_release(policy_id,digest,algorithm,target_product_id,"
                        + "capability_profile_digest,model_sha256,dataset_sha256,success_rate,"
                        + "stress_success_rate,state,release_json) "
                        + "values (?,?,?,?,?,?,?,?,?,'evaluated',?::jsonb)",
                request.policyId(), digest, request.algorithm(), request.targetProductId(),
                request.capabilityProfileDigest(), request.modelSha256(), request.datasetSha256(),
                request.successRate(), request.stressSuccessRate(), write(canonical));
        return response(request.policyId(), digest, "evaluated");
    }

    @Transactional
    public Map<String, Object> promotePolicy(String id, String actor) {
        lock(id);
        List<Double> scores = jdbc.query("select success_rate,stress_success_rate from policy_release "
                        + "where policy_id=? and state in ('evaluated','approved','deployed')",
                (rs, row) -> List.of(rs.getDouble(1), rs.getDouble(2)), id).stream()
                .findFirst().orElseThrow(() -> notFoundOrConflict("policy", id));
        if (scores.get(0) < MIN_SUCCESS || scores.get(1) < MIN_STRESS_SUCCESS) {
            reject(HttpStatus.UNPROCESSABLE_ENTITY,
                    "policy must pass success_rate >= 0.80 and stress_success_rate >= 0.70");
        }
        jdbc.update("update policy_release set state='deployed',approved_by=?,approved_at=now() "
                + "where policy_id=?", actor, id);
        Stored stored = stored("policy_release", "policy_id", id);
        return response(id, stored.digest(), "deployed");
    }

    @Transactional
    public Map<String, Object> rollbackPolicy(String id, String actor) {
        lock(id);
        int changed = jdbc.update("update policy_release set state='rolled_back',approved_by=?,"
                + "approved_at=now() where policy_id=? and state in ('approved','deployed')", actor, id);
        if (changed == 0) {
            throw notFoundOrConflict("policy", id);
        }
        Stored stored = stored("policy_release", "policy_id", id);
        return response(id, stored.digest(), "rolled_back");
    }

    @Transactional
    public Map<String, Object> recordPerception(PerceptionResultRequest request) {
        validatePoseAndGrasps(request.pose(), request.grasps());
        Stored calibration = requiredStored("calibration_bundle", "calibration_id",
                request.calibrationId(), "approved");
        Stored policy = requiredStored("policy_release", "policy_id", request.policyId(), "deployed");
        if (!calibration.digest().equals(request.calibrationSha256())
                || !policy.digest().equals(request.policySha256())) {
            reject(HttpStatus.CONFLICT, "calibration or policy digest does not match approved release");
        }
        JsonNode canonical = canonical(request);
        String digest = digest(canonical);
        lock(request.resultId());
        Stored existing = stored("perception_result", "result_id", request.resultId());
        if (existing != null) {
            requireSameDigest(existing, digest, "result_id");
            return response(request.resultId(), digest, existing.state());
        }
        jdbc.update("insert into perception_result(result_id,digest,robot_id,scene_sequence,"
                        + "calibration_id,policy_id,result_json) values (?,?,?,?,?,?,?::jsonb)",
                request.resultId(), digest, request.robotId(), request.sceneSequence(),
                request.calibrationId(), request.policyId(), write(canonical));
        return response(request.resultId(), digest, "recorded");
    }

    @Transactional
    public Map<String, Object> createIntent(ExecutionIntentRequest request) {
        JsonNode result = resultJson(request.resultId());
        if (!request.robotId().equals(result.path("robot_id").asText())) {
            reject(HttpStatus.CONFLICT, "execution intent robot does not match perception result");
        }
        requireValidatedGrasp(result.path("grasps"), request.graspId());
        JsonNode canonical = canonical(request);
        String digest = digest(canonical);
        lock(request.intentId());
        Stored existing = stored("execution_intent", "intent_id", request.intentId());
        if (existing != null) {
            requireSameDigest(existing, digest, "intent_id");
            return response(request.intentId(), digest, existing.state());
        }
        jdbc.update("insert into execution_intent(intent_id,digest,result_id,grasp_id,robot_id,"
                        + "expected_state_version,expires_at,requested_by,state,intent_json) "
                        + "values (?,?,?,?,?,?,?,?,'pending',?::jsonb)",
                request.intentId(), digest, request.resultId(), request.graspId(), request.robotId(),
                request.expectedStateVersion(), Timestamp.from(request.expiresAt()), request.actor(),
                write(canonical));
        return response(request.intentId(), digest, "pending");
    }

    @Transactional
    public Map<String, Object> approveIntent(String id, String actor) {
        lock(id);
        int changed = jdbc.update("update execution_intent set state='approved',approved_by=?,"
                + "updated_at=now() where intent_id=? and state in ('pending','approved') "
                + "and expires_at > now()", actor, id);
        if (changed == 0) {
            throw notFoundOrConflict("execution intent", id);
        }
        jdbc.update("insert into robot_command_outbox(intent_id,state) values (?,'pending') "
                + "on conflict (intent_id) do nothing", id);
        Stored stored = stored("execution_intent", "intent_id", id);
        return response(id, stored.digest(), "approved");
    }

    public Map<String, Object> intent(String id) {
        return jdbc.query("select digest,state,expires_at,command_id,error_code from execution_intent "
                        + "where intent_id=?",
                rs -> {
                    if (!rs.next()) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "execution intent not found");
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("intent_id", id);
                    result.put("digest", rs.getString(1));
                    result.put("state", rs.getString(2));
                    result.put("expires_at", rs.getTimestamp(3).toInstant());
                    result.put("command_id", rs.getString(4));
                    result.put("error_code", rs.getString(5));
                    return result;
                }, id);
    }

    @Scheduled(fixedDelayString = "${robot.outbox-delay-ms:500}")
    public void dispatchPending() {
        List<String> ids = jdbc.query("select intent_id from robot_command_outbox where "
                        + "(state='pending' or (state='dispatching' and lease_until < now())) "
                        + "and attempts < 5 order by updated_at limit 8",
                (rs, row) -> rs.getString(1));
        ids.forEach(this::dispatchOne);
    }

    void dispatchOne(String intentId) {
        int claimed = jdbc.update("update robot_command_outbox set state='dispatching',attempts=attempts+1,"
                        + "lease_until=now()+interval '30 seconds',updated_at=now() where intent_id=? "
                        + "and (state='pending' or (state='dispatching' and lease_until < now()))", intentId);
        if (claimed == 0) {
            return;
        }
        try {
            DispatchData data = dispatchData(intentId);
            if (!data.expiresAt().isAfter(Instant.now())) {
                throw new DispatchRejected("intent_expired", "execution intent expired");
            }
            JsonNode state = gateway.state(data.robotId());
            long actualVersion = state.path("state_version").asLong(-1);
            if (actualVersion != data.expectedStateVersion()) {
                throw new DispatchRejected("stale_state", "robot state_version changed");
            }
            if (!"normal".equals(state.path("hardware_safety_state").asText())
                    || state.path("software_protective_stop").asBoolean()) {
                throw new DispatchRejected("robot_not_safe", "robot safety state blocks execution");
            }
            JsonNode grasp = requireValidatedGrasp(data.result().path("grasps"), data.graspId());
            validateCapabilityProfile(data, state, grasp);
            ObjectNode action = mapper.createObjectNode();
            action.put("type", "manipulate");
            action.set("joint_positions_rad", grasp.path("validated_joint_positions_rad"));
            action.put("max_force_n", grasp.path("max_force_n").asDouble());
            ObjectNode command = mapper.createObjectNode();
            command.put("contract_version", "1.0.0");
            command.put("command_id", "intent:" + intentId);
            command.put("robot_id", data.robotId());
            command.put("issued_at", Instant.now().toString());
            command.put("expires_at", data.expiresAt().toString());
            command.put("expected_state_version", actualVersion);
            command.set("action", action);
            JsonNode response = gateway.submit(command);
            jdbc.update("update execution_intent set state='dispatched',command_id=?,"
                            + "command_response=?::jsonb,updated_at=now() where intent_id=?",
                    command.path("command_id").asText(), write(response), intentId);
            jdbc.update("update robot_command_outbox set state='dispatched',lease_until=null,"
                    + "updated_at=now() where intent_id=?", intentId);
        } catch (DispatchRejected rejected) {
            jdbc.update("update execution_intent set state='rejected',error_code=?,updated_at=now() "
                    + "where intent_id=?", rejected.code(), intentId);
            jdbc.update("update robot_command_outbox set state='failed',last_error=?,lease_until=null,"
                    + "updated_at=now() where intent_id=?", bounded(rejected.getMessage()), intentId);
        } catch (RuntimeException transientFailure) {
            jdbc.update("update robot_command_outbox set state=case when attempts>=5 then 'failed' "
                            + "else 'pending' end,last_error=?,lease_until=null,updated_at=now() "
                            + "where intent_id=?", bounded(transientFailure.getMessage()), intentId);
        }
    }

    private DispatchData dispatchData(String intentId) {
        return jdbc.query("select i.robot_id,i.expected_state_version,i.expires_at,i.grasp_id,"
                        + "p.result_json::text,r.target_product_id,r.capability_profile_digest "
                        + "from execution_intent i join perception_result p on p.result_id=i.result_id "
                        + "join policy_release r on r.policy_id=p.policy_id "
                        + "where i.intent_id=? and i.state='approved'",
                rs -> {
                    if (!rs.next()) {
                        throw new DispatchRejected("intent_not_approved", "intent is not approved");
                    }
                    return new DispatchData(rs.getString(1), rs.getLong(2),
                            rs.getTimestamp(3).toInstant(), rs.getString(4), read(rs.getString(5)),
                            rs.getString(6), rs.getString(7));
                }, intentId);
    }

    private void validateCapabilityProfile(DispatchData data, JsonNode state, JsonNode grasp) {
        String actualProductId = state.path("product_id").asText();
        if (!data.productId().equals(actualProductId)) {
            throw new DispatchRejected("product_mismatch", "live robot product does not match policy");
        }
        JsonNode profile = gateway.product(actualProductId);
        if (!data.profileDigest().equals(profile.path("profile_digest").asText())) {
            throw new DispatchRejected("profile_mismatch", "capability profile digest changed");
        }
        JsonNode joints = grasp.path("validated_joint_positions_rad");
        JsonNode limits = profile.path("joint_limits");
        if (profile.path("joint_count").asInt(-1) != joints.size()
                || !limits.isArray() || limits.size() != joints.size()) {
            throw new DispatchRejected("joint_profile_mismatch", "joint vector does not match profile");
        }
        for (int index = 0; index < joints.size(); index++) {
            double position = joints.path(index).asDouble(Double.NaN);
            double minimum = limits.path(index).path("min_position_rad").asDouble(Double.NaN);
            double maximum = limits.path(index).path("max_position_rad").asDouble(Double.NaN);
            if (!Double.isFinite(position) || !Double.isFinite(minimum) || !Double.isFinite(maximum)
                    || position < minimum || position > maximum) {
                throw new DispatchRejected("joint_limit_exceeded", "joint position exceeds profile");
            }
        }
        double force = grasp.path("max_force_n").asDouble(Double.NaN);
        double maxForce = profile.path("max_manipulation_force_n").asDouble(Double.NaN);
        if (!Double.isFinite(maxForce) || force > maxForce) {
            throw new DispatchRejected("force_limit_exceeded", "grasp force exceeds profile");
        }
    }

    static void validatePoseAndGrasps(JsonNode pose, JsonNode grasps) {
        if (!pose.isObject() || !pose.path("position_m").isArray()
                || pose.path("position_m").size() != 3 || !pose.path("quaternion_xyzw").isArray()
                || pose.path("quaternion_xyzw").size() != 4) {
            reject(HttpStatus.UNPROCESSABLE_ENTITY,
                    "pose requires position_m[3] and quaternion_xyzw[4]");
        }
        double norm = 0;
        for (JsonNode value : pose.path("quaternion_xyzw")) {
            if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
                reject(HttpStatus.UNPROCESSABLE_ENTITY, "pose quaternion must be finite");
            }
            norm += value.asDouble() * value.asDouble();
        }
        if (Math.abs(Math.sqrt(norm) - 1.0) > 1e-4) {
            reject(HttpStatus.UNPROCESSABLE_ENTITY, "pose quaternion must be unit length");
        }
        if (!grasps.isArray() || grasps.isEmpty() || grasps.size() > 64) {
            reject(HttpStatus.UNPROCESSABLE_ENTITY, "grasps must contain 1 through 64 candidates");
        }
        for (JsonNode grasp : grasps) {
            if (!grasp.path("grasp_id").isTextual() || !grasp.path("validated").isBoolean()) {
                reject(HttpStatus.UNPROCESSABLE_ENTITY,
                        "each grasp requires grasp_id and validated");
            }
            JsonNode joints = grasp.path("validated_joint_positions_rad");
            if (grasp.path("validated").asBoolean() && (!joints.isArray() || joints.size() != 6)) {
                reject(HttpStatus.UNPROCESSABLE_ENTITY,
                        "validated MM-01 grasp requires six joint positions");
            }
            double force = grasp.path("max_force_n").asDouble(Double.NaN);
            if (!Double.isFinite(force) || force <= 0 || force > 250) {
                reject(HttpStatus.UNPROCESSABLE_ENTITY, "grasp max_force_n is outside 0..250");
            }
        }
    }

    private void validateCalibration(CalibrationRequest request) {
        if (request.parentFrame().equals(request.childFrame())) {
            reject(HttpStatus.UNPROCESSABLE_ENTITY, "calibration parent and child frames must differ");
        }
        if (!request.intrinsics().isObject() || !request.extrinsics().isObject()) {
            reject(HttpStatus.UNPROCESSABLE_ENTITY,
                    "calibration intrinsics and extrinsics must be objects");
        }
    }

    private JsonNode requireValidatedGrasp(JsonNode grasps, String graspId) {
        for (JsonNode grasp : grasps) {
            if (graspId.equals(grasp.path("grasp_id").asText())) {
                if (!grasp.path("validated").asBoolean()) {
                    reject(HttpStatus.UNPROCESSABLE_ENTITY, "selected grasp is not validated");
                }
                return grasp;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "grasp candidate not found");
    }

    private JsonNode resultJson(String resultId) {
        return jdbc.query("select result_json::text from perception_result where result_id=?",
                rs -> {
                    if (!rs.next()) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "perception result not found");
                    }
                    return read(rs.getString(1));
                }, resultId);
    }

    private Stored requiredStored(String table, String key, String id, String state) {
        Stored result = stored(table, key, id);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, key + " not found");
        }
        if (!state.equals(result.state())) {
            reject(HttpStatus.CONFLICT, key + " is not in required state " + state);
        }
        return result;
    }

    private Stored stored(String table, String key, String id) {
        if (!List.of("calibration_bundle", "policy_release", "perception_result",
                "execution_intent").contains(table)
                || !List.of("calibration_id", "policy_id", "result_id", "intent_id").contains(key)) {
            throw new IllegalArgumentException("untrusted table identifier");
        }
        String stateColumn = table.equals("perception_result") ? "'recorded'" : "state";
        return jdbc.query("select digest," + stateColumn + " from " + table + " where " + key + "=?",
                rs -> rs.next() ? new Stored(rs.getString(1), rs.getString(2)) : null, id);
    }

    private void lock(String id) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, id);
    }

    private JsonNode canonical(Object value) {
        return RobotCommandService.canonicalize(mapper.valueToTree(value), mapper);
    }

    private String digest(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(write(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String write(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JsonNode read(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, Object> response(String id, String digest, String state) {
        return Map.of("id", id, "digest", digest, "state", state);
    }

    private static void requireSameDigest(Stored stored, String digest, String key) {
        if (!stored.digest().equals(digest)) {
            reject(HttpStatus.CONFLICT, key + " already exists with different content");
        }
    }

    private ResponseStatusException notFoundOrConflict(String label, String id) {
        Stored any = label.equals("calibration")
                ? stored("calibration_bundle", "calibration_id", id)
                : stored("policy_release", "policy_id", id);
        return new ResponseStatusException(any == null ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT,
                any == null ? label + " not found" : label + " state transition is not allowed");
    }

    private static void reject(HttpStatus status, String detail) {
        throw new ResponseStatusException(status, detail);
    }

    private static String bounded(String value) {
        String safe = value == null ? "unknown failure" : value;
        return safe.substring(0, Math.min(safe.length(), 512));
    }

    private record Stored(String digest, String state) {}
    private record DispatchData(String robotId, long expectedStateVersion, Instant expiresAt,
                                String graspId, JsonNode result, String productId,
                                String profileDigest) {}
    private static final class DispatchRejected extends RuntimeException {
        private final String code;
        DispatchRejected(String code, String message) {
            super(message);
            this.code = code;
        }
        String code() { return code; }
    }
}
