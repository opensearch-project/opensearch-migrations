import { useQuery } from "@tanstack/react-query";
import { Activity, CircleAlert, LoaderCircle } from "lucide-react";

import { getHealth } from "../api/client";


export function App() {
  const health = useQuery({
    queryKey: ["system-health"],
    queryFn: getHealth,
    staleTime: 30_000,
  });

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand-mark" aria-hidden="true">
          <Activity />
        </div>
        <div>
          <h1>Workflow Manage</h1>
          <p>Migration orchestration</p>
        </div>
        <div className="server-state" aria-live="polite">
          {health.isPending ? (
            <>
              <LoaderCircle className="spin" aria-hidden="true" />
              Connecting to server
            </>
          ) : health.isError ? (
            <>
              <CircleAlert aria-hidden="true" />
              Server unavailable
            </>
          ) : (
            <>
              <span className="live-dot" aria-hidden="true" />
              Server ready
            </>
          )}
        </div>
      </header>
      <main className="shell-loading">
        <LoaderCircle className="spin" aria-hidden="true" />
        <strong>Loading workflow state</strong>
      </main>
    </div>
  );
}
