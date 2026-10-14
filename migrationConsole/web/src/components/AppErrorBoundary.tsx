import { Component, type ReactNode } from "react";
import { TriangleAlert } from "lucide-react";


interface AppErrorBoundaryState {
  error: Error | null;
}


export class AppErrorBoundary extends Component<
  Readonly<{ children: ReactNode }>,
  AppErrorBoundaryState
> {
  state: AppErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): AppErrorBoundaryState {
    return { error };
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="app-shell">
        <div className="shell-error" role="alert">
          <TriangleAlert aria-hidden="true" />
          <h2>Something went wrong</h2>
          <p>{this.state.error.message}</p>
          <button
            onClick={() => globalThis.location.reload()}
            type="button"
          >
            Reload Workflow Manage
          </button>
        </div>
      </div>
    );
  }
}
