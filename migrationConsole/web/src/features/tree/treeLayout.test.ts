import { describe, expect, it } from "vitest";

import {
  resolveTreeLayoutOffset,
  type TreeLayoutOffset,
} from "./treeLayout";


describe("resolveTreeLayoutOffset", () => {
  const parents = new Map<string, string | null>([
    ["buffer", null],
    ["default", "buffer"],
    ["field", "default"],
  ]);

  it("makes unmeasured descendants follow their nearest moving ancestor", () => {
    const offsets = new Map<string, TreeLayoutOffset>([
      ["buffer", { x: 0, y: 84 }],
    ]);
    const resolved = new Map<string, TreeLayoutOffset>();

    expect(resolveTreeLayoutOffset(
      "default",
      parents,
      offsets,
      resolved,
    )).toEqual({ x: 0, y: 84 });
    expect(resolveTreeLayoutOffset(
      "field",
      parents,
      offsets,
      resolved,
    )).toEqual({ x: 0, y: 84 });
  });

  it("preserves a measured stationary child's layout anchor", () => {
    const offsets = new Map<string, TreeLayoutOffset>([
      ["buffer", { x: 0, y: 84 }],
      ["default", { x: 0, y: 0 }],
    ]);

    expect(resolveTreeLayoutOffset(
      "default",
      parents,
      offsets,
    )).toEqual({ x: 0, y: 0 });
  });

  it("preserves a child's own meaningful displacement", () => {
    const offsets = new Map<string, TreeLayoutOffset>([
      ["buffer", { x: 0, y: 84 }],
      ["default", { x: 0, y: 42 }],
    ]);

    expect(resolveTreeLayoutOffset(
      "default",
      parents,
      offsets,
    )).toEqual({ x: 0, y: 42 });
  });
});
