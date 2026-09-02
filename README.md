# Application Robot Operations

An enterprise-oriented Spring Boot and React/TypeScript operations application for ingesting robot
state, applying model-aware rules, retaining auditable telemetry and command streams, and using a
replaceable physical robot gateway. It never implements vendor or ROS protocols directly.

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

## Safe command lifecycle

`POST /api/v1/commands` accepts the canonical `physical-robot-interface` command contract. The
application records a SHA-256 fingerprint and gateway response in PostgreSQL before returning it.
An identical `command_id` replay returns the stored response with `duplicate=true`; different
content returns 409. Status and cancellation use `GET /api/v1/commands/{commandId}` and
`POST /api/v1/commands/{commandId}/cancel`.

```bash
issued="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
expires="$(date -u -d '+60 seconds' +%Y-%m-%dT%H:%M:%SZ)"
command_id="$(python3 -c 'import uuid; print(uuid.uuid4())')"
curl -fsS http://127.0.0.1:8803/api/v1/commands \
  -H 'content-type: application/json' -d "{
    \"contract_version\":\"1.0.0\",\"command_id\":\"$command_id\",
    \"robot_id\":\"mh-01-a\",\"issued_at\":\"$issued\",\"expires_at\":\"$expires\",
    \"expected_state_version\":0,
    \"action\":{\"type\":\"protective_stop\",\"reason\":\"operator test\"}}"
```

The default Compose deployment starts the deterministic `physical-robot-interface` mock on the
private `backend` network, so the command examples above work without an external robot service.
A real target requires both
`ROBOT_TARGET_MODE=real` and `ROBOT_ALLOW_REAL=true` after adapter conformance and hardware safety
approval. Software `protective_stop` is not a certified hardware emergency-stop circuit.

## Perception and policy approval lifecycle

The `/api/v2` control plane turns artifacts produced by simulation and embedded perception into an
auditable command intent. It stores metadata and digests, not RGB-D frames or model binaries.

1. Register `POST /api/v2/calibrations`, then explicitly approve the bundle at
   `/api/v2/calibrations/{id}/approve`.
2. Register an evaluated `bc_rnn` release at `POST /api/v2/policies`. Promotion requires
   `success_rate >= 0.80`, `stress_success_rate >= 0.70`, and the immutable
   `capability_profile_digest` from `GET /v2/products/{product_id}`.
3. Record a perception result whose calibration and policy digests exactly match the approved
   releases. A validated MM-01 grasp contains six joint positions and a force limit at most 250 N.
4. Create an execution intent and approve it. The durable outbox re-reads the mock robot's
   `state_version` and safety state immediately before submitting a deterministic command ID.

The default target is the software product `MM-01` mock through its HTTP contract. Inputs are
calibration metadata, immutable SHA-256 artifact identities, 6DoF pose, validated grasp candidates,
and an operator identity. Outputs are lifecycle state plus stable content digest and, after dispatch,
the physical-interface command ID. Robot CAD, camera frames, datasets, and model files stay in their
own module/storage boundary.

Creation never moves hardware. Approval only makes the intent eligible for dispatch; expiry,
changed state version, hardware safety state, or software protective stop rejects it fail-closed.
The same JIT check also rejects changed product/profile digests, wrong joint count, any joint outside
its named position bounds, and force above the product profile maximum.

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

Deterministic telemetry scenarios support `nominal`, `anomalies`, `ordering`, and `idempotency`:

```bash
python3 tools/mock_fleet.py generate anomalies /tmp/anomalies.jsonl
python3 tools/mock_fleet.py replay /tmp/anomalies.jsonl --verify
python3 tools/soak.py --duration-seconds 60 --workers 8
```

See [OPERATIONS.md](OPERATIONS.md) for health, metrics, outage, recovery, and soak guidance. CI also
runs integration/concurrency and Redis-failure checks, Dependabot updates, a high/critical Trivy gate,
locked dependency builds, non-root inspection, SBOM generation, and provenance attestation.

Review the [enterprise requirements](docs/enterprise-requirements.md) and
[quality scorecard rationale](docs/quality-scorecard.md) for the acceptance contract and evidence.

## Scope boundaries

Authentication, device certificates, TLS, tenant boundaries, and rate limits belong at the deployment
perimeter and must be supplied before shared or production use. ROS, CAN, MQTT, vendor-specific
protocols, trajectory generation, raw sensor transport, model serving, and certified safety behavior
remain out of scope. The application submits canonical commands only through the configured gateway.
Default database credentials are local-only.
