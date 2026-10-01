package io.career262.fleet.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MqttRejectionStoreTest {
    @Test void untrustedAuditStringsCannotPoisonPostgresText() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MqttRejectionStore store = new MqttRejectionStore(jdbc);
        for (String invalid : Arrays.asList("\u0000", "\ud800", "not-a-uuid", null)) {
            store.record("robots/\u0000\ud800/telemetry", new byte[]{0, (byte) 0xff}, 1, false, "validation", invalid);
        }
        for (var invocation : mockingDetails(jdbc).getInvocations()) {
            Object[] args = invocation.getArguments();
            assertThat(args[2]).isEqualTo("robots/\ufffd\ufffd/telemetry");
            assertThat(args[8]).isNull();
            assertThat(args[3]).isEqualTo(MqttRejectionStore.sha256(new byte[]{0, (byte) 0xff}));
        }
        assertThat(mockingDetails(jdbc).getInvocations()).hasSize(4);
    }
    @Test void preservesCanonicalUuidAndBoundsTopicWithoutSplittingUnicode() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        String id = "ad37a1bf-047e-4167-bf94-0c5e6b88d547";
        new MqttRejectionStore(jdbc).record("\ud83e\udd16".repeat(300), "body".getBytes(StandardCharsets.UTF_8),
                1, false, "conflict", id);
        Object[] args = mockingDetails(jdbc).getInvocations().iterator().next().getArguments();
        String topic = (String) args[2];
        assertThat(topic.codePointCount(0,topic.length())).isEqualTo(256);
        assertThat(args[8]).isEqualTo(id);
    }
}
