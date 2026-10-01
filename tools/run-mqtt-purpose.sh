#!/usr/bin/env bash
# Destructive only to a dedicated ephemeral Compose project; never touches real robots.
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

command -v python3 >/dev/null || { echo 'python3 is required; purpose tests did not run' >&2; exit 2; }
mkdir -p artifacts
python3 - <<'PY'
import json
from pathlib import Path
Path('artifacts/mqtt-purpose.json').write_text(json.dumps({
    'schema_version': '1.0', 'status': 'running', 'phase': 'bootstrap',
    'real_processes_required': True, 'scenarios': []}, indent=2) + '\n')
PY

export COMPOSE_PROJECT_NAME="robot-mqtt-purpose-$(date +%s)-$$"
export SPRING_PROFILES_ACTIVE=mqtt,mqtt-fault-test
export MQTT_TEST_HALT_AFTER_COMMIT_EVENT_ID=ad37a1bf-047e-4167-bf94-0c5e6b88d547
compose=(docker compose -p "$COMPOSE_PROJECT_NAME" -f compose.mqtt.yaml)
started=0
finish() {
  status=$?
  trap - EXIT
  if [[ "$started" == 1 ]]; then
    "${compose[@]}" logs --no-color > artifacts/mqtt-compose.log 2>&1 || true
    "${compose[@]}" ps -a > artifacts/mqtt-compose-state.txt 2>&1 || true
    "${compose[@]}" down -v --remove-orphans || status=1
  fi
  if [[ "$status" != 0 ]]; then
    python3 - "$status" <<'PY'
import json, sys
from pathlib import Path
path = Path('artifacts/mqtt-purpose.json')
report = json.loads(path.read_text())
if report.get('status') != 'failed':
    report.update(status='failed', error='Runner or dependency failure; see console/Compose logs',
                  runner_exit_code=int(sys.argv[1]))
path.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
PY
  fi
  exit "$status"
}
trap finish EXIT

command -v docker >/dev/null || { echo 'Docker is required; no mocked fallback and no purpose pass' >&2; exit 2; }
docker compose version >/dev/null
docker info >/dev/null
"${compose[@]}" config --quiet
started=1
"${compose[@]}" up -d --build --wait --wait-timeout 180
python3 tests/mqtt_recovery.py --project "$COMPOSE_PROJECT_NAME" \
  --compose-file compose.mqtt.yaml --report artifacts/mqtt-purpose.json "$@"
