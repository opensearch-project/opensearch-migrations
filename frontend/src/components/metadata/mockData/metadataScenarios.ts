"use client";

import { MetadataStatus } from "@/generated/api";

export const METADATA_SCENARIOS: Record<string, MetadataStatus> = {
  notStarted: {
    status: "Pending",
    started: undefined,
    finished: undefined,
    clusters: undefined,
    items: undefined,
    transformations: undefined,
    errors: undefined,
    errorCount: undefined,
    errorCode: undefined,
  },
  inProgress: {
    status: "Running",
    started: new Date(Date.now() - 300000),
    finished: undefined,
    clusters: undefined,
    items: undefined,
    transformations: undefined,
    errors: undefined,
    errorCount: undefined,
    errorCode: undefined,
  },
  completedEmpty: {
    status: "Completed",
    started: new Date(Date.now() - 600000),
    finished: new Date(),
    clusters: {
      source: {
        type: "Snapshot",
        version: "ELASTICSEARCH 7.10.0",
      },
      target: {
        type: "Remote Cluster",
        version: "OPENSEARCH 2.11.0",
      },
    },
    items: {
      dryRun: false,
      indexTemplates: [],
      componentTemplates: [],
      indexes: [],
      aliases: [],
    },
    transformations: undefined,
    errors: undefined,
    errorCount: 0,
    errorCode: undefined,
  },
  completedWithData: {
    status: "Completed",
    started: new Date(Date.now() - 1200000),
    finished: new Date(),
    clusters: {
      source: {
        type: "Snapshot",
        version: "ELASTICSEARCH 5.6.0",
      },
      target: {
        type: "Remote Cluster",
        version: "OPENSEARCH 2.19.1",
      },
    },
    items: {
      dryRun: false,
      indexTemplates: [
        { name: "template1", successful: true },
        { name: "template2", successful: false },
      ],
      componentTemplates: [{ name: "component1", successful: true }],
      indexes: [
        { name: "index1", successful: true },
        { name: "index2", successful: true },
        { name: "index3", successful: false },
      ],
      aliases: [{ name: "alias1", successful: true }],
    },
    transformations: undefined,
    errors: undefined,
    errorCount: 0,
    errorCode: undefined,
  },
  failed: {
    status: "Failed",
    started: new Date(Date.now() - 600000),
    finished: new Date(Date.now() - 300000),
    clusters: {
      source: {
        type: "Snapshot",
        version: "ELASTICSEARCH 5.6.0",
      },
      target: {
        type: "Remote Cluster",
        version: "OPENSEARCH 2.19.1",
      },
    },
    items: undefined,
    transformations: undefined,
    errors: ["Connection failed", "Authentication error"],
    errorCount: 2,
    errorCode: 500,
  },
};
