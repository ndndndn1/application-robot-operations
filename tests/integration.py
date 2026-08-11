#!/usr/bin/env python3
import concurrent.futures
import json
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:8803"


def request(method: str, path: str, body: dict | None = None) -> tuple[int, str, dict]:
    req = urllib.request.Request(BASE + path, method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={"content-type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, response.headers.get_content_type(), json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, error.headers.get_content_type(), json.load(error)


def packet(robot: str, sequence: int, event_id: str | None = None) -> dict:
    return {"robotId": robot, "timestamp": time.time() + sequence / 1000, "x": sequence / 100,
            "y": 0, "battery": 90, "taskState": "moving", "errorCodes": [],
            "modelId": "standard", "eventId": event_id or str(uuid.uuid4())}


invalid = packet("bad id", 0)
invalid["battery"] = 101
status, content_type, problem = request("POST", "/api/telemetry", invalid)
assert (status, content_type, problem["status"]) == (422, "application/problem+json", 422)

event_id = str(uuid.uuid4())
original = packet("integration-idempotency", 0, event_id)
first = request("POST", "/api/telemetry", original)
second = request("POST", "/api/telemetry", original)
assert first[0] == second[0] == 200
assert first[2]["duplicate"] is False and second[2]["duplicate"] is True
changed = dict(original, battery=89)
status, content_type, problem = request("POST", "/api/telemetry", changed)
assert status == 409 and content_type == "application/problem+json" and problem["status"] == 409

robot = "integration-concurrency"
packets = [packet(robot, sequence) for sequence in range(16)]
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
    results = list(executor.map(lambda value: request("POST", "/api/telemetry", value), packets))
assert all(result[0] == 200 for result in results), results
status, _, recent = request("GET", f"/api/robots/{robot}/recent?limit=16")
assert status == 200 and len(recent["items"]) == 16
assert all(isinstance(item, dict) and "telemetry" in item for item in recent["items"])
observed = [item["telemetry"]["timestamp"] for item in recent["items"]]
assert observed == sorted(observed, reverse=True), observed

with urllib.request.urlopen(BASE + "/actuator/prometheus", timeout=20) as response:
    metrics = response.read().decode()
assert "fleet_telemetry_ingest_total" in metrics
print("integration, idempotency, RFC 9457, concurrency, ordering, and metrics passed")
