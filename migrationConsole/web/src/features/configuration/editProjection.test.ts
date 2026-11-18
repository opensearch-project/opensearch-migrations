import { expect, test } from "vitest";

import type { ManageNode, ManageSnapshot } from "../../api/client";
import {
  projectEditSnapshot,
  removableEditTarget,
  settledRenameResourceId,
} from "./editProjection";
import type {
  PendingResourceAddition,
  PendingResourceRename,
} from "./resourceAdds";


function migrationNode(
  id: string,
  resourceName: string,
  editTargetId: string | null,
  status: string,
): ManageNode {
  return {
    id,
    revision: `${id}-1`,
    parentId: "section:Snapshot Migration",
    childIds: [],
    kind: "resource",
    label: resourceName,
    description: null,
    status,
    phase: null,
    valueSummary: null,
    diagnostics: [],
    capabilities: editTargetId
      ? [{ kind: "edit", editTargetId, label: `Edit ${resourceName}` }]
      : [],
    details: [],
    relationships: [],
    comparisons: [],
    resourcePlural: "snapshotmigrations",
    resourceName,
    resourceType: "Snapshot migration",
    configPresence: {},
  };
}


const sliceRename: PendingResourceRename = {
  editTargetId: "edit:snapshotMigrationConfigs.1",
  groupId: "section:Snapshot Migration",
  id: "resource:snapshotmigrations:src-tgt-nightly-slice-1",
  label: "slice-9",
  nodeKind: "resource",
  oldEditTargetId: "edit:snapshotMigrationConfigs.1",
  oldId: "resource:snapshotmigrations:src-tgt-nightly-slice-1",
  resourceName: "src-tgt-nightly-slice-9",
  resourcePlural: "snapshotmigrations",
  resourceType: "Snapshot migration",
  status: "applied",
};


test("settles a stable-target rename the server republished under a new id", () => {
  // The server retires the old snapshot migration identity and materializes
  // the renamed entry as a draft-only row, so the settled node id differs
  // from the one the rename started on.
  const nodes = {
    [sliceRename.oldId]: migrationNode(
      sliceRename.oldId,
      "src-tgt-nightly-slice-1",
      null,
      "removed",
    ),
    "config:snapshotMigrationConfigs:1": migrationNode(
      "config:snapshotMigrationConfigs:1",
      "src-tgt-nightly-slice-9",
      "edit:snapshotMigrationConfigs.1",
      "changed",
    ),
  };

  expect(settledRenameResourceId(nodes, sliceRename))
    .toBe("config:snapshotMigrationConfigs:1");
});


test("finds the nearest removable configuration owner for a nested target", () => {
  const nodes = [{
    id: "edit:sourceClusters.source",
    path: ["sourceClusters", "source"],
    label: "source",
    valueKind: "object",
    presence: "required",
    children: [{
      id: "edit:sourceClusters.source.snapshots.snap",
      path: ["sourceClusters", "source", "snapshots", "snap"],
      label: "snap",
      valueKind: "object",
      presence: "required",
      removable: true,
      children: [{
        id: "edit:sourceClusters.source.snapshots.snap.config",
        path: ["sourceClusters", "source", "snapshots", "snap", "config"],
        label: "config",
        valueKind: "object",
        presence: "required",
        children: [],
      }],
    }],
  }] as Parameters<typeof removableEditTarget>[0];

  expect(removableEditTarget(
    nodes,
    "edit:sourceClusters.source.snapshots.snap.config.create",
  )).toBe("edit:sourceClusters.source.snapshots.snap");
  expect(removableEditTarget(nodes, "edit:sourceClusters.source"))
    .toBeNull();
});


test("keeps a stable-target rename pending until the new name appears", () => {
  const nodes = {
    [sliceRename.oldId]: migrationNode(
      sliceRename.oldId,
      "src-tgt-nightly-slice-1",
      "edit:snapshotMigrationConfigs.1",
      "pending",
    ),
  };

  expect(settledRenameResourceId(nodes, sliceRename)).toBeNull();
});


test("settles a stable-target rename applied in place", () => {
  const nodes = {
    [sliceRename.oldId]: migrationNode(
      sliceRename.oldId,
      "src-tgt-nightly-slice-9",
      "edit:snapshotMigrationConfigs.1",
      "changed",
    ),
  };

  expect(settledRenameResourceId(nodes, sliceRename))
    .toBe(sliceRename.oldId);
});


test("projects a named pending resource into an otherwise empty group", () => {
  const snapshot: ManageSnapshot = {
    formatVersion: 1,
    revision: "draft-1",
    observedAt: "2026-09-10T12:00:00Z",
    namespace: "ma",
    workflowName: "migration-workflow",
    workflow: null,
    rootIds: ["section:Snapshot Migration"],
    nodes: {
      "section:Snapshot Migration": {
        id: "section:Snapshot Migration",
        revision: "section-1",
        parentId: null,
        childIds: [],
        kind: "section",
        label: "Snapshot Migration",
        description: null,
        status: "ok",
        phase: null,
        valueSummary: null,
        diagnostics: [],
        capabilities: [],
        details: [],
        relationships: [],
        comparisons: [],
        resourcePlural: null,
        resourceName: null,
        resourceType: null,
        configPresence: {},
      },
    },
  };
  const addition: PendingResourceAddition = {
    id: "optimistic-add:edit:snapshotMigrationConfigs.0",
    editTargetId: "edit:snapshotMigrationConfigs.0",
    groupId: "section:Snapshot Migration",
    groupLabel: "Snapshot Migration",
    label: "visual-check",
    nodeKind: "resource",
    resourceName: "visual-check",
    resourcePlural: "snapshotmigrations",
    resourceType: "Snapshot migration",
    sectionId: "section:Snapshot Migration",
    sectionLabel: "Snapshot Migration",
    status: "awaiting-draft",
  };

  const projected = projectEditSnapshot(snapshot, [addition]);

  expect(projected.nodes["section:Snapshot Migration"].childIds).toEqual([
    addition.id,
  ]);
  expect(projected.nodes[addition.id]).toMatchObject({
    parentId: "section:Snapshot Migration",
    label: "visual-check",
    status: "changed",
    valueSummary: "Addition pending submission",
    capabilities: [{
      kind: "edit",
      editTargetId: "edit:snapshotMigrationConfigs.0",
    }],
  });
});
