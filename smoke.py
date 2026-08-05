import json
import urllib.request
import time


def send(body: dict) -> dict:
    request = urllib.request.Request("http://127.0.0.1:8803/api/telemetry", data=json.dumps(body).encode(), headers={"content-type":"application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)

base = {"robotId":"smoke-r1","timestamp":time.time(),"x":0,"y":0,"battery":80,"taskState":"moving","errorCodes":[],"modelId":"standard"}
assert send(base)["severity"] == "normal"
jump = dict(base, timestamp=base["timestamp"]+1, x=20, y=20, battery=72)
result = send(jump)
assert result["severity"] == "critical", result
assert "position_jump" in result["rules"], result
unknown = dict(jump, timestamp=jump["timestamp"]+1, errorCodes=["E_UNKNOWN_SENSOR"])
assert any(rule.startswith("unknown_error:") for rule in send(unknown)["rules"])
print("Fleet smoke passed")
