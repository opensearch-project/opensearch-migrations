<script setup lang="ts">
import { computed } from "vue";
import { Check, FileKey2, Plus } from "lucide-vue-next";
import {
  type ConfigControl,
  type ManageTreeState,
  type TreeNode,
} from "@manage-spike/shared";
import ConfigField from "./ConfigField.vue";

const props = defineProps<{
  state: ManageTreeState;
  selectedNode: TreeNode;
  canAddTransform: boolean;
  transformAdded: boolean;
}>();

const emit = defineEmits<{
  addTransform: [];
}>();

type ConfigFieldNode = TreeNode & { configControl: ConfigControl };

const fields = computed<ReadonlyArray<ConfigFieldNode>>(() => {
  const collected: ConfigFieldNode[] = [];
  function visit(nodeId: string): void {
    const node = props.state.nodes[nodeId];
    if (!node) {
      return;
    }
    if (node.kind === "config-field" && node.configControl) {
      collected.push(node as ConfigFieldNode);
    }
    node.childIds.forEach(visit);
  }
  visit(props.selectedNode.id);
  return collected;
});
</script>

<template>
  <div v-if="fields.length === 0" class="workspace-empty">
    <FileKey2 :size="28" aria-hidden="true" />
    <h3>No editable values</h3>
    <p>
      {{
        state.mode === "inspect"
          ? "Configuration is available in edit mode."
          : "Select a resource or configuration group."
      }}
    </p>
  </div>

  <form v-else class="configuration-form" @submit.prevent>
    <div class="configuration-toolbar">
      <div>
        <span class="eyebrow">Pending configuration</span>
        <h3>{{ fields.length }} editable values</h3>
      </div>
      <button
        v-if="state.nodes['config-replayer']"
        class="button secondary"
        type="button"
        data-testid="add-transform-control"
        :disabled="!canAddTransform"
        @click="emit('addTransform')"
      >
        <Check v-if="transformAdded" :size="16" aria-hidden="true" />
        <Plus v-else :size="16" aria-hidden="true" />
        {{ transformAdded ? "Transform added" : "Add transform" }}
      </button>
    </div>

    <div class="configuration-fields">
      <div v-for="node in fields" :key="node.id" class="configuration-field">
        <div class="field-copy">
          <label :for="`field-${node.id}`">{{ node.label }}</label>
          <p>{{ node.description }}</p>
        </div>
        <ConfigField :node="node" />
      </div>
    </div>
  </form>
</template>
