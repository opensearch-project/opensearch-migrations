import { useCallback, useEffect, useRef, useState } from "react";
import {
  applyTreePatch,
  createInitialState,
  type ManageTreeState,
  type TreePatch,
} from "@manage-spike/shared";

const PATCH_DELAY_MS = 140;
const INSERT_ANIMATION_MS = 900;

export interface ManageTreeController {
  state: ManageTreeState;
  announcement: string;
  insertedIds: ReadonlySet<string>;
  transitioning: boolean;
  applyPatch: (patch: TreePatch) => void;
  applyPatches: (patches: ReadonlyArray<TreePatch>) => void;
}

export function useManageTree(): ManageTreeController {
  const [state, setState] = useState(createInitialState);
  const [announcement, setAnnouncement] = useState("");
  const [insertedIds, setInsertedIds] = useState<ReadonlySet<string>>(
    () => new Set(),
  );
  const [transitioning, setTransitioning] = useState(false);
  const timers = useRef<number[]>([]);

  const schedule = useCallback((callback: () => void, delay: number): void => {
    const timer = window.setTimeout(() => {
      timers.current = timers.current.filter((candidate) => candidate !== timer);
      callback();
    }, delay);
    timers.current.push(timer);
  }, []);

  useEffect(
    () => () => {
      timers.current.forEach(window.clearTimeout);
    },
    [],
  );

  const applyPatch = useCallback(
    (patch: TreePatch): void => {
      setState((current) => applyTreePatch(current, patch));
      setAnnouncement(patch.announce);

      if (patch.type === "insert") {
        const ids = patch.nodes.map((node) => node.id);
        setInsertedIds((current) => new Set([...current, ...ids]));
        schedule(() => {
          setInsertedIds((current) => {
            const next = new Set(current);
            ids.forEach((id) => next.delete(id));
            return next;
          });
        }, INSERT_ANIMATION_MS);
      }
    },
    [schedule],
  );

  const applyPatches = useCallback(
    (patches: ReadonlyArray<TreePatch>): void => {
      if (patches.length === 0) {
        return;
      }
      setTransitioning(true);
      patches.forEach((patch, index) => {
        schedule(() => applyPatch(patch), index * PATCH_DELAY_MS);
      });
      schedule(
        () => setTransitioning(false),
        patches.length * PATCH_DELAY_MS,
      );
    },
    [applyPatch, schedule],
  );

  return {
    state,
    announcement,
    insertedIds,
    transitioning,
    applyPatch,
    applyPatches,
  };
}
