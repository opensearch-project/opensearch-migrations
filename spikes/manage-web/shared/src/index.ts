export type NodeKind =
  | "section"
  | "group"
  | "resource"
  | "config-group"
  | "config-field"
  | "diagnostic";

export type NodeSeverity =
  | "normal"
  | "changed"
  | "running"
  | "warning"
  | "blocked"
  | "error";

export type ConfigControl =
  | {
      kind: "text";
      value: string;
      placeholder?: string;
    }
  | {
      kind: "number";
      value: number;
      minimum?: number;
      maximum?: number;
    }
  | {
      kind: "boolean";
      value: boolean;
    }
  | {
      kind: "enum";
      value: string;
      options: ReadonlyArray<{ label: string; value: string }>;
    }
  | {
      kind: "config-map-key";
      configMap: string;
      key: string;
      options: ReadonlyArray<{
        name: string;
        keys: ReadonlyArray<string>;
      }>;
    };

export interface TreeNode {
  id: string;
  parentId?: string;
  childIds: ReadonlyArray<string>;
  kind: NodeKind;
  label: string;
  description?: string;
  severity: NodeSeverity;
  phase?: string;
  valueSummary?: string;
  diagnostic?: string;
  configControl?: ConfigControl;
  capabilities?: ReadonlyArray<"edit" | "approve" | "reset" | "logs" | "output">;
}

export interface ManageTreeState {
  revision: number;
  mode: "inspect" | "edit";
  rootIds: ReadonlyArray<string>;
  nodes: Readonly<Record<string, TreeNode>>;
}

export type TreePatch =
  | {
      type: "insert";
      parentId: string;
      index?: number;
      nodes: ReadonlyArray<TreeNode>;
      announce: string;
    }
  | {
      type: "update";
      nodeId: string;
      changes: Partial<Omit<TreeNode, "id" | "parentId" | "childIds">>;
      announce: string;
    }
  | {
      type: "remove";
      nodeId: string;
      announce: string;
    }
  | {
      type: "set-mode";
      mode: ManageTreeState["mode"];
      announce: string;
    };

export interface OperationState {
  id: string;
  label: string;
  phase: string;
  state: "running" | "waiting" | "succeeded";
  progress: number;
}

export const DEFAULT_EXPANDED_IDS: ReadonlyArray<string> = [
  "section-live",
  "group-capture",
  "group-replay",
];

function resourceNodes(): ReadonlyArray<TreeNode> {
  return [
    {
      id: "section-status",
      childIds: ["group-workflow"],
      kind: "section",
      label: "Workflow Status",
      severity: "running",
    },
    {
      id: "group-workflow",
      parentId: "section-status",
      childIds: ["resource-run"],
      kind: "group",
      label: "Current Run",
      severity: "running",
    },
    {
      id: "resource-run",
      parentId: "group-workflow",
      childIds: [],
      kind: "resource",
      label: "migration",
      description: "The active migration workflow.",
      severity: "running",
      phase: "Running",
      valueSummary: "Started 18 minutes ago",
      capabilities: ["logs", "output"],
    },
    {
      id: "section-live",
      childIds: ["group-capture", "group-buffer", "group-replay"],
      kind: "section",
      label: "Live Traffic Migration",
      severity: "warning",
    },
    {
      id: "group-capture",
      parentId: "section-live",
      childIds: ["resource-proxy"],
      kind: "group",
      label: "Capture",
      severity: "warning",
    },
    {
      id: "resource-proxy",
      parentId: "group-capture",
      childIds: ["diagnostic-proxy"],
      kind: "resource",
      label: "capture-proxy",
      description: "Captures source cluster traffic for replay.",
      severity: "warning",
      phase: "Ready",
      valueSummary: "ClusterIP :9200",
      capabilities: ["edit", "reset", "logs", "output"],
    },
    {
      id: "diagnostic-proxy",
      parentId: "resource-proxy",
      childIds: [],
      kind: "diagnostic",
      label: "Client certificate verification is not configured",
      description: "Configure a trusted client CA when client certificates are required.",
      severity: "warning",
      diagnostic: "Optional for this local capture workflow.",
    },
    {
      id: "group-buffer",
      parentId: "section-live",
      childIds: ["resource-kafka", "resource-topic"],
      kind: "group",
      label: "Buffer",
      severity: "normal",
    },
    {
      id: "resource-kafka",
      parentId: "group-buffer",
      childIds: [],
      kind: "resource",
      label: "default-kafka",
      description: "Workflow-managed Kafka cluster.",
      severity: "normal",
      phase: "Ready",
      valueSummary: "3 brokers",
      capabilities: ["reset", "logs"],
    },
    {
      id: "resource-topic",
      parentId: "group-buffer",
      childIds: [],
      kind: "resource",
      label: "captured-traffic",
      description: "Captured traffic topic.",
      severity: "normal",
      phase: "Ready",
      valueSummary: "12 partitions",
      capabilities: ["reset", "logs"],
    },
    {
      id: "group-replay",
      parentId: "section-live",
      childIds: ["resource-replayer"],
      kind: "group",
      label: "Replay",
      severity: "blocked",
    },
    {
      id: "resource-replayer",
      parentId: "group-replay",
      childIds: ["diagnostic-replayer"],
      kind: "resource",
      label: "traffic-replayer",
      description: "Replays captured traffic against the target cluster.",
      severity: "blocked",
      phase: "Waiting",
      valueSummary: "Approval required",
      capabilities: ["edit", "approve", "reset", "logs", "output"],
    },
    {
      id: "diagnostic-replayer",
      parentId: "resource-replayer",
      childIds: [],
      kind: "diagnostic",
      label: "Waiting for replay approval",
      description: "Review the target and replay settings before continuing.",
      severity: "blocked",
      diagnostic: "Replay has not started.",
    },
    {
      id: "section-snapshot",
      childIds: ["group-snapshots"],
      kind: "section",
      label: "Snapshot Migration",
      severity: "normal",
    },
    {
      id: "group-snapshots",
      parentId: "section-snapshot",
      childIds: ["resource-snapshot"],
      kind: "group",
      label: "Snapshots",
      severity: "normal",
    },
    {
      id: "resource-snapshot",
      parentId: "group-snapshots",
      childIds: [],
      kind: "resource",
      label: "catalog-snapshot",
      description: "Optional snapshot migration path.",
      severity: "normal",
      phase: "Not configured",
      capabilities: ["edit"],
    },
  ];
}

export function createInitialState(): ManageTreeState {
  const nodes = Object.fromEntries(resourceNodes().map((node) => [node.id, node]));
  return {
    revision: 1,
    mode: "inspect",
    rootIds: ["section-status", "section-live", "section-snapshot"],
    nodes,
  };
}

export const ENTER_EDIT_MODE_PATCHES: ReadonlyArray<TreePatch> = [
  {
    type: "set-mode",
    mode: "edit",
    announce: "Configuration fields are now visible.",
  },
  {
    type: "insert",
    parentId: "resource-proxy",
    index: 0,
    announce: "Capture proxy configuration added below the selected resource.",
    nodes: [
      {
        id: "config-proxy",
        parentId: "resource-proxy",
        childIds: [
          "config-listen-port",
          "config-service-type",
          "config-client-auth",
        ],
        kind: "config-group",
        label: "Proxy configuration",
        description: "Editable pending configuration.",
        severity: "changed",
      },
      {
        id: "config-listen-port",
        parentId: "config-proxy",
        childIds: [],
        kind: "config-field",
        label: "Listen port",
        description: "Port exposed by the capture proxy.",
        severity: "normal",
        valueSummary: "9200",
        configControl: {
          kind: "number",
          value: 9200,
          minimum: 1,
          maximum: 65535,
        },
      },
      {
        id: "config-service-type",
        parentId: "config-proxy",
        childIds: [],
        kind: "config-field",
        label: "Service type",
        description: "Kubernetes service exposure for the proxy.",
        severity: "changed",
        valueSummary: "ClusterIP",
        configControl: {
          kind: "enum",
          value: "ClusterIP",
          options: [
            { label: "ClusterIP", value: "ClusterIP" },
            { label: "LoadBalancer", value: "LoadBalancer" },
          ],
        },
      },
      {
        id: "config-client-auth",
        parentId: "config-proxy",
        childIds: ["config-trusted-ca"],
        kind: "config-group",
        label: "Client certificate verification",
        description: "Trust configuration for incoming mTLS clients.",
        severity: "warning",
      },
      {
        id: "config-trusted-ca",
        parentId: "config-client-auth",
        childIds: [],
        kind: "config-field",
        label: "Trusted client CA",
        description: "ConfigMap and key containing the trusted CA certificate.",
        severity: "warning",
        valueSummary: "proxy-client-ca/ca.crt",
        configControl: {
          kind: "config-map-key",
          configMap: "proxy-client-ca",
          key: "ca.crt",
          options: [
            { name: "proxy-client-ca", keys: ["ca.crt", "intermediate.crt"] },
            { name: "shared-trust", keys: ["root.pem", "README"] },
            { name: "transform-config", keys: ["transform.js", "mappings.json"] },
          ],
        },
      },
    ],
  },
  {
    type: "insert",
    parentId: "resource-replayer",
    index: 0,
    announce: "Traffic replayer configuration is available.",
    nodes: [
      {
        id: "config-replayer",
        parentId: "resource-replayer",
        childIds: ["config-speedup", "config-remove-auth"],
        kind: "config-group",
        label: "Replayer configuration",
        description: "Editable pending configuration.",
        severity: "changed",
      },
      {
        id: "config-speedup",
        parentId: "config-replayer",
        childIds: [],
        kind: "config-field",
        label: "Speedup factor",
        description: "Replay rate relative to captured traffic.",
        severity: "changed",
        valueSummary: "1.5",
        configControl: {
          kind: "number",
          value: 1.5,
          minimum: 0.1,
          maximum: 100,
        },
      },
      {
        id: "config-remove-auth",
        parentId: "config-replayer",
        childIds: [],
        kind: "config-field",
        label: "Remove authorization header",
        description: "Remove captured authorization headers before replay.",
        severity: "normal",
        valueSummary: "Enabled",
        configControl: {
          kind: "boolean",
          value: true,
        },
      },
    ],
  },
];

export const ADD_TRANSFORM_PATCH: TreePatch = {
  type: "insert",
  parentId: "config-replayer",
  index: 1,
  announce: "Transform entry point added.",
  nodes: [
    {
      id: "config-transform",
      parentId: "config-replayer",
      childIds: ["config-transform-file"],
      kind: "config-group",
      label: "Transform",
      description: "Optional request transformation.",
      severity: "changed",
    },
    {
      id: "config-transform-file",
      parentId: "config-transform",
      childIds: [],
      kind: "config-field",
      label: "JavaScript entry point",
      description: "ConfigMap and key containing the transform.",
      severity: "changed",
      valueSummary: "transform-config/transform.js",
      configControl: {
        kind: "config-map-key",
        configMap: "transform-config",
        key: "transform.js",
        options: [
          { name: "transform-config", keys: ["transform.js", "mappings.json"] },
          { name: "experimental-transform", keys: ["main.js", "package.json"] },
        ],
      },
    },
  ],
};

export const STATUS_UPDATE_PATCH: TreePatch = {
  type: "update",
  nodeId: "resource-run",
  changes: {
    valueSummary: "Observed just now",
  },
  announce: "Workflow observation refreshed.",
};

export function applyTreePatch(
  state: ManageTreeState,
  patch: TreePatch,
): ManageTreeState {
  if (patch.type === "set-mode") {
    return {
      ...state,
      revision: state.revision + 1,
      mode: patch.mode,
    };
  }

  if (patch.type === "update") {
    const current = state.nodes[patch.nodeId];
    if (!current) {
      return state;
    }
    return {
      ...state,
      revision: state.revision + 1,
      nodes: {
        ...state.nodes,
        [patch.nodeId]: {
          ...current,
          ...patch.changes,
        },
      },
    };
  }

  if (patch.type === "insert") {
    const parent = state.nodes[patch.parentId];
    if (!parent) {
      return state;
    }
    const insertedById = Object.fromEntries(
      patch.nodes.map((node) => [node.id, node]),
    );
    const topLevelIds = patch.nodes
      .filter((node) => node.parentId === patch.parentId)
      .map((node) => node.id);
    const nextChildren = [...parent.childIds];
    nextChildren.splice(
      patch.index ?? nextChildren.length,
      0,
      ...topLevelIds.filter((id) => !nextChildren.includes(id)),
    );
    return {
      ...state,
      revision: state.revision + 1,
      nodes: {
        ...state.nodes,
        ...insertedById,
        [parent.id]: {
          ...parent,
          childIds: nextChildren,
        },
      },
    };
  }

  const removed = state.nodes[patch.nodeId];
  if (!removed) {
    return state;
  }
  const idsToRemove = new Set<string>();
  const collect = (nodeId: string): void => {
    idsToRemove.add(nodeId);
    state.nodes[nodeId]?.childIds.forEach(collect);
  };
  collect(patch.nodeId);
  const nodes = Object.fromEntries(
    Object.entries(state.nodes).filter(([id]) => !idsToRemove.has(id)),
  );
  const parent = removed.parentId ? nodes[removed.parentId] : undefined;
  if (parent) {
    nodes[parent.id] = {
      ...parent,
      childIds: parent.childIds.filter((id) => id !== removed.id),
    };
  }
  return {
    ...state,
    revision: state.revision + 1,
    rootIds: state.rootIds.filter((id) => id !== removed.id),
    nodes,
  };
}

export function createOperation(): OperationState {
  return {
    id: "operation-approve-replay",
    label: "Approve traffic replay",
    phase: "Waiting for cluster state",
    state: "waiting",
    progress: 55,
  };
}

export function createLogLine(index: number): string {
  const timestamp = new Date(Date.UTC(2026, 7, 11, 14, 22, index % 60))
    .toISOString()
    .replace(".000Z", "Z");
  const messages = [
    "Loaded captured traffic batch",
    "Applied request transform",
    "Sent request to target cluster",
    "Recorded response status 200",
  ];
  return `${timestamp} replayer-0 ${messages[index % messages.length]} sequence=${index}`;
}
