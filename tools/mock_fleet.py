#!/usr/bin/env python3
"""Deterministic telemetry fixture generation, replay, and verification."""

import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

BASE_TIME = 1_700_000_000.0
NAMESPACE = uuid.UUID("9f49ea43-d46f-47e2-93a8-f74b0fe39534")


def packet(name: str, index: int, **changes: object) -> dict:
    value = {
        "robotId": f"mock-{name}", "timestamp": BASE_TIME + index, "x": float(index),
        "y": 0.0, "battery": 90.0 - index, "taskState": "moving", "errorCodes": [],
        "modelId": "standard", "eventId": str(uuid.uuid5(NAMESPACE, f"{name}:{index}")),
    }
    value.update(changes)
    return value


def scenario(name: str) -> list[dict]:
    if name == "nominal":
        return [{"telemetry": packet(name, i), "expect": "normal"} for i in range(3)]
    if name == "anomalies":
        return [
            {"telemetry": packet(name, 0), "expect": "normal"},
            {"telemetry": packet(name, 1, x=20.0), "expect": "critical", "rule": "position_jump"},
            {"telemetry": packet(name, 2, x=21.0, errorCodes=["E_VENDOR_UNKNOWN"]),
             "expect": "critical", "rule": "unknown_error:E_VENDOR_UNKNOWN"},
        ]
    if name == "ordering":
        return [
            {"telemetry": packet(name, 5), "expect": "normal"},
            {"telemetry": packet(name, 4), "expect": "warn", "rule": "out_of_order_timestamp"},
        ]
    if name == "idempotency":
        item = {"telemetry": packet(name, 0), "expect": "normal"}
        return [item, item]
    raise ValueError(f"unknown scenario: {name}")


def write_fixture(name: str, destination: Path) -> None:
    destination.write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in scenario(name)))


def post(base_url: str, telemetry: dict) -> dict:
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/telemetry", data=json.dumps(telemetry).encode(),
        headers={"content-type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def replay(source: Path, base_url: str, verify: bool) -> None:
    rows = [json.loads(line) for line in source.read_text().splitlines() if line.strip()]
    for index, row in enumerate(rows):
        response = post(base_url, row["telemetry"])
        if verify:
            assert response["severity"] == row["expect"], (index, row, response)
            if "rule" in row:
                assert row["rule"] in response["rules"], (index, row, response)
            if index and rows[index - 1] == row:
                assert response["duplicate"] is True, response
        print(json.dumps(response, sort_keys=True))


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    generate = sub.add_parser("generate")
    generate.add_argument("scenario", choices=["nominal", "anomalies", "ordering", "idempotency"])
    generate.add_argument("output", type=Path)
    replay_parser = sub.add_parser("replay")
    replay_parser.add_argument("source", type=Path)
    replay_parser.add_argument("--base-url", default="http://127.0.0.1:8803")
    replay_parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.command == "generate":
        write_fixture(args.scenario, args.output)
    else:
        replay(args.source, args.base_url, args.verify)


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, urllib.error.URLError, ValueError) as error:
        print(f"mock fleet failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
