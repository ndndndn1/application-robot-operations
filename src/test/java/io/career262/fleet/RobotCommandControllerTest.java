package io.career262.fleet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.career262.fleet.RobotCommandController.CommandResult;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RobotCommandController.class)
class RobotCommandControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean RobotCommandService service;

    @Test void validPhysicalContractIsForwarded() throws Exception {
        String commandId = UUID.randomUUID().toString();
        when(service.submit(any())).thenReturn(new CommandResult(commandId, "mock", false,
                mapper.readTree("{\"status\":\"accepted\"}")));
        mvc.perform(post("/api/v1/commands").contentType("application/json").content("""
                {"contract_version":"1.0.0","command_id":"%s","robot_id":"mh-01-a",
                 "issued_at":"2026-08-29T00:00:00Z","expires_at":"2026-08-29T00:01:00Z",
                 "expected_state_version":0,
                 "action":{"type":"navigate","target":{"x_m":1,"y_m":2,"yaw_rad":0,
                 "frame":"map"},"max_speed_mps":0.5}}
                """.formatted(commandId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target_mode").value("mock"))
                .andExpect(jsonPath("$.record.status").value("accepted"));
    }

    @Test void malformedCommandFailsClosed() throws Exception {
        mvc.perform(post("/api/v1/commands").contentType("application/json").content("""
                {"contract_version":"2","robot_id":"bad id","action":{}}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test void unknownFieldsAreRejected() throws Exception {
        mvc.perform(post("/api/v1/commands").contentType("application/json").content("""
                {"contract_version":"1.0.0","robot_id":"mh-01-a",
                 "issued_at":"2026-08-29T00:00:00Z","expires_at":"2026-08-29T00:01:00Z",
                 "expected_state_version":0,"action":{"type":"protective_stop","reason":"test"},
                 "untrusted":true}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"));
    }
}
