#!/usr/bin/env python3
"""Validate the machine-readable quality scorecard without third-party dependencies."""
import json
import sys
from pathlib import Path

REQUIRED_GATES = {"tests", "runtime_smoke", "memory", "security", "docs_examples"}


def fail(message: str) -> None:
    raise ValueError(message)


def main() -> None:
    scorecard = json.loads(Path(__file__).with_name("scorecard.json").read_text())
    if scorecard.get("schema_version") != "1.0": fail("schema_version must be 1.0")
    target, score = scorecard.get("target"), scorecard.get("score")
    if not isinstance(target, int) or target < 80: fail("target must be an integer >= 80")
    categories = scorecard.get("categories")
    if not isinstance(categories, list) or not categories: fail("categories must be a non-empty list")
    ids: set[str] = set()
    for category in categories:
        if set(category) != {"id", "max", "earned", "evidence"}: fail("invalid category keys")
        if not isinstance(category["id"], str) or not category["id"]: fail("category id required")
        if category["id"] in ids: fail("category ids must be unique")
        ids.add(category["id"])
        maximum, earned = category["max"], category["earned"]
        if not isinstance(maximum, int) or not isinstance(earned, int) or not 0 <= earned <= maximum:
            fail(f"invalid points for {category['id']}")
        evidence = category["evidence"]
        if not isinstance(evidence, list) or not evidence or not all(isinstance(v, str) and v for v in evidence):
            fail(f"non-empty evidence required for {category['id']}")
    if sum(category["max"] for category in categories) != 100: fail("category maximums must total 100")
    if score != sum(category["earned"] for category in categories): fail("score must equal earned points")
    if score < target: fail("score is below target")
    gates = scorecard.get("hard_gates")
    if not isinstance(gates, dict) or set(gates) != REQUIRED_GATES or not all(v is True for v in gates.values()):
        fail("all required hard gates must be true")
    print(f"Quality scorecard passed: {score}/100 (target {target})")


if __name__ == "__main__":
    try: main()
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"scorecard invalid: {error}", file=sys.stderr)
        raise SystemExit(1) from error
