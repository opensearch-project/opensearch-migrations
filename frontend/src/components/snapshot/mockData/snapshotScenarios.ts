import { SnapshotStatus, StepState } from "@/generated/api/types.gen";

// Debug scenario mock data
export const SNAPSHOT_SCENARIOS: Record<string, SnapshotStatus> = {
  notStarted: {
    status: "Pending" as StepState,
    percentage_completed: 0,
    eta_ms: null,
    started: undefined,
    finished: undefined,
  },
  inProgress: {
    status: "Running" as StepState,
    percentage_completed: 25,
    eta_ms: 7200000, // 2 hours in milliseconds
    started: new Date(Date.now() - 1800000), // Started 30 minutes ago
    finished: undefined,
  },
  almostDone: {
    status: "Running" as StepState,
    percentage_completed: 99,
    eta_ms: 60000, // 1 minute in milliseconds
    started: new Date(Date.now() - 3600000), // Started 1 hour ago
    finished: undefined,
  },
  completed: {
    status: "Completed" as StepState,
    percentage_completed: 100,
    eta_ms: null,
    started: new Date(Date.now() - 3600000), // Started 1 hour ago
    finished: new Date(), // Just finished
  },
  failed: {
    status: "Failed" as StepState,
    percentage_completed: 45,
    eta_ms: null,
    started: new Date(Date.now() - 1800000), // Started 30 minutes ago
    finished: new Date(Date.now() - 600000), // Failed 10 minutes ago
  },
};
