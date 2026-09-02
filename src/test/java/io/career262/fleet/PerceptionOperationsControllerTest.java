package io.career262.fleet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PerceptionOperationsController.class)
class PerceptionOperationsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PerceptionOperationsService service;

    @Test void validCalibrationBundleIsForwarded() throws Exception {
        when(service.createCalibration(any())).thenReturn(Map.of(
                "id", "cal-01", "digest", "a".repeat(64), "state", "draft"));
        mvc.perform(post("/api/v2/calibrations").contentType("application/json").content("""
                {"schema_version":"1.0.0","calibration_id":"cal-01","robot_id":"mm-01-a",
                 "product_id":"MM-01","sensor_rig_id":"rig-01","parent_frame":"base_link",
                 "child_frame":"camera_link","intrinsics":{"fx":600},"extrinsics":{"x":0.1},
                 "source_artifact_sha256":"%s","method":"stereo_hand_eye",
                 "software_version":"embedded-1.0","observed_at":"2026-09-02T00:00:00Z"}
                """.formatted("b".repeat(64))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("draft"));
    }

    @Test void missingIdentityFieldsFailClosed() throws Exception {
        mvc.perform(post("/api/v2/policies").contentType("application/json").content("""
                {"schema_version":"1.0.0","algorithm":"bc_rnn","target_product_id":"MM-01",
                 "capability_profile_digest":"sha256:%s",
                 "model_sha256":"%s","dataset_sha256":"%s","input_schema_sha256":"%s",
                 "output_schema_sha256":"%s","success_rate":0.9,"stress_success_rate":0.8}
                """.formatted("e".repeat(64), "a".repeat(64), "b".repeat(64),
                        "c".repeat(64), "d".repeat(64))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType("application/problem+json"));
    }

    @Test void unknownFieldsAreRejected() throws Exception {
        mvc.perform(post("/api/v2/execution-intents").contentType("application/json").content("""
                {"schema_version":"1.0.0","intent_id":"intent-01","result_id":"result-01",
                 "grasp_id":"grasp-01","robot_id":"mm-01-a","expected_state_version":0,
                 "expires_at":"2027-09-02T00:00:00Z","actor":"operator","unsafe":true}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"));
    }
}
