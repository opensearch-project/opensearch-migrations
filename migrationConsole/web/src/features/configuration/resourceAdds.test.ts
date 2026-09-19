import { expect, test } from "vitest";

import type { EditNode } from "@opensearch-migrations/config-edit-core";
import {
  pendingResourceRename,
  resourceAddPlacement,
  resourceRenameCollisionProblem,
  type ResourceRenameOption,
} from "./resourceAdds";


const snapshotPlacement = {
  collectionPath: "snapshotMigrationConfigs",
  groupId: "section:Snapshot Migration",
  groupLabel: "Snapshot Migration",
  nodeKind: "resource" as const,
  resourcePlural: "snapshotmigrations",
  resourceType: "Snapshot migration",
  sectionId: "section:Snapshot Migration",
  sectionLabel: "Snapshot Migration",
};


test("places optimistic snapshot migrations directly under their section", () => {
  const node = {
    path: ["snapshotMigrationConfigs"],
    inputHint: {
      resourceCollection: {
        navigation: {
          sectionId: "section:Snapshot Migration",
          sectionLabel: "Snapshot Migration",
          groupId: "group:Snapshot Migration:Backfill",
          groupLabel: "Backfill",
        },
        resource: {
          plural: "snapshotmigrations",
          typeLabel: "Snapshot migration",
        },
      },
    },
  } as Pick<EditNode, "inputHint" | "path">;

  expect(resourceAddPlacement(node)).toMatchObject({
    groupId: "section:Snapshot Migration",
    groupLabel: "Snapshot Migration",
  });
});


test("keeps a stable draft node id while renaming a snapshot migration", () => {
  const option: ResourceRenameOption = {
    collisionScope: "source-target-snap",
    currentName: "slice-0",
    editTargetStable: true,
    editTargetId: "edit:snapshotMigrationConfigs.1",
    label: "source-target-snap-slice-0",
    labelPrefix: "source-target-snap-",
    operation: "set",
    path: ["snapshotMigrationConfigs", "1", "slice"],
    placement: snapshotPlacement,
    resourceNamePrefix: "source-target-snap-",
  };

  expect(pendingResourceRename(
    option,
    "config:snapshotMigrationConfigs:1",
    "slice-a",
  )).toMatchObject({
    id: "config:snapshotMigrationConfigs:1",
    label: "source-target-snap-slice-a",
    resourceName: "source-target-snap-slice-a",
  });
});


test("rejects only names already used in the same migration tuple", () => {
  const option: ResourceRenameOption = {
    collisionScope: "source-target-snap",
    conflictingNames: ["slice-a"],
    currentName: "slice-0",
    editTargetId: "edit:snapshotMigrationConfigs.1",
    label: "source-target-snap-slice-0",
    path: ["snapshotMigrationConfigs", "1", "slice"],
    placement: snapshotPlacement,
  };

  expect(resourceRenameCollisionProblem(option, "slice-a")).toBe(
    "Snapshot migration 'source-target-snap-slice-a' is already configured. "
    + "Choose another name.",
  );
  expect(resourceRenameCollisionProblem(option, "slice-b")).toBe("");
});
