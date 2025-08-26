"use client";

import { BackfillOverallStatus } from "@/generated/api";

export const BACKFILL_SCENARIOS: Record<string, BackfillOverallStatus> = {
  notStarted: {
    status: "Pending",
    percentage_completed: 0,
    eta_ms: undefined,
    started: undefined,
    finished: undefined,
    shard_total: undefined,
    shard_complete: undefined,
    shard_in_progress: undefined,
    shard_waiting: undefined,
  },
  inProgress: {
    status: "Running",
    percentage_completed: 45,
    eta_ms: 1800000, // 30 minutes
    started: new Date(Date.now() - 600000), // 10 minutes ago
    finished: undefined,
    shard_total: 100,
    shard_complete: 45,
    shard_in_progress: 8,
    shard_waiting: 45,
  },
  nearCompletion: {
    status: "Running",
    percentage_completed: 95,
    eta_ms: 300000, // 5 minutes
    started: new Date(Date.now() - 3600000), // 1 hour ago
    finished: undefined,
    shard_total: 200,
    shard_complete: 190,
    shard_in_progress: 5,
    shard_waiting: 4,
  },
  completed: {
    status: "Completed",
    percentage_completed: 100,
    eta_ms: null,
    started: new Date(Date.now() - 7200000), // 2 hours ago
    finished: new Date(Date.now() - 300000), // 5 minutes ago
    shard_total: 150,
    shard_complete: 150,
    shard_in_progress: 0,
    shard_waiting: 0,
  },
  completedWithFailures: {
    status: "Completed",
    percentage_completed: 100,
    eta_ms: null,
    started: new Date(Date.now() - 5400000), // 1.5 hours ago
    finished: new Date(Date.now() - 180000), // 3 minutes ago
    shard_total: 75,
    shard_complete: 75,
    shard_in_progress: 0,
    shard_waiting: 0,
  },
  failed: {
    status: "Failed",
    percentage_completed: 25,
    eta_ms: null,
    started: new Date(Date.now() - 1800000), // 30 minutes ago
    finished: new Date(Date.now() - 900000), // 15 minutes ago
    shard_total: 50,
    shard_complete: 12,
    shard_in_progress: 0,
    shard_waiting: 0,
  },
  pausedNearCompletion: {
    status: "Paused",
    percentage_completed: 95,
    eta_ms: null,
    started: new Date(Date.now() - 3600000), // 1 hour ago
    finished: undefined,
    shard_total: 200,
    shard_complete: 190,
    shard_in_progress: 0,
    shard_waiting: 10,
  },
};
