#!/usr/bin/env python3
"""Bounded soak harness; choose duration explicitly for long runs."""
import argparse
import concurrent.futures
import json
import statistics
import time
import urllib.request
import uuid


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8803")
    parser.add_argument("--duration-seconds", type=int, default=60)
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()
    assert 1 <= args.duration_seconds <= 86400 and 1 <= args.workers <= 64
    deadline = time.monotonic() + args.duration_seconds
    latencies: list[float] = []

    def worker(number: int) -> int:
        count = 0
        while time.monotonic() < deadline:
            now = time.time()
            body = {"robotId": f"soak-{number:02d}", "timestamp": now, "x": count / 100,
                    "y": number, "battery": 75, "taskState": "moving", "errorCodes": [],
                    "modelId": "standard", "eventId": str(uuid.uuid4())}
            request = urllib.request.Request(args.base_url + "/api/telemetry", method="POST",
                data=json.dumps(body).encode(), headers={"content-type": "application/json"})
            started = time.monotonic()
            with urllib.request.urlopen(request, timeout=30) as response:
                assert response.status == 200
                response.read()
            latencies.append(time.monotonic() - started)
            count += 1
        return count

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        counts = list(executor.map(worker, range(args.workers)))
    ordered = sorted(latencies)
    p95 = ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))] * 1000
    print(json.dumps({"requests": sum(counts), "errors": 0,
                      "mean_ms": round(statistics.mean(latencies) * 1000, 2), "p95_ms": round(p95, 2)}))


if __name__ == "__main__":
    main()
