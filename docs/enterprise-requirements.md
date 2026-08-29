# Enterprise Requirements

This document records the acceptance contract for the robot operations release. The
machine-readable coverage map is [`requirements-coverage.json`](../requirements-coverage.json).

## Target

A production-oriented application for receiving, evaluating, storing, and displaying robot fleet
observations and submitting canonical commands through a replaceable physical gateway. The HTTP
service binds to `127.0.0.1:8803`; PostgreSQL is authoritative and Redis is an optional bounded
telemetry cache. Vendor, ROS, CAN, and MQTT interfaces remain in the embedded adapter layer.

## Inputs

- `POST /api/telemetry` accepts `application/json`. Required fields are `robotId` (1-80 characters,
  `[A-Za-z0-9][A-Za-z0-9._:-]*`), positive finite Unix-seconds `timestamp` no more than five minutes
  in the future, finite `x` and `y` from -1,000,000 through 1,000,000, `battery` from 0 through 100,
  `taskState` in `idle|moving|docking|charging|error`, zero to 32 uppercase error codes of at most 80
  characters, and `modelId` in `standard|compact|heavy`. Optional `eventId` is an RFC 4122 UUID.
  Unknown JSON fields are rejected.
- `GET /api/robots/{robotId}/recent?limit=N` accepts the same robot-ID format and an integer limit
  from 1 through 50; the default is 20.
- `POST /api/v1/commands` accepts physical contract `1.0.0`: optional UUID `command_id`, bounded
  `robot_id`, ISO timestamps `issued_at` and `expires_at`, nonnegative `expected_state_version`, and
  a typed `navigate`, `manipulate`, or `protective_stop` action. Unknown top-level fields are
  rejected. Expired requests, invalid time order, future issue time, and unsupported actions fail
  before reaching the gateway.
- `GET /api/v1/commands/{commandId}` and `POST /api/v1/commands/{commandId}/cancel` accept UUIDs and
  operate only on commands already recorded by this application.
- The web console supplies the same typed telemetry fields. The deterministic command-line mocks
  accept only the documented scenario, URL, duration, and worker arguments below.

## Outputs

- Successful ingestion returns HTTP 200 JSON with `eventId`, the normalized `telemetry` object,
  `severity` (`normal|warn|critical`), string `rules`, nullable numeric `deltaMeters` and
  `deltaBattery`, and boolean `duplicate`. An identical event replay returns the stored result with
  `duplicate=true`; reuse of its ID with different content returns HTTP 409.
- Recent lookup returns HTTP 200 JSON as `{ "robotId": string, "items": Response[] }`, newest first
  by timestamp and event ID, capped by the requested limit and the 50-event cache ring.
- Command calls return `{command_id,target_mode,duplicate,record}`. PostgreSQL retains request hash,
  request, gateway response, status, target mode, and timestamps. Exact replay returns the original
  response with `duplicate=true`; changed content with the same ID returns 409.
- Client errors use `application/problem+json` RFC 9457 objects with `type`, `title`, `status`,
  `detail`, and `instance`. Health and Prometheus metrics are exposed under `/actuator`.

## Software mocks

- `python3 tools/mock_fleet.py generate SCENARIO FILE` accepts `nominal`, `anomalies`, `ordering`, or
  `idempotency` and emits deterministic JSON Lines. Each line contains a telemetry input, expected
  severity, and an optional expected rule.
- `python3 tools/mock_fleet.py replay FILE --base-url http://127.0.0.1:8803 --verify` posts that JSONL
  stream, writes one API response JSON object per line, and exits nonzero on transport or contract
  mismatch.
- `python3 tools/soak.py --duration-seconds S --workers W` is a bounded load mock: `S` is 1-86,400
  and `W` is 1-64. It outputs one JSON object containing `requests`, `errors`, `mean_ms`, and
  `p95_ms`. It generates observations only; it neither simulates nor invokes robot control.

| Requirement | Acceptance evidence | Verification |
| --- | --- | --- |
| Strict telemetry API | Required fields, finite/ranged values, model profiles, task-state and error-code constraints, unknown-field rejection, and RFC 9457 problem responses | `TelemetryControllerTest`, `RuleEngineTest`, `tests/integration.py` |
| Idempotent ingestion | Optional UUID `eventId`; identical replay returns the stored response and a changed payload returns HTTP 409 | `tests/integration.py` |
| Transactional ordering | PostgreSQL transaction plus per-robot advisory lock; previous snapshot selected by `observed_at DESC, id DESC`; an older event cannot replace the latest snapshot | `tests/integration.py` concurrency and ordering cases |
| Resilient cache | Redis is updated only after database commit, uses a 24-hour TTL and bounded 50-event history, and recent reads fall back to PostgreSQL | `tests/redis_failure.py`, `tests/integration.py` |
| Recent object API | Recent telemetry is returned as typed response objects in deterministic timestamp/event-ID order with limits from 1 through 50 | `tests/integration.py` |
| Command gateway contract | Strict physical command envelope, expiry and action validation, status, cancellation, and RFC 9457 rejection | `RobotCommandControllerTest`, cross-repository integration smoke |
| Command idempotency and audit | UUID and payload hash serialize duplicate submission; PostgreSQL retains canonical request and response | `RobotCommandService`, `V3__robot_command_audit.sql`, integration smoke |
| Mock-first safety | Mock is the default; real targeting requires an independent explicit enablement flag after safety approval | `HttpRobotGateway`, operations runbook |
| Deterministic fleet simulation | Nominal, anomaly, ordering, and idempotency scenarios can be generated, replayed, and verified repeatably | `tools/mock_fleet.py` |
| Operational readiness | Prometheus metrics, health probes, resource ceilings, non-root read-only runtime, runbook, and bounded soak harness | `OPERATIONS.md`, `tools/soak.py`, CI runtime inspection |
| Supply-chain controls | Locked npm install, pinned build/runtime images and actions, high/critical Trivy gate, Dependabot, SBOM, and provenance | `.github/workflows/publish.yml`, `.github/dependabot.yml` |
| Web console | Typed fields, profile/state selection, submission results, and recent telemetry on a responsive page | `web/src/main.tsx`, `web/src/styles.css`, `npm run typecheck`, `npm run build` |

## Scope boundary

This release ingests telemetry and submits canonical commands to a configured gateway. It does not
generate trajectories, bypass robot capability checks, implement vendor/ROS/CAN/MQTT protocols, or
claim certified safety behavior. Software protective stop and hardware E-stop remain distinct.

## Release gate

Acceptance requires all automated tests, runtime smoke and outage checks, security scanning,
documentation examples, and the scorecard target to pass. A long soak is prepared but must be
scheduled explicitly so it does not consume shared-host resources unexpectedly.
