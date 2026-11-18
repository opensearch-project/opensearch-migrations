import { expect, test } from "vitest";

import type { ManageNode, ManageSnapshot } from "../../api/client";
import type { ResourceRenameOption } from "../configuration/resourceAdds";
import { resourceIdForRenameOption } from "./resourceRenameMatching";


function migrationNode(
  id: string,
  editTargetId: string | null,
): ManageNode {
  return {
    id,
    revision: `${id}-1`,
    parentId: "section:Snapshot Migration",
    childIds: [],
    kind: "resource",
    label: "source-target-snap-slice-0",
    description: null,
    status: "error",
    phase: null,
    valueSummary: null,
    diagnostics: [],
    capabilities: editTargetId
      ? [{ kind: "edit", editTargetId, label: "Edit snapshot migration" }]
      : [],
    details: [],
    relationships: [],
    comparisons: [],
    resourcePlural: "snapshotmigrations",
    resourceName: "source-target-snap-slice-0",
    resourceType: "Snapshot migration",
    configPresence: {},
  };
}


function renameOption(editTargetId: string): ResourceRenameOption {
  return {
    currentName: "slice-0",
    editTargetId,
    editTargetStable: true,
    label: "source-target-snap-slice-0",
    operation: "set",
    path: [...editTargetId.replace(/^edit:/, "").split("."), "slice"],
    placement: {
      collectionPath: "snapshotMigrationConfigs",
      groupId: "section:Snapshot Migration",
      groupLabel: "Snapshot Migration",
      nodeKind: "resource",
      resourcePlural: "snapshotmigrations",
      resourceType: "Snapshot migration",
    },
    resourceNamePrefix: "source-target-snap-",
  };
}


test("matches duplicate snapshot migration names by stable edit target", () => {
  const runtimeId = "resource:snapshotmigrations:source-target-snap-slice-0";
  const firstId = "config:snapshotMigrationConfigs:0";
  const secondId = "config:snapshotMigrationConfigs:1";
  const snapshot: ManageSnapshot = {
    formatVersion: 1,
    revision: "draft-1",
    observedAt: "2026-09-11T12:00:00Z",
    namespace: "ma",
    workflowName: "migration-workflow",
    workflow: null,
    rootIds: ["section:Snapshot Migration"],
    nodes: {
      [runtimeId]: migrationNode(runtimeId, null),
      [firstId]: migrationNode(firstId, "edit:snapshotMigrationConfigs.0"),
      [secondId]: migrationNode(secondId, "edit:snapshotMigrationConfigs.1"),
    },
  };

  expect(resourceIdForRenameOption(
    snapshot,
    renameOption("edit:snapshotMigrationConfigs.0"),
  )).toBe(firstId);
  expect(resourceIdForRenameOption(
    snapshot,
    renameOption("edit:snapshotMigrationConfigs.1"),
  )).toBe(secondId);
});
