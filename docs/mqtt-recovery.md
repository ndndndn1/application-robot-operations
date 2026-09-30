# MQTT telemetry: bounded recovery, durable decisions

This optional adapter demonstrates a Spring Boot / SQL / Mosquitto workflow for robot operations.
It does not send robot commands. HTTP ingestion remains available and unchanged when the `mqtt`
Spring profile is disabled. It uses the existing telemetry service, rule engine, PostgreSQL table,
and history HTTP API; there is no second rules implementation.

## Run the local demo

Requirements: Docker Engine with Compose v2 and Python 3. The default HTTP/command demo is unchanged.
This standalone stack has no physical gateway, external proxy network, or cloud dependency at runtime.

```bash
docker compose -f compose.mqtt.yaml up -d --build --wait
docker compose -f compose.mqtt.yaml exec -T mosquitto mosquitto_pub \
  -h 127.0.0.1 -q 1 -t robots/demo-1/telemetry -m \
  '{"robotId":"demo-1","timestamp":1700000000,"x":0,"y":0,"battery":8,"taskState":"moving","errorCodes":[],"modelId":"standard","eventId":"0f547e75-2972-44e8-811e-24b8ded2ab3f"}'
curl -fsS 'http://127.0.0.1:8803/api/robots/demo-1/recent?limit=20'
docker compose -f compose.mqtt.yaml down
```

Expected history contains the source UUID, `critical`, and `battery_critical`. Repeating the
publication preserves one database row and its original decision. `down` keeps broker/DB volumes;
`down -v` irreversibly removes this local demo's history and sessions. Do not use it on retained data.
The anonymous broker binds to host loopback only; do not expose it to a LAN or Internet. Authentication,
per-robot publish ACLs, TLS, tenant separation, rate limits and operational backups are prerequisites
for shared/production use, not capabilities claimed by this demo.

## Delivery contract

- Publish QoS1, non-retained JSON to `robots/{robotId}/telemetry`, with a stable UUID `eventId` generated
  and retained by the publisher before sending. Same ID/content is replay; changed content is a conflict.
  An ID generated afresh for each retry defeats deduplication. HTTP may omit eventId; MQTT may not.
- HTTP's bean validation is reused, including nested error-code bounds; unknown fields, malformed JSON,
  trailing JSON, missing IDs and topic/body robot mismatch are rejected. Existing finite-number,
  future timestamp and model rules still run in the same telemetry service.
- A received valid event is ACKed only after the existing database transaction returns successfully.
  A permanent invalid/conflicting message is ACKed only after a rejection transaction commits.
  A transaction failure or unexpected error disconnects the subscriber without ACK and retries the
  persistent session. Poison traffic has a durable disposition and cannot indefinitely block valid traffic
  while the database is healthy.
- The `mqtt_rejection` ledger stores SHA-256, byte length, bounded topic/event ID, reason, delivery count,
  first/last seen. It deliberately does not store raw payloads. A repeated identical rejection increments
  its delivery count. The original publisher must retain source data if repair/replay is required.
- Topic mismatch validation is a consistency check, **not device authentication**. MQTT v5
  retain-as-published makes both live retained publications and retained replay observable/rejectable.
  QoS0 is rejected if it arrives; it has no recoverability guarantee. QoS2 publishers are downgraded to
  subscription QoS1 and must follow the same source-ID contract.
- Out-of-order observations remain auditable and are flagged; they do not advance the last snapshot.
  Operator history always reads PostgreSQL. A nonempty Redis ring can be incomplete after a crash,
  eviction or partial write, so it is never used as authoritative history. Redis remains best-effort,
  after-commit, TTL-bounded auxiliary data.

### What is and is not guaranteed

For a committed valid event ID, there is one stored telemetry effect. Replayed delivery returns the
stored decision; conflict does not overwrite it. A process crash after commit but before ACK is safe
because the broker redelivers and the database deduplicates. These are application idempotency and
at-least-once transport properties, **not physical exactly-once execution** or a distributed transaction.
No physical action is part of MQTT ingestion.

Publisher PUBACK only proves the broker accepted a publication, not that PostgreSQL committed it.
The producer must reconcile source event IDs against durable history/SQL or its own acknowledgment
protocol, and keep replayable source records. Mosquitto may silently drop messages when its queue is
full. Its periodic snapshot persistence is not a per-PUBACK fsync guarantee: arbitrary broker/power
crash can lose unsaved session/backlog data. Graceful broker restart with its volume intact is tested;
arbitrary broker kill/power-loss losslessness is not claimed. Lost volumes, session expiry, changed
client IDs, competing subscribers with the same ID, and traffic before the first successful
subscription can lose backlog. Never infer end-to-end success from a QoS label.

## Explicit bounds and operations

| Bound | Demo value / effect |
|---|---|
| Consumer in-flight QoS1 | MQTT5 Receive Maximum 1, broker max-inflight 1; synchronous processing, no application worker queue |
| Broker queued messages | 1,000 per client, beyond in-flight |
| Broker queued bytes | 16 MiB per client; first message/byte limit wins |
| Incoming broker packet | 65,536 bytes including protocol overhead; larger packets are disconnected before the application and have no SQL rejection record |
| Application payload | 16,384 bytes; larger broker-delivered payloads get a durable `oversized` rejection |
| Subscriber session | 24 hours, stable client ID `fleet-ingress-demo`, clean start false |
| Reconnect | One supervisor, 2-second delay after attempt; connect 5s and token 10s timeouts |
| DB wait | Pool acquisition 3s, socket 5s; retry instead of ACK on failure |
| Broker simultaneous connections | 32; trusted local demo, not an adversarial load guarantee |
| Containers | app 768MiB, PG 512MiB, Redis 192MiB, broker 128MiB |

At rate R events/second, plan outages substantially shorter than 1,000/R seconds and below the byte
limit, subtracting existing queue occupancy and allowing headroom. This is a planning ceiling, not a
promise under every workload. Session expiry also bounds recovery. QoS0 floods are outside this
contract and require perimeter rate limiting. Rejection rows are bounded in size, but unique rejected
messages grow the SQL table over time: monitor disk, export/archive according to your audit policy,
and never delete records needed for reconciliation. There is no automatic audit retention deletion.

Inspect `/actuator/prometheus` for `fleet_mqtt_connected`, `fleet_mqtt_dispositions_total` with
`outcome=accepted|duplicate|rejected`, `fleet_mqtt_retry_failures_total`,
`fleet_mqtt_connect_failures_total`, and `fleet_mqtt_protocol_errors_total`. Counters reset on process
restart; SQL is durable evidence. Connected=1 means connected/subscribed, not an empty backlog.
Actuator overall health separately reports database/cache availability.

```bash
# Rejections; read-only, bounded operator query
docker compose -f compose.mqtt.yaml exec -T postgres psql -U fleet -d fleet -c \
  'select reason,topic,event_id,payload_sha256,payload_bytes,deliveries,last_seen from mqtt_rejection order by last_seen desc limit 50;'
# Recovery exercise; publish a bounded batch while stopped, keep the same IDs/client ID
docker compose -f compose.mqtt.yaml stop app
docker compose -f compose.mqtt.yaml start app
# Inspect broker queue/drop messages and storage-related errors
docker compose -f compose.mqtt.yaml logs --tail=100 mosquitto app
```

On DB outage restore PostgreSQL first, leave broker/session storage intact, then confirm source-ID
counts and original decisions in history. Do not restart with a new client ID or delete volumes as a
"fix". On conflicts inspect the source-ID producer; give genuinely new observations new IDs rather
than overwriting existing history. Replaying a repaired poison message with a valid ID is an explicit
producer action. Broker persistence files must be backed up consistently with broker shutdown.

## Reproducible purpose test and evidence

```bash
tools/run-mqtt-purpose.sh
cat artifacts/mqtt-purpose.json
```

This creates an isolated random Compose project, builds the real application, exercises real Mosquitto
and PostgreSQL, writes machine-readable scenario evidence and diagnostics, and removes only that
project's disposable volumes. It fails if required tools/processes are unavailable. Do not run it
against a live deployment. Ports 1883/8803 must be free. The dedicated `mqtt-purpose-recovery` workflow
runs the same command and uploads JSON/logs. Passing this workflow is required by this change's merge
review, even if repository branch protection has not made it a required check.

Acceptance covers source-ID/payload/rule equality through SQL and HTTP, duplicate replay over restart,
conflicting replay, real JVM exit86 after commit before ACK with broker redelivery, DB stop/recovery,
broker graceful persisted restart, delayed observations, permanent invalid/oversized/topic mismatch/
retained/QoS0 input, and bounded multi-robot count/recovery/latency. Default measured workload is four
robots × 20 observations. Recovery must finish within 60s and steady-state p95 publish-to-SQL visibility
within 10s on this small workload; evidence records actual values, not inferred production throughput.

The deliberately destructive `mqtt-fault-test` profile and
`MQTT_TEST_HALT_AFTER_COMMIT_EVENT_ID` are only enabled in the disposable harness. The listener halts
only on the first newly accepted matching event, never on duplicate replay. Normal demo profile
`mqtt` does not instantiate it. **Never enable the fault profile in a retained/shared deployment.**

Native-process verification can run the identical assertions via `--control-command`, using the
explicit driver protocol in `tests/mqtt_recovery.py`. Such evidence must identify native component
versions and must not be presented as a Docker/container pass. Container topology, resource limits,
permissions and image security remain separate CI checks.

The MQTT table is an additive idempotent repeatable migration, avoiding the V4 migration reserved by
the separate perception/policy work. Future schema evolution must use reviewed ALTER migrations;
`CREATE IF NOT EXISTS` is not a schema-drift repair mechanism.

References: [Mosquitto queue/persistence configuration](https://mosquitto.org/man/mosquitto-conf-5.html),
[Paho MQTT5 1.2.5 source](https://github.com/eclipse-paho/paho.mqtt.java/tree/v1.2.5/org.eclipse.paho.mqttv5.client).
