# Operations runbook

## Health and metrics

- Liveness/readiness: `GET /actuator/health`
- Prometheus: `GET /actuator/prometheus`
- Key application series: `fleet_telemetry_ingest_total` and
  `fleet_telemetry_cache_failures_total`; command outcomes use `robot_command_total`.

Alert on sustained HTTP 5xx, PostgreSQL pool exhaustion, any cache-failure increase combined with
database latency, or absence of expected telemetry. Redis is an optimization: PostgreSQL remains
authoritative and recent-event reads fall back to it. A Redis outage can increase latency but must
not reject valid ingestion.

## Recovery and maintenance

1. Confirm PostgreSQL health before restarting the application.
2. Restart Redis at any time; cache entries have a 24-hour TTL and rebuild on new traffic.
3. Never repair idempotency conflicts by deleting events. A `409` means a producer reused an
   `eventId` for different content and must issue a new UUID.
4. Treat out-of-order events as retained evidence. They are marked by the rule engine and do not
   replace the newest snapshot.
5. For command failures, preserve `command_id` while querying the physical gateway; do not issue a
   new ID until the prior command reaches a known terminal state.

## Robot gateway rollout

The default deployment uses `ROBOT_TARGET_MODE=mock` and the `physical-robot-interface` mock URL.
Before changing the URL to a real embedded adapter, record its product/model, isolated OT network,
ROS domain/namespace, conformance result, watchdog, and hardware E-stop test. Only then enable both
`ROBOT_TARGET_MODE=real` and `ROBOT_ALLOW_REAL=true` for the approved deployment window.

The operator UI always confirms mock commands. A production perimeter must additionally enforce
authenticated operator identity and per-command authorization. Software `protective_stop` is not a
replacement for a wired, certified emergency-stop circuit.

Run `python3 tools/soak.py --duration-seconds 3600 --workers 8` only in an isolated test deployment.
The default 60-second run is intended as a quick stability check. The harness reports request count,
errors, mean latency, and p95 latency; capture container memory and database growth alongside it for
a formal soak result.
