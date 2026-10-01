package io.career262.fleet.mqtt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Destructive integration-test probe. Never enable this profile outside an ephemeral test stack. */
@Component
@Profile("mqtt-fault-test")
public class MqttCrashFault {
    private final String target;
    public MqttCrashFault(@Value("${MQTT_TEST_HALT_AFTER_COMMIT_EVENT_ID:}") String target) { this.target = target; }
    @EventListener
    public void afterCommit(MqttTelemetryHandler.Disposition disposition) {
        if ("accepted".equals(disposition.outcome()) && target.equals(disposition.eventId())) {
            Runtime.getRuntime().halt(86);
        }
    }
}
