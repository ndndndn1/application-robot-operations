import React, {useState} from "react";
import {createRoot} from "react-dom/client";

function App() {
  const [robotId, setRobotId] = useState("robot-1");
  const [output, setOutput] = useState("No telemetry sent");
  async function send() {
    const body = {robotId, timestamp: Date.now()/1000, x: 1, y: 2, battery: 72, taskState: "moving", errorCodes: [], modelId: "compact"};
    const response = await fetch("/api/telemetry", {method:"POST", headers:{"content-type":"application/json"}, body:JSON.stringify(body)});
    setOutput(JSON.stringify(await response.json(), null, 2));
  }
  return <main><h1>Robot fleet telemetry</h1><label>Robot <input value={robotId} onChange={event=>setRobotId(event.target.value)}/></label><button onClick={send}>Send packet</button><pre>{output}</pre></main>;
}
createRoot(document.getElementById("root")!).render(<App/>);
