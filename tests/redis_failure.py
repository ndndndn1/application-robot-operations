#!/usr/bin/env python3
"""Run while Redis is stopped; ingestion and recent reads must use PostgreSQL."""
import json
import time
import urllib.request
import uuid

base = "http://127.0.0.1:8803"
body = {"robotId": "redis-failure", "timestamp": time.time(), "x": 0, "y": 0, "battery": 88,
        "taskState": "idle", "errorCodes": [], "modelId": "standard", "eventId": str(uuid.uuid4())}
request = urllib.request.Request(base + "/api/telemetry", method="POST", data=json.dumps(body).encode(),
                                 headers={"content-type": "application/json"})
with urllib.request.urlopen(request, timeout=30) as response:
    assert json.load(response)["severity"] == "normal"
with urllib.request.urlopen(base + "/api/robots/redis-failure/recent", timeout=30) as response:
    result = json.load(response)
assert isinstance(result["items"][0], dict)
print("Redis outage fallback passed")
