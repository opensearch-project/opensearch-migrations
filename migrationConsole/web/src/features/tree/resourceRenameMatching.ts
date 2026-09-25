import type { ManageSnapshot } from "../../api/client";
import { editTarget } from "../configuration/editProjection";
import type { ResourceRenameOption } from "../configuration/resourceAdds";


export function resourceIdForRenameOption(
  snapshot: ManageSnapshot,
  option: ResourceRenameOption,
): string | null {
  const resources = Object.values(snapshot.nodes).filter(
    (node) => ["resource", "config-definition"].includes(node.kind),
  );
  const exact = resources.find(
    (node) => editTarget(node) === option.editTargetId,
  );
  if (exact) return exact.id;
  if (option.editTargetStable) return null;
  const fallback = resources.find((node) => (
    option.placement.resourcePlural
    && node.resourcePlural === option.placement.resourcePlural
    && (
      node.resourceName === option.currentName
      || node.resourceName
        === `${option.resourceNamePrefix ?? ""}${option.currentName}`
      || node.label === option.label
    )
  ));
  return fallback?.id ?? null;
}
