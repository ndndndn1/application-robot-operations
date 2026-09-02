# Quality Scorecard

The release currently earns **94/100** against an **80/100** acceptance target. The authoritative,
CI-validated representation is [`quality/scorecard.json`](../quality/scorecard.json); this document
provides the review rationale.

| Category | Earned / maximum | Evidence |
| --- | ---: | --- |
| Functional contract | 25 / 25 | Strict telemetry and calibration/policy/perception/intent validation, model profiles, RFC 9457 errors, idempotency, and object history |
| Data integrity | 20 / 20 | PostgreSQL transactions, advisory locks, immutable content digests, actor approvals, unique IDs, and stable ordering |
| Reliability and observability | 18 / 20 | Durable leased command outbox, bounded retry, JIT robot state/safety checks, cache fallback, health/metrics, and resource limits |
| Verification | 14 / 15 | JVM contract/controller tests plus API, idempotency, concurrency, Redis-failure, perception approval/dispatch, and runtime checks |
| Security and supply chain | 8 / 10 | Non-root read-only runtime, dropped capabilities, internal network, pinned inputs, locked npm, Trivy, Dependabot, SBOM, and provenance |
| Documentation and usability | 9 / 10 | Inputs, outputs, MM-01 mock target, perception lifecycle, failure semantics, target gating, metrics, safety boundaries, and operations |

## Hard gates

All required gates are true: tests, runtime smoke, memory/resource controls, security, and
documentation examples. CI validates the score arithmetic, the 100-point maximum, non-empty
evidence, the 80-point minimum, and the complete hard-gate set with:

```bash
python3 quality/check_score.py
```

The unearned points represent work outside this release's required scope, such as extended
multi-hour performance history and richer operator UX studies. They do not weaken a hard gate.
