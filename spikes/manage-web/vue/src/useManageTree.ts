import { onBeforeUnmount, ref, shallowRef } from "vue";
import {
  applyTreePatch,
  createInitialState,
  type TreePatch,
} from "@manage-spike/shared";

const PATCH_DELAY_MS = 140;
const INSERT_ANIMATION_MS = 900;

export function useManageTree() {
  const state = shallowRef(createInitialState());
  const announcement = ref("");
  const insertedIds = shallowRef<ReadonlySet<string>>(new Set());
  const transitioning = ref(false);
  const timers = new Set<ReturnType<typeof setTimeout>>();

  function schedule(callback: () => void, delay: number): void {
    const timer = setTimeout(() => {
      timers.delete(timer);
      callback();
    }, delay);
    timers.add(timer);
  }

  function applyPatch(patch: TreePatch): void {
    state.value = applyTreePatch(state.value, patch);
    announcement.value = patch.announce;

    if (patch.type === "insert") {
      const ids = patch.nodes.map((node) => node.id);
      insertedIds.value = new Set([...insertedIds.value, ...ids]);
      schedule(() => {
        const remaining = new Set(insertedIds.value);
        ids.forEach((id) => remaining.delete(id));
        insertedIds.value = remaining;
      }, INSERT_ANIMATION_MS);
    }
  }

  function applyPatches(patches: ReadonlyArray<TreePatch>): void {
    if (patches.length === 0) {
      return;
    }
    transitioning.value = true;
    patches.forEach((patch, index) => {
      schedule(() => applyPatch(patch), index * PATCH_DELAY_MS);
    });
    schedule(
      () => {
        transitioning.value = false;
      },
      patches.length * PATCH_DELAY_MS,
    );
  }

  onBeforeUnmount(() => {
    timers.forEach(clearTimeout);
    timers.clear();
  });

  return {
    state,
    announcement,
    insertedIds,
    transitioning,
    applyPatch,
    applyPatches,
  };
}
