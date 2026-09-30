#!/usr/bin/env python3
"""Real Spring/PostgreSQL/Mosquitto recovery contract, using only Python's stdlib.

This is a destructive, isolated-test-stack harness, not a production diagnostics tool.
The runner creates its own Compose project. Every published accepted event is checked
against PostgreSQL and the existing history API; MQTT PUBACK alone is never success.
An optional external process-control command lets exactly the same assertions run on
native real processes when Docker is unavailable. See ExternalDriver's protocol.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shlex
import socket
import struct
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
FAULT_EVENT_ID = "ad37a1bf-047e-4167-bf94-0c5e6b88d547"
BASE_TIME = 1_700_000_000.0


def check(condition: bool, message: object) -> None:
    if not condition:
        raise AssertionError(message)


def literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def eventually(description, operation, timeout=60.0, interval=0.2):
    deadline = time.monotonic() + timeout
    last = None
    while True:
        try:
            value = operation()
            if value:
                return value
            last = repr(value)
        except (OSError, ValueError, RuntimeError, urllib.error.URLError, subprocess.SubprocessError) as error:
            last = repr(error)
        if time.monotonic() >= deadline:
            raise AssertionError(f"Timed out after {timeout}s: {description}; last={last}")
        time.sleep(interval)


def utf8(value: str) -> bytes:
    encoded = value.encode("utf-8")
    check(len(encoded) <= 65535, "MQTT string too long")
    return struct.pack("!H", len(encoded)) + encoded


def remaining_length(value: int) -> bytes:
    check(0 <= value <= 268435455, "MQTT packet too large")
    result = bytearray()
    while True:
        digit = value % 128
        value //= 128
        result.append(digit | (128 if value else 0))
        if not value:
            return bytes(result)


def receive_exact(connection: socket.socket, length: int) -> bytes:
    value = bytearray()
    while len(value) < length:
        data = connection.recv(length - len(value))
        if not data:
            raise ConnectionError("MQTT peer closed before complete packet")
        value.extend(data)
    return bytes(value)


def receive_packet(connection: socket.socket) -> tuple[int, bytes]:
    header = receive_exact(connection, 1)[0]
    length = 0
    multiplier = 1
    for _ in range(4):
        digit = receive_exact(connection, 1)[0]
        length += (digit & 127) * multiplier
        if not digit & 128:
            check(length <= 1024 * 1024, "Unexpectedly large broker response")
            return header, receive_exact(connection, length)
        multiplier *= 128
    raise ValueError("Malformed MQTT remaining length")


class Publisher:
    """Small real MQTT 3.1.1 publisher; subscriber must tolerate ordinary v3 devices."""

    def __init__(self, host: str, port: int):
        self.host, self.port = host, port
        self.connection = None
        self.packet_id = 0

    def __enter__(self):
        self.connection = socket.create_connection((self.host, self.port), timeout=10)
        self.connection.settimeout(10)
        body = utf8("MQTT") + bytes([4, 2]) + struct.pack("!H", 30)
        body += utf8("purpose-" + uuid.uuid4().hex[:16])
        self.connection.sendall(b"\x10" + remaining_length(len(body)) + body)
        header, body = receive_packet(self.connection)
        check(header == 0x20 and body == b"\x00\x00", f"MQTT CONNECT rejected: {header}, {body!r}")
        return self

    def publish(self, topic: str, payload: bytes, qos=1, retained=False):
        check(qos in (0, 1), "Harness supports publish QoS 0 or 1")
        self.packet_id = self.packet_id % 65535 + 1
        body = utf8(topic)
        if qos:
            body += struct.pack("!H", self.packet_id)
        body += payload
        header = 0x30 | (qos << 1) | int(retained)
        self.connection.sendall(bytes([header]) + remaining_length(len(body)) + body)
        if qos:
            ack_header, ack = receive_packet(self.connection)
            check(ack_header == 0x40 and ack == struct.pack("!H", self.packet_id),
                  f"Missing/mismatched broker PUBACK: {ack_header}, {ack!r}")

    def __exit__(self, kind, value, trace):
        if self.connection:
            try:
                self.connection.sendall(b"\xe0\x00")
            except OSError:
                pass
            self.connection.close()


class ComposeDriver:
    def __init__(self, args):
        self.command = ["docker", "compose", "-p", args.project]
        for file in args.compose_file:
            self.command += ["-f", str(ROOT / file)]

    def run(self, *args, timeout=90, accepted_exit_codes=(0,)):
        result = subprocess.run(self.command + list(args), cwd=ROOT, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if result.returncode not in accepted_exit_codes:
            raise RuntimeError(f"{' '.join(self.command + list(args))} failed ({result.returncode}): "
                               f"{result.stdout}\n{result.stderr}")
        return result.stdout.strip()

    def sql(self, query):
        return self.run("exec", "-T", "postgres", "psql", "-X", "-v", "ON_ERROR_STOP=1",
                        "-U", "fleet", "-d", "fleet", "-At", "-c", query)

    def start(self, *services):
        self.run("start", *services)

    def stop(self, *services):
        self.run("stop", "-t", "3", *services)

    def state(self, service):
        container_id = self.run("ps", "-aq", service)
        check(bool(container_id), f"Missing {service} container")
        result = subprocess.run(["docker", "inspect", "--format", "{{json .State}}", container_id],
                                check=True, capture_output=True, text=True, timeout=10)
        state = json.loads(result.stdout)
        return {"running": state["Running"], "exit_code": state["ExitCode"]}

    def broker_config(self):
        return self.run("exec", "-T", "mosquitto", "cat", "/mosquitto/config/mosquitto.conf")

    def versions(self):
        return {"java": self.run("exec", "-T", "app", "java", "--version"),
                "mosquitto": self.run("exec", "-T", "mosquitto", "mosquitto", "-h",
                                      accepted_exit_codes=(0, 3)).splitlines()[0],
                "redis": self.run("exec", "-T", "redis", "redis-server", "--version"),
                "postgresql": self.run("exec", "-T", "postgres", "postgres", "--version")}

    def redis(self, *args):
        return self.run("exec", "-T", "redis", "redis-cli", "--raw", *args)

    def logs(self):
        return self.run("logs", "--no-color", "--tail", "300", timeout=30)


class ExternalDriver:
    """Optional real-process controller, never an in-memory broker/DB substitute.

    --control-command is a shell-split executable/arguments (no shell is executed).
    Requests append one of: sql SQL | start SERVICE... | stop SERVICE... |
    state SERVICE | broker-config | redis ARGS... | versions | logs. Successful calls exit 0.
    sql returns psql -At output; state returns {running:bool,exit_code:int}; versions
    returns a JSON object of actual runtime version strings; other
    read calls return text. Service names and purpose tests are identical to Compose.
    The controller must retain DB/broker data across stops and report real exits.
    """

    def __init__(self, command):
        self.command = shlex.split(command)
        check(bool(self.command), "Empty external control command")

    def run(self, *args):
        result = subprocess.run(self.command + list(args), cwd=ROOT, text=True,
                                capture_output=True, timeout=90)
        if result.returncode:
            raise RuntimeError(f"Native controller {args[0]} failed ({result.returncode}): "
                               f"{result.stdout}\n{result.stderr}")
        return result.stdout.strip()

    def sql(self, query): return self.run("sql", query)
    def start(self, *services): self.run("start", *services)
    def stop(self, *services): self.run("stop", *services)
    def state(self, service): return json.loads(self.run("state", service))
    def broker_config(self): return self.run("broker-config")
    def versions(self): return json.loads(self.run("versions"))
    def redis(self, *args): return self.run("redis", *args)
    def logs(self): return self.run("logs")


class RecoverySuite:
    def __init__(self, args, report):
        self.args, self.report = args, report
        self.driver = ExternalDriver(args.control_command) if args.control_command else ComposeDriver(args)
        self.namespace = uuid.uuid4()
        self.prefix = "mqtt-" + self.namespace.hex[:10] + "-"
        self.expected = {}
        self.lock = threading.Lock()
        self.queue_messages = 0
        self.queue_bytes = 0

    def http(self, path, raw=False):
        with urllib.request.urlopen(self.args.base_url.rstrip("/") + path, timeout=5) as response:
            data = response.read().decode()
            return data if raw else json.loads(data)

    def metric(self, name, **labels):
        total = 0.0
        for line in self.http("/actuator/prometheus", raw=True).splitlines():
            if line.startswith("#") or not line:
                continue
            match = re.match(r"([^\s{]+)(?:\{([^}]*)\})?\s+(\S+)", line)
            if match and match.group(1) == name:
                actual = dict(re.findall(r'(\w+)="([^"]*)"', match.group(2) or ""))
                if all(actual.get(key) == value for key, value in labels.items()):
                    total += float(match.group(3))
        return total

    def ready(self):
        return eventually("healthy Spring and connected MQTT subscriber",
                          lambda: self.http("/actuator/health")["status"] == "UP"
                          and self.metric("fleet_mqtt_connected") == 1,
                          timeout=self.args.startup_timeout)

    def packet(self, robot, index, **changes):
        value = {"robotId": self.prefix + robot, "timestamp": BASE_TIME + index,
                 "x": index / 10.0, "y": 0.0, "battery": 90.0, "taskState": "moving",
                 "errorCodes": [], "modelId": "standard",
                 "eventId": str(uuid.uuid5(self.namespace, f"{robot}:{index}"))}
        value.update(changes)
        return value

    def publish(self, packet, *, remember=True, topic=None, retained=False, qos=1):
        body = json.dumps(packet, separators=(",", ":"), sort_keys=True).encode()
        with Publisher(self.args.broker_host, self.args.broker_port) as publisher:
            publisher.publish(topic or f"robots/{packet['robotId']}/telemetry", body, qos=qos, retained=retained)
        if remember:
            with self.lock:
                original = self.expected.setdefault(packet["eventId"], packet)
                check(original == packet, "Harness source ID was reused with different content")
        return body

    def rows(self):
        query = ("select coalesce(json_agg(t), '[]'::json)::text from "
                 "(select event_id::text as event_id, payload, response_json, severity, rules "
                 f"from telemetry_event where robot_id like {literal(self.prefix + '%')} "
                 "order by observed_at desc, id desc) t")
        return json.loads(self.driver.sql(query))

    def row(self, event_id):
        query = ("select row_to_json(t)::text from (select event_id::text as event_id, payload, "
                 "response_json, severity, rules from telemetry_event "
                 f"where event_id={literal(event_id)}::uuid) t")
        result = self.driver.sql(query)
        return json.loads(result) if result else None

    def wait_event(self, packet):
        return eventually("database event " + packet["eventId"], lambda: self.row(packet["eventId"]),
                          self.args.recovery_timeout)

    def exact_database(self):
        rows = self.rows()
        by_id = {row["event_id"]: row for row in rows}
        check(len(rows) == len(by_id) == len(self.expected),
              {"expected_count": len(self.expected), "database_count": len(rows)})
        check(set(by_id) == set(self.expected),
              {"missing_ids": sorted(set(self.expected) - set(by_id)),
               "unexpected_ids": sorted(set(by_id) - set(self.expected))})
        for event_id, packet in self.expected.items():
            row = by_id[event_id]
            check(row["payload"] == packet, {"payload_changed": event_id, "row": row})
            check(row["response_json"]["eventId"] == event_id, "Response changed source eventId")
            check(row["response_json"]["telemetry"] == packet, "Response telemetry differs from source")
            check(row["response_json"]["duplicate"] is False, "Replay mutated persisted decision")
        return rows

    def wait_all(self):
        expected_ids = set(self.expected)
        return eventually("all source IDs committed", lambda: set(r["event_id"] for r in self.rows())
                          >= expected_ids, self.args.recovery_timeout)

    def history(self, robot, packets):
        expected_ids = {packet["eventId"] for packet in packets}
        def current_history():
            current = self.http(f"/api/robots/{robot}/recent?limit=50")["items"]
            return current if {item["eventId"] for item in current} == expected_ids else None
        items = eventually("complete HTTP history for " + robot, current_history, self.args.recovery_timeout)
        check(len(items) == len(packets), {"robot": robot, "history": len(items), "source": len(packets)})
        check({item["eventId"] for item in items} == {packet["eventId"] for packet in packets},
              "History IDs do not exactly match source")
        check([item["telemetry"]["timestamp"] for item in items] ==
              sorted([packet["timestamp"] for packet in packets], reverse=True), "History not newest first")
        for item in items:
            check(item["telemetry"] == self.expected[item["eventId"]], "History changed source payload")
        return items

    def rejection(self, body, reason, topic):
        digest = hashlib.sha256(body).hexdigest()
        query = ("select coalesce(json_agg(t), '[]'::json)::text from (select reason, topic, "
                 "payload_sha256, payload_bytes, deliveries, event_id from mqtt_rejection "
                 f"where payload_sha256={literal(digest)} and topic={literal(topic)} "
                 f"and reason={literal(reason)}) t")
        rows = eventually(f"durable {reason} rejection", lambda: json.loads(self.driver.sql(query)),
                          self.args.recovery_timeout)
        check(len(rows) == 1, {"duplicate_rejection_ledger_rows": rows})
        row = rows[0]
        check(row["payload_bytes"] == len(body) and row["deliveries"] >= 1, row)
        return row

    def bounded_backlog(self, packets):
        total_bytes = sum(len(json.dumps(packet).encode()) for packet in packets)
        check(len(packets) < self.queue_messages, "Fixture exceeds declared broker queue message bound")
        check(total_bytes < self.queue_bytes, "Fixture exceeds declared broker queue byte bound")
        for packet in packets:
            self.publish(packet)
        return {"messages": len(packets), "payload_bytes": total_bytes,
                "broker_max_queued_messages": self.queue_messages,
                "broker_max_queued_bytes": self.queue_bytes}

    def test_contract(self):
        self.ready()
        self.report["runtime_versions"] = self.driver.versions()
        self.report["runtime_versions"]["python"] = sys.version.splitlines()[0]
        self.report["postgres_version"] = self.driver.sql("show server_version")
        config = self.driver.broker_config()
        self.report["broker_config_sha256"] = hashlib.sha256(config.encode()).hexdigest()
        options = {}
        for line in config.splitlines():
            fields = line.split("#", 1)[0].strip().split(maxsplit=1)
            if len(fields) == 2:
                options[fields[0]] = fields[1]
        check(options.get("persistence") == "true", "Broker persistence must be enabled")
        self.queue_messages = int(options.get("max_queued_messages", "0"))
        self.queue_bytes = int(options.get("max_queued_bytes", "0"))
        check(0 < self.queue_messages <= 1000, "Explicit bounded broker queue <=1000 required")
        check(0 < self.queue_bytes <= 64 * 1024 * 1024, "Explicit broker queue byte bound <=64MiB required")
        self.report["broker_bounds"] = {"messages": self.queue_messages, "bytes": self.queue_bytes,
                                        "persistence": True}
        fixtures = [self.packet("rules", 0), self.packet("rules", 1, x=20.0),
                    self.packet("rules", 2, x=20.1, battery=8.0, errorCodes=["E_VENDOR_UNKNOWN"])]
        expected_rules = [[], ["position_jump"],
                          ["battery_critical", "battery_drop_spike", "unknown_error:E_VENDOR_UNKNOWN"]]
        for packet, rules, severity in zip(fixtures, expected_rules, ["normal", "critical", "critical"]):
            self.publish(packet)
            row = self.wait_event(packet)
            check(row["rules"] == rules and row["severity"] == severity, row)
            check(row["response_json"]["rules"] == rules, row)
        self.exact_database()
        self.history(fixtures[0]["robotId"], fixtures)
        # A nonempty but incomplete cache must not hide committed operator history.
        ring_key = "fleet:ring:" + fixtures[0]["robotId"]
        self.driver.redis("DEL", ring_key)
        self.driver.redis("LPUSH", ring_key, json.dumps(self.row(fixtures[0]["eventId"])["response_json"]))
        check(self.driver.redis("LLEN", ring_key) == "1", "Failed to arrange nonempty stale Redis ring")
        self.history(fixtures[0]["robotId"], fixtures)
        return {"source_events": len(fixtures), "exact_rule_lists": expected_rules,
                "incomplete_nonempty_cache_cannot_hide_history": True}

    def test_duplicate_restart(self):
        original = self.packet("duplicate", 0)
        self.publish(original)
        before = self.wait_event(original)
        duplicate_before = self.metric("fleet_mqtt_dispositions_total", outcome="duplicate")
        self.publish(original)
        eventually("duplicate acknowledged", lambda: self.metric("fleet_mqtt_dispositions_total", outcome="duplicate")
                   > duplicate_before)
        self.driver.stop("app")
        check(not self.driver.state("app")["running"], "Subscriber did not stop")
        self.publish(original)
        queued = self.packet("duplicate", 1)
        self.publish(queued)
        self.driver.start("app")
        self.ready()
        self.wait_event(queued)
        eventually("offline duplicate replayed after restart",
                   lambda: self.metric("fleet_mqtt_dispositions_total", outcome="duplicate") >= 1)
        check(self.row(original["eventId"]) == before, "Duplicate changed stored decision")
        self.exact_database()
        self.history(original["robotId"], [original, queued])
        return {"duplicate_deliveries": 2, "database_effects_for_original": 1}

    def test_conflict(self):
        original = self.packet("conflict", 0)
        self.publish(original)
        before = self.wait_event(original)
        altered = dict(original, battery=12.0)
        topic = f"robots/{original['robotId']}/telemetry"
        payload = self.publish(altered, remember=False)
        row = self.rejection(payload, "conflict", topic)
        self.publish(altered, remember=False)
        eventually("repeat conflict delivery audited", lambda: self.rejection(payload, "conflict", topic)["deliveries"]
                   > row["deliveries"])
        check(self.row(original["eventId"]) == before, "Conflict overwrote original payload or verdict")
        self.exact_database()
        return {"original_preserved": True, "rejection_reason": "conflict", "repeated_rejection_audited": True}

    def test_commit_before_ack_crash(self):
        packet = self.packet("commit-crash", 0, eventId=FAULT_EVENT_ID)
        self.publish(packet)
        state = eventually("test subscriber halts before ACK", lambda: (s if not s["running"] else None)
                           if (s := self.driver.state("app")) else None,
                           self.args.recovery_timeout)
        check(state["exit_code"] == 86, {"expected_exit": 86, "actual": state})
        check(self.row(FAULT_EVENT_ID) is not None, "Subscriber crashed before database commit")
        recovery_start = time.monotonic()
        self.driver.start("app")
        self.ready()
        eventually("broker redelivers committed, unacknowledged event",
                   lambda: self.metric("fleet_mqtt_dispositions_total", outcome="duplicate") >= 1,
                   self.args.recovery_timeout)
        self.exact_database()
        recovery = time.monotonic() - recovery_start
        check(recovery <= self.args.recovery_timeout, "Commit/ACK crash recovery exceeded declared target")
        return {"exit_code": 86, "committed_before_crash": True, "redelivery_verified": True,
                "database_effects": 1, "recovery_seconds": round(recovery, 3)}

    def test_database_outage(self):
        retry_before = self.metric("fleet_mqtt_retry_failures_total")
        self.driver.stop("postgres")
        check(not self.driver.state("postgres")["running"], "PostgreSQL did not stop")
        first = self.packet("db-outage", 0)
        self.publish(first)
        eventually("database failure must leave delivery unacknowledged",
                   lambda: self.metric("fleet_mqtt_retry_failures_total") > retry_before,
                   self.args.recovery_timeout)
        packets = [self.packet("db-outage", index) for index in range(1, 24)]
        bounds = self.bounded_backlog(packets)
        check(self.driver.state("app")["running"], "Subscriber died during database outage")
        recovery_start = time.monotonic()
        self.driver.start("postgres")
        self.wait_all()
        recovery = time.monotonic() - recovery_start
        self.ready()
        self.exact_database()
        self.history(first["robotId"], [first] + packets)
        check(recovery <= self.args.recovery_timeout, "Database recovery exceeded declared target")
        return dict(bounds, in_flight=1, recovery_seconds=round(recovery, 3),
                    exact_recovered_events=len(packets) + 1)

    def test_broker_persistence(self):
        self.driver.stop("app")
        packets = [self.packet("broker-restart", index) for index in range(12)]
        bounds = self.bounded_backlog(packets)
        self.driver.stop("mosquitto")
        check(not self.driver.state("mosquitto")["running"], "Broker did not stop")
        recovery_start = time.monotonic()
        self.driver.start("mosquitto")
        self.driver.start("app")
        self.ready()
        self.wait_all()
        self.exact_database()
        self.history(packets[0]["robotId"], packets)
        recovery = time.monotonic() - recovery_start
        check(recovery <= self.args.recovery_timeout, "Broker persistence recovery exceeded declared target")
        # Also prove the already-running subscriber reconnects without an application restart.
        self.driver.stop("mosquitto")
        eventually("live subscriber observes broker disconnect", lambda: self.metric("fleet_mqtt_connected") == 0)
        reconnect_start = time.monotonic()
        self.driver.start("mosquitto")
        self.ready()
        sentinel = self.packet("broker-live-reconnect", 0)
        self.publish(sentinel)
        self.wait_event(sentinel)
        self.exact_database()
        reconnect = time.monotonic() - reconnect_start
        check(reconnect <= self.args.recovery_timeout, "Live broker reconnect exceeded declared target")
        return dict(bounds, graceful_restart=True, exact_recovered_events=len(packets),
                    recovery_seconds=round(recovery, 3), live_subscriber_reconnected=True,
                    live_reconnect_seconds=round(reconnect, 3))

    def test_ordering_and_poison(self):
        latest = self.packet("ordering", 10, x=10.0)
        stale = self.packet("ordering", 5, x=0.0)
        next_packet = self.packet("ordering", 11, x=10.1)
        for packet in [latest, stale]:
            accepted_before = self.metric("fleet_mqtt_dispositions_total", outcome="accepted")
            self.publish(packet)
            self.wait_event(packet)
            eventually("completed disposition before snapshot assertion",
                       lambda: self.metric("fleet_mqtt_dispositions_total", outcome="accepted") > accepted_before)
        check(self.row(stale["eventId"])["rules"] == ["position_jump", "out_of_order_timestamp"],
              "Out-of-order observation not retained with exact rules")
        snapshot = json.loads(self.driver.redis("GET", "fleet:last:" + latest["robotId"]))
        check(snapshot == latest, "Out-of-order packet overwrote latest snapshot")
        accepted_before = self.metric("fleet_mqtt_dispositions_total", outcome="accepted")
        self.publish(next_packet)
        self.wait_event(next_packet)
        eventually("next packet cache update complete",
                   lambda: self.metric("fleet_mqtt_dispositions_total", outcome="accepted") > accepted_before)
        check(self.row(next_packet["eventId"])["rules"] == [],
              "Stale packet incorrectly replaced latest observation for rule evaluation")
        snapshot = json.loads(self.driver.redis("GET", "fleet:last:" + latest["robotId"]))
        check(snapshot == next_packet, "Redis snapshot is not the latest observation")
        self.history(latest["robotId"], [latest, stale, next_packet])

        rejected = []
        rejection_evidence = []
        malformed = self.packet("invalid", 0)
        topic = f"robots/{malformed['robotId']}/telemetry"
        unknown = dict(malformed, unexpected="field")
        missing = {key: value for key, value in malformed.items() if key != "eventId"}
        invalid = dict(malformed, battery=101)
        invalid_ids = [json.dumps(dict(malformed, eventId=value)).encode()
                       for value in ("\x00", "\ud800")]
        invalid_robot = json.dumps(dict(malformed, robotId="\x00")).encode()
        invalid_errors = json.dumps(dict(malformed, errorCodes=["E_\x00"])).encode()
        control_cases = {invalid_ids[0]: "eventId_NUL", invalid_ids[1]: "eventId_unpaired_surrogate",
                         invalid_robot: "robotId_NUL", invalid_errors: "errorCodes_NUL"}
        cases = [
            (topic, b'{"robotId":', "invalid_json", 1, False),
            (topic, b"{" + b" " * self.args.payload_limit + b"}", "oversized", 1, False),
            (f"robots/{self.prefix}wrong-topic/telemetry", json.dumps(malformed).encode(),
             "topic_robot_mismatch", 1, False),
            (topic, json.dumps(unknown).encode(), "invalid_json", 1, False),
            (topic, json.dumps(missing).encode(), "missing_event_id", 1, False),
            (topic, json.dumps(invalid).encode(), "validation", 1, False),
            (topic, invalid_ids[0], "validation", 1, False),
            (topic, invalid_ids[1], "validation", 1, False),
            (topic, invalid_robot, "validation", 1, False),
            (topic, invalid_errors, "validation", 1, False),
            (topic, json.dumps(malformed).encode(), "retained", 1, True),
            (topic, json.dumps(malformed).encode(), "qos", 0, False),
        ]
        for case_topic, body, reason, qos, retained in cases:
            with Publisher(self.args.broker_host, self.args.broker_port) as publisher:
                publisher.publish(case_topic, body, qos=qos, retained=retained)
            rejection = self.rejection(body, reason, case_topic)
            if body in invalid_ids:
                check(rejection["event_id"] is None, "Invalid UUID must not enter PostgreSQL audit text")
            if body in (invalid_robot, invalid_errors):
                check(rejection["event_id"] == malformed["eventId"], "Valid source UUID lost from rejection audit")
            rejected.append(reason)
            rejection_evidence.append(dict(rejection, case=control_cases.get(body, reason)))
        # Clear the broker's retained poison so the test leaves no replay landmine.
        with Publisher(self.args.broker_host, self.args.broker_port) as publisher:
            publisher.publish(topic, b"", retained=True)
        sentinel = self.packet("poison-survivor", 0)
        self.publish(sentinel)
        self.wait_event(sentinel)
        self.exact_database()
        return {"out_of_order_preserved": True, "latest_snapshot_preserved": True,
                "durable_rejection_reasons": rejected, "durable_rejections": rejection_evidence,
                "control_character_validation_cases": list(control_cases.values()),
                "invalid_uuid_audit_ids_null": len(invalid_ids), "valid_after_poison": True}

    def test_bounded_fleet(self):
        robot_count, per_robot = self.args.robots, self.args.events_per_robot
        fixtures = [[self.packet(f"fleet-{robot}", index) for index in range(per_robot)]
                    for robot in range(robot_count)]
        check(robot_count * per_robot < self.queue_messages, "Workload exceeds queue message bound")
        check(sum(len(json.dumps(packet).encode()) for group in fixtures for packet in group)
              < self.queue_bytes, "Workload exceeds queue byte bound")
        sent, observed = {}, {}
        target_ids = {packet["eventId"] for group in fixtures for packet in group}
        workload_start = time.monotonic()

        def publish_robot(group):
            with Publisher(self.args.broker_host, self.args.broker_port) as publisher:
                for packet in group:
                    body = json.dumps(packet, sort_keys=True, separators=(",", ":")).encode()
                    with self.lock:
                        sent[packet["eventId"]] = time.monotonic()
                        self.expected[packet["eventId"]] = packet
                    publisher.publish(f"robots/{packet['robotId']}/telemetry", body)

        with concurrent.futures.ThreadPoolExecutor(max_workers=robot_count) as pool:
            futures = [pool.submit(publish_robot, group) for group in fixtures]
            deadline = time.monotonic() + self.args.recovery_timeout
            while set(observed) != target_ids:
                seen = {row["event_id"] for row in self.rows()} & target_ids
                now = time.monotonic()
                for event_id in seen:
                    observed.setdefault(event_id, now)
                for future in futures:
                    if future.done():
                        future.result()
                check(now < deadline, {"workload_missing_ids": sorted(target_ids - set(observed))})
                if set(observed) != target_ids:
                    time.sleep(0.1)
            for future in futures:
                future.result()
        latencies = sorted(observed[event_id] - sent[event_id] for event_id in target_ids)
        p95 = latencies[math.ceil(len(latencies) * 0.95) - 1]
        check(p95 <= self.args.latency_target, {"p95_seconds": p95, "target_seconds": self.args.latency_target})
        self.exact_database()
        for group in fixtures:
            items = self.history(group[0]["robotId"], group)
            check(all(item["rules"] == [] and item["severity"] == "normal" for item in items),
                  "Nominal fleet rule outcome mismatch")
        return {"robots": robot_count, "source_unique_events": len(target_ids),
                "database_unique_events": len(observed), "publisher_pubacks": len(target_ids),
                "duration_seconds": round(time.monotonic() - workload_start, 3),
                "latency_observation": "publish start to first SQL observation, including polling/control overhead",
                "latency_p50_seconds": round(latencies[len(latencies) // 2], 4),
                "latency_p95_seconds": round(p95, 4), "latency_max_seconds": round(max(latencies), 4),
                "p95_target_seconds": self.args.latency_target}

    def run(self):
        for name, test in [
            ("mqtt_to_database_and_rules", self.test_contract),
            ("idempotency_across_subscriber_restart", self.test_duplicate_restart),
            ("conflict_original_preserved", self.test_conflict),
            ("crash_after_commit_before_ack", self.test_commit_before_ack_crash),
            ("database_outage_bounded_backlog", self.test_database_outage),
            ("graceful_broker_persistence_restart", self.test_broker_persistence),
            ("ordering_and_poison_isolation", self.test_ordering_and_poison),
            ("bounded_multirobot_workload", self.test_bounded_fleet),
        ]:
            start = time.monotonic()
            entry = {"name": name, "status": "running"}
            self.report["scenarios"].append(entry)
            write_report(self.args.report, self.report)
            try:
                entry["evidence"] = test()
                entry["status"] = "passed"
                print(f"PASS {name}", flush=True)
            except Exception as error:
                entry["status"] = "failed"
                entry["error"] = str(error)
                raise
            finally:
                entry["duration_seconds"] = round(time.monotonic() - start, 3)
                write_report(self.args.report, self.report)
        rows = self.exact_database()
        self.report["source_unique_events"] = len(self.expected)
        self.report["database_unique_events"] = len(rows)
        self.report["source_event_ids"] = sorted(self.expected)
        self.report["database_event_ids"] = sorted(row["event_id"] for row in rows)


def write_report(destination: Path, value):
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    temporary.replace(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose-file", action="append", default=None)
    parser.add_argument("--project", default=os.environ.get("COMPOSE_PROJECT_NAME", "robot-mqtt-purpose"))
    parser.add_argument("--control-command", help="Real native-process controller; see ExternalDriver protocol")
    parser.add_argument("--base-url", default="http://127.0.0.1:8803")
    parser.add_argument("--broker-host", default="127.0.0.1")
    parser.add_argument("--broker-port", type=int, default=1883)
    parser.add_argument("--report", type=Path, default=ROOT / "artifacts/mqtt-purpose.json")
    parser.add_argument("--payload-limit", type=int, default=16384)
    parser.add_argument("--recovery-timeout", type=float, default=60)
    parser.add_argument("--startup-timeout", type=float, default=120)
    parser.add_argument("--latency-target", type=float, default=10)
    parser.add_argument("--robots", type=int, default=4)
    parser.add_argument("--events-per-robot", type=int, default=20)
    args = parser.parse_args()
    args.compose_file = args.compose_file or ["compose.mqtt.yaml"]
    check(1 <= args.robots <= 16 and 1 <= args.events_per_robot <= 50, "Bound workload to 1–16 robots x1–50 events")
    check(args.recovery_timeout > 0 and args.latency_target > 0, "Positive recovery/latency budgets required")
    report = {"schema_version": "1.0", "status": "running", "real_processes_required": True,
              "driver": "external-processes" if args.control_command else "docker-compose",
              "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "recovery_target_seconds": args.recovery_timeout, "scenarios": []}
    write_report(args.report, report)
    suite = RecoverySuite(args, report)
    try:
        suite.run()
        report["status"] = "passed"
    except Exception as error:
        report["status"] = "failed"
        report["error"] = f"{type(error).__name__}: {error}"
        traceback.print_exc()
        try:
            log_path = args.report.with_suffix(".log")
            log_path.write_text(suite.driver.logs())
            report["diagnostic_log"] = str(log_path)
        except Exception as log_error:
            report["diagnostic_error"] = str(log_error)
    finally:
        # Keep publisher truth even for a failed run, so missing data can be investigated.
        source_path = args.report.with_name(args.report.stem + "-source.jsonl")
        source_path.write_text("".join(json.dumps(packet, sort_keys=True) + "\n"
                                      for _, packet in sorted(suite.expected.items())))
        report["source_fixture"] = str(source_path)
        report["source_unique_events"] = len(suite.expected)
        report["completed_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        write_report(args.report, report)
    print(json.dumps({"status": report["status"], "report": str(args.report)}, sort_keys=True))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
