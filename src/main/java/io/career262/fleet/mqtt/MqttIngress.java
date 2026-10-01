package io.career262.fleet.mqtt;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.*;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Single QoS1 subscriber. Broker session is the bounded backlog; no application work queue. */
@Component
@Profile("mqtt")
@EnableConfigurationProperties(MqttSettings.class)
public class MqttIngress implements SmartLifecycle, MqttCallback {
    private static final Logger LOG = LoggerFactory.getLogger(MqttIngress.class);
    private final MqttSettings settings;
    private final MqttTelemetryHandler handler;
    private final MeterRegistry metrics;
    private final ApplicationEventPublisher events;
    private final AtomicInteger connected = new AtomicInteger();
    private final AtomicBoolean reconnectRequested = new AtomicBoolean();
    private volatile boolean running;
    private MqttClient client;
    private ScheduledExecutorService connector;

    public MqttIngress(MqttSettings settings, MqttTelemetryHandler handler, MeterRegistry metrics,
                       ApplicationEventPublisher events) {
        this.settings = settings; this.handler = handler; this.metrics = metrics; this.events = events;
        metrics.gauge("fleet_mqtt_connected", connected);
    }

    @Override public synchronized void start() {
        if (running) return;
        try {
            // Subscriber receives only QoS1; all durable state lives in PostgreSQL/broker session.
            client = new MqttClient(settings.brokerUri(), settings.clientId(), new MemoryPersistence());
            client.setManualAcks(true);
            client.setCallback(this);
            client.setTimeToWait(10000);
        } catch (MqttException exception) { throw new IllegalStateException("Cannot create MQTT client", exception); }
        running = true;
        connector = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "mqtt-connect"); thread.setDaemon(true); return thread;
        });
        connector.scheduleWithFixedDelay(this::connect, 0, 2, TimeUnit.SECONDS);
    }

    private void connect() {
        if (!running) return;
        try {
            // Paho 1.2.5's default callback swallows user exceptions. Explicitly recover outside
            // the callback thread (disconnecting inside it can deadlock), without acknowledging.
            if (reconnectRequested.getAndSet(false) && client.isConnected()) {
                client.disconnectForcibly(0, 1000, false);
            }
            if (client.isConnected()) return;
            MqttConnectionOptions options = new MqttConnectionOptions();
            options.setAutomaticReconnect(false); // One bounded supervisor also retries first connection.
            options.setCleanStart(false);
            options.setSessionExpiryInterval(settings.sessionExpirySeconds());
            options.setReceiveMaximum(1);
            options.setMaximumPacketSize(65536L);
            options.setKeepAliveInterval(15);
            options.setConnectionTimeout(5);
            client.connect(options);
            MqttSubscription subscription = new MqttSubscription("robots/+/telemetry", 1);
            subscription.setRetainAsPublished(true); // Reject retained live publications as well as replay.
            subscription.setRetainHandling(1); // Do not resend retained data on existing subscription.
            IMqttToken token = client.subscribe(new MqttSubscription[]{subscription});
            if (token.getGrantedQos().length != 1 || token.getGrantedQos()[0] != 1) {
                client.disconnect();
                throw new IllegalStateException("MQTT broker did not grant QoS1 subscription");
            }
            connected.set(1);
            LOG.info("MQTT telemetry subscription ready with persistent session");
        } catch (Exception exception) {
            connected.set(0);
            metrics.counter("fleet_mqtt_connect_failures_total").increment();
            LOG.warn("MQTT connection/subscription failed: {}", exception.getClass().getSimpleName());
            // A failed SUBACK must not leave a connected but unusable subscriber forever.
            try { if (client.isConnected()) client.disconnectForcibly(0, 1000, false); }
            catch (MqttException ignored) { /* supervisor retries */ }
        }
    }

    @Override public void messageArrived(String topic, MqttMessage message) throws Exception {
        try {
            var disposition = handler.handle(topic, message.getPayload(), message.getQos(), message.isRetained());
            metrics.counter("fleet_mqtt_dispositions_total", "outcome", disposition.outcome()).increment();
            events.publishEvent(disposition); // Fault-test-only listener can halt at the commit/ACK boundary.
            if (message.getQos() == 1) client.messageArrivedComplete(message.getId(), 1);
        } catch (Exception exception) {
            connected.set(0);
            metrics.counter("fleet_mqtt_retry_failures_total").increment();
            LOG.warn("MQTT disposition not acknowledged; persistent session will retry: {}",
                    exception.getClass().getSimpleName());
            reconnectRequested.set(true); // Supervisor closes/reconnects; manual ACK stays withheld.
        }
    }

    @Override public synchronized void stop() {
        running = false; connected.set(0);
        if (connector != null) connector.shutdownNow();
        if (client != null) {
            try { client.disconnectForcibly(0, 1000, false); client.close(true); }
            catch (MqttException exception) { LOG.debug("MQTT shutdown: {}", exception.getClass().getSimpleName()); }
        }
    }
    @Override public boolean isRunning() { return running; }
    @Override public void disconnected(MqttDisconnectResponse response) { connected.set(0); }
    @Override public void mqttErrorOccurred(MqttException exception) {
        metrics.counter("fleet_mqtt_protocol_errors_total").increment();
        reconnectRequested.set(true);
    }
    @Override public void deliveryComplete(IMqttToken token) {}
    @Override public void connectComplete(boolean reconnect, String serverURI) {}
    @Override public void authPacketArrived(int reasonCode, MqttProperties properties) {}
}
