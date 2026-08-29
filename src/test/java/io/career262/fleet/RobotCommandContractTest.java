package io.career262.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RobotCommandContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void canonicalizationIgnoresObjectMemberOrderRecursively() throws Exception {
        JsonNode first = mapper.readTree("""
                {"type":"navigate","target":{"x_m":1,"y_m":2,"yaw_rad":0,"frame":"map"},
                 "max_speed_mps":0.5}
                """);
        JsonNode reordered = mapper.readTree("""
                {"max_speed_mps":0.5,"target":{"frame":"map","yaw_rad":0,"y_m":2,"x_m":1},
                 "type":"navigate"}
                """);
        assertEquals(RobotCommandService.canonicalize(first, mapper),
                RobotCommandService.canonicalize(reordered, mapper));
    }

    @Test void navigateRequiresTypedTargetAndRejectsUnknownFields() throws Exception {
        assertThrows(ResponseStatusException.class, () -> RobotCommandService.validateAction(
                mapper.readTree("{\"type\":\"navigate\"}")));
        assertThrows(ResponseStatusException.class, () -> RobotCommandService.validateAction(
                mapper.readTree("""
                        {"type":"navigate","target":{"x_m":1,"y_m":2,"yaw_rad":0,
                         "frame":"map","speed":99}}
                        """)));
    }

    @Test void manipulateRequiresFiniteBoundedJointsAndForce() throws Exception {
        assertThrows(ResponseStatusException.class, () -> RobotCommandService.validateAction(
                mapper.readTree("{\"type\":\"manipulate\",\"joint_positions_rad\":[]}")));
        assertThrows(ResponseStatusException.class, () -> RobotCommandService.validateAction(
                mapper.readTree("""
                        {"type":"manipulate","joint_positions_rad":[0,1],"max_force_n":251}
                        """)));
    }

    @Test void protectiveStopRequiresReason() throws Exception {
        assertThrows(ResponseStatusException.class, () -> RobotCommandService.validateAction(
                mapper.readTree("{\"type\":\"protective_stop\",\"reason\":\" \"}")));
    }
}
