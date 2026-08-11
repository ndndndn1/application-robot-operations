package io.career262.fleet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TelemetryController.class)
class TelemetryControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean TelemetryService service;

    @Test void invalidTelemetryUsesProblemDetails() throws Exception {
        mvc.perform(post("/api/telemetry").contentType("application/json").content("""
                {"robotId":"bad id","timestamp":1,"x":0,"y":0,"battery":101,
                 "taskState":"flying","errorCodes":[],"modelId":"standard"}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value("Telemetry validation failed"));
    }

    @Test void unknownJsonFieldIsRejected() throws Exception {
        mvc.perform(post("/api/telemetry").contentType("application/json").content("""
                {"robotId":"r1","timestamp":1,"x":0,"y":0,"battery":90,
                 "taskState":"idle","errorCodes":[],"modelId":"standard","speed":10}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"));
    }
}
