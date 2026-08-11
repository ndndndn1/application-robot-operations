# Robot Fleet Telemetry

An enterprise-oriented Spring Boot and React/TypeScript reference for ingesting robot state,
applying model-aware rules, retaining an auditable event stream, and exposing operational metrics.
It is telemetry-only: it does not control robots or implement vendor protocols.

## Data contract

`POST /api/telemetry` accepts strict JSON. IDs are bounded, numeric fields must be finite, battery is
0–100, task state is one of `idle`, `moving`, `docking`, `charging`, or `error`, and model profile is
one of `standard`, `compact`, or `heavy`. Unknown fields and profiles are rejected. Validation and
conflict responses use `application/problem+json` (RFC 9457).

```bash
curl -fsS http://127.0.0.1:8803/api/telemetry \
  -H 'content-type: application/json' \
  -d '{
    "robotId":"robot-17","timestamp":1750000000.25,"x":12.4,"y":8.1,"battery":72,
    "taskState":"moving","errorCodes":[],"modelId":"standard",
    "eventId":"46517c9c-7697-44e8-8fa7-e22873bcfad0"
  }'
```

`eventId` is optional. The server creates one when absent. Replaying the same ID and same payload
returns the stored decision with `duplicate: true`; reusing it for different content returns `409`.
`GET /api/robots/{robotId}/recent?limit=20` returns decoded JSON objects ordered by observation time,
not JSON-encoded strings.

## Consistency and failure semantics

Ingestion runs in a PostgreSQL transaction. A transaction-scoped advisory lock serializes each
robot's read/evaluate/insert path across replicas. Events have a unique UUID and SHA-256 payload
fingerprint; history order is `(observed_at DESC, id DESC)`. Older packets are retained and marked
`out_of_order_timestamp`, but never replace a newer snapshot.

Redis receives the snapshot and bounded 50-event ring only after database commit. Both keys expire
after 24 hours. Cache failures increment a metric and recent-event reads fall back to PostgreSQL, so
Redis cannot become the system of record.

## Run and verify

```bash
docker compose up -d --build --wait
python3 tests/integration.py
docker compose stop redis
python3 tests/redis_failure.py
docker compose down
```

The service binds only to `127.0.0.1:8803`. Database/cache traffic uses an internal network and the
application's egress environment points at the host-standard Squid proxy network. The application runs as
UID 10001 with a read-only filesystem, no Linux capabilities, `no-new-privileges`, and CPU, memory,
and PID limits.

Unit and build checks:

```bash
docker compose build test
python3 quality/check_score.py
cd web && npm ci --ignore-scripts && npm run typecheck && npm run build
```

Deterministic mock scenarios support `nominal`, `anomalies`, `ordering`, and `idempotency`:

```bash
python3 tools/mock_fleet.py generate anomalies /tmp/anomalies.jsonl
python3 tools/mock_fleet.py replay /tmp/anomalies.jsonl --verify
python3 tools/soak.py --duration-seconds 60 --workers 8
```

See [OPERATIONS.md](OPERATIONS.md) for health, metrics, outage, recovery, and soak guidance. CI also
runs integration/concurrency and Redis-failure checks, dependency review, a high/critical Trivy gate,
locked dependency builds, non-root inspection, SBOM generation, and provenance attestation.

## Scope boundaries

Authentication, device certificates, TLS, tenant boundaries, and rate limits belong at the deployment
perimeter and must be supplied before shared or production use. Robot control, ROS, CAN, MQTT, and
vendor-specific adapters are deliberately out of scope. Default database credentials are local-only.
