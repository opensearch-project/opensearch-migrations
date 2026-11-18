import { describe, expect, it } from "vitest";

import type { ManageNode } from "../../api/client";
import { manageSnapshot } from "../../test/fixtures";
import {
  activityTargets,
  activityTypes,
  buildActivityDashboard,
} from "./activityDashboard";


function resource(
  nodeId: string,
  plural: string,
  name: string,
  sourceRefs: string[] = [],
  targetRefs: string[] = [],
): ManageNode {
  return {
    ...manageSnapshot.nodes["resource:captureproxies:capture"],
    id: nodeId,
    revision: `${nodeId}:1`,
    label: name,
    resourceName: name,
    resourcePlural: plural,
    resourceType: plural,
    sourceRefs,
    targetRefs,
  };
}


const nodes = {
  "resource:sourceconfigs:source": resource(
    "resource:sourceconfigs:source",
    "sourceconfigs",
    "source",
    ["source"],
  ),
  "resource:targetconfigs:target": resource(
    "resource:targetconfigs:target",
    "targetconfigs",
    "target",
    [],
    ["target"],
  ),
  "resource:captureproxies:capture": resource(
    "resource:captureproxies:capture",
    "captureproxies",
    "capture",
    ["source"],
  ),
  "resource:trafficreplays:replay": resource(
    "resource:trafficreplays:replay",
    "trafficreplays",
    "replay",
    ["source"],
    ["target"],
  ),
};


describe("activity dashboard projection", () => {
  it("lists exact source and target aliases and available status types", () => {
    expect(activityTargets(nodes)).toEqual([
      {
        clusterName: "source",
        kind: "source",
        nodeId: "resource:sourceconfigs:source",
      },
      {
        clusterName: "target",
        kind: "target",
        nodeId: "resource:targetconfigs:target",
      },
    ]);
    expect(activityTypes(nodes).map((type) => type.id)).toEqual([
      "cluster-indices",
      "cluster-health",
      "capture-proxies",
      "traffic-replays",
    ]);
  });

  it("creates every selected check per cluster and deduplicates shared resources", () => {
    const dashboard = buildActivityDashboard(
      nodes,
      new Set([
        "resource:sourceconfigs:source",
        "resource:targetconfigs:target",
      ]),
      new Set([
        "cluster-indices",
        "cluster-health",
        "capture-proxies",
        "traffic-replays",
      ]),
    );

    expect(dashboard.curlExplorers).toHaveLength(4);
    expect(dashboard.curlExplorers).toEqual(expect.arrayContaining([
      expect.objectContaining({
        clusterName: "source",
        path: "/_cat/indices?pretty&v",
      }),
      expect.objectContaining({
        clusterName: "source",
        path: "/_cluster/health?pretty",
      }),
      expect.objectContaining({
        clusterName: "target",
        path: "/_cat/indices?pretty&v",
      }),
      expect.objectContaining({
        clusterName: "target",
        path: "/_cluster/health?pretty",
      }),
    ]));
    expect(dashboard.runtimeStatuses).toEqual([
      expect.objectContaining({
        nodeId: "resource:captureproxies:capture",
      }),
      expect.objectContaining({
        nodeId: "resource:trafficreplays:replay",
      }),
    ]);
  });
});
