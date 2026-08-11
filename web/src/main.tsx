import React, {useState} from "react";
import {createRoot} from "react-dom/client";
import "./styles.css";

type Result = {eventId: string; severity: string; rules: string[]; duplicate: boolean};

function App() {
  const [robotId, setRobotId] = useState("robot-1");
  const [modelId, setModelId] = useState("standard");
  const [taskState, setTaskState] = useState("moving");
  const [battery, setBattery] = useState(72);
  const [x, setX] = useState(1);
  const [y, setY] = useState(2);
  const [output, setOutput] = useState("No request sent.");
  const [busy, setBusy] = useState(false);

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

  return <main>
    <header><p className="eyebrow">Operations reference console</p><h1>Robot fleet telemetry</h1>
      <p>Validate, classify, and inspect telemetry without issuing robot-control commands.</p></header>
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
    <section className="panel"><h2>API response</h2><pre aria-live="polite">{output}</pre></section>
  </main>;
}
createRoot(document.getElementById("root")!).render(<App/>);
