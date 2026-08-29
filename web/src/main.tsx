import React, {useState} from "react";
import {createRoot} from "react-dom/client";
import "./styles.css";

type Result = {eventId: string; severity: string; rules: string[]; duplicate: boolean};

function App() {
  const [robotId, setRobotId] = useState("mh-01-a");
  const [modelId, setModelId] = useState("standard");
  const [taskState, setTaskState] = useState("moving");
  const [battery, setBattery] = useState(72);
  const [x, setX] = useState(1);
  const [y, setY] = useState(2);
  const [output, setOutput] = useState("No request sent.");
  const [busy, setBusy] = useState(false);
  const [lastCommandId, setLastCommandId] = useState("");

  async function call(path: string, options?: RequestInit) {
    setBusy(true);
    try {
      const response = await fetch(path, options);
      const data: unknown = await response.json();
      setOutput(JSON.stringify(data, null, 2));
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return data;
    } catch (error) {
      if (output === "No request sent.") setOutput(String(error));
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function send() {
    const body = {robotId, timestamp: Date.now() / 1000, x, y, battery, taskState,
      errorCodes: [], modelId, eventId: crypto.randomUUID()};
    await call("/api/telemetry", {method: "POST", headers: {"content-type": "application/json"},
      body: JSON.stringify(body)});
  }

  async function recent() {
    await call(`/api/robots/${encodeURIComponent(robotId)}/recent?limit=20`);
  }

  async function command(type: "navigate" | "protective_stop") {
    const label = type === "navigate" ? "mock robot navigation" : "software protective stop";
    if (!window.confirm(`Submit ${label} to ${robotId}? This console is in MOCK mode.`)) return;
    const commandId = crypto.randomUUID();
    const issuedAt = new Date();
    const expiresAt = new Date(issuedAt.getTime() + 60_000);
    const action = type === "navigate"
      ? {type, target: {x_m: x, y_m: y, yaw_rad: 0, frame: "map"}, max_speed_mps: 0.5}
      : {type, reason: "operator requested protective stop"};
    const result = await call("/api/v1/commands", {method: "POST",
      headers: {"content-type": "application/json"}, body: JSON.stringify({
        contract_version: "1.0.0", command_id: commandId, robot_id: robotId,
        issued_at: issuedAt.toISOString(), expires_at: expiresAt.toISOString(),
        expected_state_version: 0, action,
      })});
    if (result) setLastCommandId(commandId);
  }

  async function commandStatus() {
    if (lastCommandId) await call(`/api/v1/commands/${encodeURIComponent(lastCommandId)}`);
  }

  return <main>
    <header><div className="heading"><div><p className="eyebrow">Operations reference console</p>
      <h1>Robot operations</h1></div><span className="mode">Mock target</span></div>
      <p>Inspect fleet telemetry and exercise the safe command lifecycle against replaceable robot adapters.</p>
      <p className="notice">Software protective stop is not a certified hardware emergency-stop circuit.</p></header>
    <section className="panel form" aria-label="Telemetry packet">
      <label>Robot ID<input value={robotId} onChange={event => setRobotId(event.target.value)}/></label>
      <label>Profile<select value={modelId} onChange={event => setModelId(event.target.value)}>
        <option>standard</option><option>compact</option><option>heavy</option></select></label>
      <label>Task state<select value={taskState} onChange={event => setTaskState(event.target.value)}>
        <option>idle</option><option>moving</option><option>docking</option><option>charging</option><option>error</option>
      </select></label>
      <label>Battery<input type="number" min="0" max="100" value={battery}
        onChange={event => setBattery(event.target.valueAsNumber)}/></label>
      <label>X<input type="number" value={x} onChange={event => setX(event.target.valueAsNumber)}/></label>
      <label>Y<input type="number" value={y} onChange={event => setY(event.target.valueAsNumber)}/></label>
      <div className="actions"><button disabled={busy} onClick={send}>Send packet</button>
        <button className="secondary" disabled={busy} onClick={recent}>Load recent</button></div>
    </section>
    <section className="panel" aria-label="Mock robot commands"><h2>Mock command lifecycle</h2>
      <p className="hint">Commands expire after 60 seconds and require confirmation. X/Y are used as the navigation target.</p>
      <div className="actions"><button disabled={busy} onClick={() => command("navigate")}>Navigate mock</button>
        <button className="danger" disabled={busy} onClick={() => command("protective_stop")}>Protective stop</button>
        <button className="secondary" disabled={busy || !lastCommandId} onClick={commandStatus}>Load command status</button></div>
      {lastCommandId && <p className="command-id">Last command: <code>{lastCommandId}</code></p>}
    </section>
    <section className="panel"><h2>API response</h2><pre aria-live="polite">{output}</pre></section>
  </main>;
}
createRoot(document.getElementById("root")!).render(<App/>);
