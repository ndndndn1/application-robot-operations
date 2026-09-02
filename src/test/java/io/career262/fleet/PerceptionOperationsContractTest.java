package io.career262.fleet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PerceptionOperationsContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void acceptsUnitPoseAndValidatedMm01Grasp() throws Exception {
        var pose = mapper.readTree("""
                {"position_m":[0.4,-0.1,0.2],"quaternion_xyzw":[0,0,0,1]}
                """);
        var grasps = mapper.readTree("""
                [{"grasp_id":"top","validated":true,
                  "validated_joint_positions_rad":[0,-0.3,0.7,0,-0.4,0],"max_force_n":35}]
                """);
        assertDoesNotThrow(() -> PerceptionOperationsService.validatePoseAndGrasps(pose, grasps));
    }

    @Test void rejectsNonUnitQuaternion() throws Exception {
        var pose = mapper.readTree("""
                {"position_m":[0,0,0],"quaternion_xyzw":[0,0,0,2]}
                """);
        var grasps = mapper.readTree("""
                [{"grasp_id":"top","validated":true,
                  "validated_joint_positions_rad":[0,0,0,0,0,0],"max_force_n":20}]
                """);
        assertThrows(ResponseStatusException.class,
                () -> PerceptionOperationsService.validatePoseAndGrasps(pose, grasps));
    }

    @Test void rejectsInvalidJointCountAndUnsafeForce() throws Exception {
        var pose = mapper.readTree("""
                {"position_m":[0,0,0],"quaternion_xyzw":[0,0,0,1]}
                """);
        var badJoints = mapper.readTree("""
                [{"grasp_id":"top","validated":true,
                  "validated_joint_positions_rad":[0,0,0],"max_force_n":20}]
                """);
        var badForce = mapper.readTree("""
                [{"grasp_id":"top","validated":true,
                  "validated_joint_positions_rad":[0,0,0,0,0,0],"max_force_n":251}]
                """);
        assertThrows(ResponseStatusException.class,
                () -> PerceptionOperationsService.validatePoseAndGrasps(pose, badJoints));
        assertThrows(ResponseStatusException.class,
                () -> PerceptionOperationsService.validatePoseAndGrasps(pose, badForce));
    }
}
