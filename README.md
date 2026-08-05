# Robot Fleet Telemetry

A Spring Boot and React/TypeScript reference system for ingesting robot state, applying model-aware
rules, persisting events, and exposing operational metrics.

## Implemented flow

1. Robots or gateways post validated telemetry to `/api/telemetry`.
2. Redis supplies the previous snapshot and a bounded recent-event ring.
3. A deterministic rule engine evaluates position jumps, battery behavior, task-state consistency,
   event ordering, and known or unknown error codes.
4. PostgreSQL stores normalized events through a Flyway-managed schema.
5. Actuator exposes health and Prometheus metrics, including a critical-event counter.
6. A React/TypeScript console sends and displays live telemetry responses.

## Run

```bash
docker compose up -d --build --wait
python3 smoke.py
docker compose down
```

The service binds only to `127.0.0.1:8803`. The smoke scenario verifies a normal packet, a critical
position jump, and an unknown error code.

## Test

```bash
docker compose run --rm test
```

JUnit cases cover position jumps, charging loss, and unknown errors. Compose health checks ensure
PostgreSQL and Redis are ready before the application starts.

## Limits

- Model profiles are intentionally small examples and require fleet-specific calibration.
- Authentication, device certificates, and tenant boundaries belong in the deployment perimeter.
- The OCI image is portable, but provider-specific infrastructure templates are not included.
