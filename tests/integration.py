#!/usr/bin/env python3
import concurrent.futures
import datetime
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

# Perception-to-command lifecycle: only approved calibration, evaluated BC-RNN policy,
# validated grasp, explicit operator approval, and matching live robot version can dispatch.
suffix = uuid.uuid4().hex[:12]
zeros = "0" * 64
calibration_id = f"cal-{suffix}"
status, _, calibration = request("POST", "/api/v2/calibrations", {
    "schema_version": "1.0.0", "calibration_id": calibration_id,
    "robot_id": "mm-01-a", "product_id": "mock-mobile-manipulator-mm-01",
    "sensor_rig_id": "rig-stereo-01",
    "parent_frame": "base_link", "child_frame": "stereo_camera_link",
    "intrinsics": {"left": {"fx": 600.0, "fy": 600.0, "cx": 320.0, "cy": 240.0}},
    "extrinsics": {"translation_m": [0.2, 0.0, 0.8], "quaternion_xyzw": [0, 0, 0, 1]},
    "source_artifact_sha256": "1" * 64, "method": "stereo_hand_eye",
    "software_version": "embedded-robot-ros2/1.0.0",
    "observed_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
})
assert status == 201, calibration
status, _, approved_calibration = request(
    "POST", f"/api/v2/calibrations/{calibration_id}/approve", {"actor": "operator:integration"})
assert status == 200 and approved_calibration["state"] == "approved", approved_calibration

policy_id = f"policy-{suffix}"
status, _, policy = request("POST", "/api/v2/policies", {
    "schema_version": "1.0.0", "policy_id": policy_id, "algorithm": "bc_rnn",
    "target_product_id": "mock-mobile-manipulator-mm-01",
    "capability_profile_digest": "sha256:ac9b980057912f3967c9c5444912429f481ca5f5bd482f34f6ac6fbdf87cd322",
    "model_sha256": "2" * 64,
    "dataset_sha256": "3" * 64, "input_schema_sha256": "4" * 64,
    "output_schema_sha256": "5" * 64, "success_rate": 0.90, "stress_success_rate": 0.80,
})
assert status == 201, policy
status, _, deployed_policy = request(
    "POST", f"/api/v2/policies/{policy_id}/promote", {"actor": "operator:integration"})
assert status == 200 and deployed_policy["state"] == "deployed", deployed_policy

result_id = f"result-{suffix}"
grasp_id = f"grasp-{suffix}"
status, _, result = request("POST", "/api/v2/perception-results", {
    "schema_version": "1.0.0", "result_id": result_id, "robot_id": "mm-01-a",
    "scene_sequence": 1, "calibration_id": calibration_id,
    "calibration_sha256": calibration["digest"], "policy_id": policy_id,
    "policy_sha256": policy["digest"], "input_artifact_sha256": zeros,
    "pose": {"position_m": [0.45, 0.0, 0.2], "quaternion_xyzw": [0, 0, 0, 1]},
    "grasps": [{"grasp_id": grasp_id, "validated": True,
                "validated_joint_positions_rad": [0.0, -0.3, 0.7, 0.0, -0.4, 0.0],
                "max_force_n": 35.0}],
    "produced_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
})
assert status == 201 and result["state"] == "recorded", result

intent_id = f"intent-{suffix}"
expires_at = (datetime.datetime.now(datetime.timezone.utc)
              + datetime.timedelta(seconds=60)).isoformat()
status, _, intent = request("POST", "/api/v2/execution-intents", {
    "schema_version": "1.0.0", "intent_id": intent_id, "result_id": result_id,
    "grasp_id": grasp_id, "robot_id": "mm-01-a", "expected_state_version": 0,
    "expires_at": expires_at, "actor": "planner:integration",
})
assert status == 201 and intent["state"] == "pending", intent
status, _, approved_intent = request(
    "POST", f"/api/v2/execution-intents/{intent_id}/approve", {"actor": "operator:integration"})
assert status == 200 and approved_intent["state"] == "approved", approved_intent

deadline = time.monotonic() + 10
while time.monotonic() < deadline:
    status, _, lifecycle = request("GET", f"/api/v2/execution-intents/{intent_id}")
    if lifecycle.get("state") in {"dispatched", "rejected", "failed"}:
        break
    time.sleep(0.25)
assert status == 200 and lifecycle["state"] == "dispatched", lifecycle
assert lifecycle["command_id"] == f"intent:{intent_id}", lifecycle

print("integration, idempotency, concurrency, perception approval, JIT state, outbox, and metrics passed")
