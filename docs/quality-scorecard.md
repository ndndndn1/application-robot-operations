# Quality Scorecard

The release currently earns **93/100** against an **80/100** acceptance target. The authoritative,
CI-validated representation is [`quality/scorecard.json`](../quality/scorecard.json); this document
provides the review rationale.

| Category | Earned / maximum | Evidence |
| --- | ---: | --- |
| Functional contract | 25 / 25 | Strict telemetry validation, model profiles, RFC 9457 errors, idempotency, and object history |
| Data integrity | 20 / 20 | PostgreSQL transactions, per-robot advisory locks, unique event IDs, payload hashes, and stable ordering |
| Reliability and observability | 18 / 20 | After-commit Redis caching, TTL and database fallback, health/Prometheus endpoints, limits, and a soak harness |
| Verification | 14 / 15 | Seven JVM tests plus API, idempotency, concurrency, ordering, Redis-failure, deterministic replay, and runtime checks |
| Security and supply chain | 8 / 10 | Non-root read-only runtime, dropped capabilities, internal network, pinned inputs, locked npm, Trivy, Dependabot, SBOM, and provenance |
| Documentation and usability | 8 / 10 | API examples, consistency/failure semantics, metrics, security boundaries, operations, and requirements coverage |

## Hard gates

All required gates are true: tests, runtime smoke, memory/resource controls, security, and
documentation examples. CI validates the score arithmetic, the 100-point maximum, non-empty
evidence, the 80-point minimum, and the complete hard-gate set with:

```bash
python3 quality/check_score.py
```

The unearned points represent work outside this release's required scope, such as extended
multi-hour performance history and richer operator UX studies. They do not weaken a hard gate.
