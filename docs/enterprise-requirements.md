# Enterprise Requirements

This document records the acceptance contract for the telemetry-only hardening release. The
machine-readable coverage map is [`requirements-coverage.json`](../requirements-coverage.json).

| Requirement | Acceptance evidence | Verification |
| --- | --- | --- |
| Strict telemetry API | Required fields, finite/ranged values, model profiles, task-state and error-code constraints, unknown-field rejection, and RFC 9457 problem responses | `TelemetryControllerTest`, `RuleEngineTest`, `tests/integration.py` |
| Idempotent ingestion | Optional UUID `eventId`; identical replay returns the stored response and a changed payload returns HTTP 409 | `tests/integration.py` |
| Transactional ordering | PostgreSQL transaction plus per-robot advisory lock; previous snapshot selected by `observed_at DESC, id DESC`; an older event cannot replace the latest snapshot | `tests/integration.py` concurrency and ordering cases |
| Resilient cache | Redis is updated only after database commit, uses a 24-hour TTL and bounded 50-event history, and recent reads fall back to PostgreSQL | `tests/redis_failure.py`, `tests/integration.py` |
| Recent object API | Recent telemetry is returned as typed response objects in deterministic timestamp/event-ID order with limits from 1 through 50 | `tests/integration.py` |
| Deterministic fleet simulation | Nominal, anomaly, ordering, and idempotency scenarios can be generated, replayed, and verified repeatably | `tools/mock_fleet.py` |
| Operational readiness | Prometheus metrics, health probes, resource ceilings, non-root read-only runtime, runbook, and bounded soak harness | `OPERATIONS.md`, `tools/soak.py`, CI runtime inspection |
| Supply-chain controls | Locked npm install, pinned build/runtime images and actions, high/critical Trivy gate, Dependabot, SBOM, and provenance | `.github/workflows/publish.yml`, `.github/dependabot.yml` |
| Web console | Typed fields, profile/state selection, submission results, and recent telemetry on a responsive page | `web/src/main.tsx`, `web/src/styles.css`, `npm run typecheck`, `npm run build` |

## Scope boundary

This release ingests, evaluates, stores, and displays telemetry. It does not send robot commands
or add vendor integrations, ROS, CAN, or MQTT behavior.

## Release gate

Acceptance requires all automated tests, runtime smoke and outage checks, security scanning,
documentation examples, and the scorecard target to pass. A long soak is prepared but must be
scheduled explicitly so it does not consume shared-host resources unexpectedly.
