<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { ConfigControl, TreeNode } from "@manage-spike/shared";

const props = defineProps<{
  node: TreeNode & { configControl: ConfigControl };
}>();

const draft = ref<ConfigControl>(structuredClone(props.node.configControl));

watch(
  () => props.node,
  (node) => {
    draft.value = structuredClone(node.configControl);
  },
);

const selectedKeys = computed<ReadonlyArray<string>>(() => {
  const control = draft.value;
  if (control.kind !== "config-map-key") {
    return [];
  }
  return (
    control.options.find((option) => option.name === control.configMap)?.keys ??
    []
  );
});

function setText(event: Event): void {
  if (draft.value.kind === "text") {
    draft.value = {
      ...draft.value,
      value: (event.target as HTMLInputElement).value,
    };
  }
}

function setNumber(event: Event): void {
  if (draft.value.kind === "number") {
    draft.value = {
      ...draft.value,
      value: (event.target as HTMLInputElement).valueAsNumber,
    };
  }
}

function setBoolean(event: Event): void {
  if (draft.value.kind === "boolean") {
    draft.value = {
      ...draft.value,
      value: (event.target as HTMLInputElement).checked,
    };
  }
}

function setEnum(event: Event): void {
  if (draft.value.kind === "enum") {
    draft.value = {
      ...draft.value,
      value: (event.target as HTMLSelectElement).value,
    };
  }
}

function setConfigMap(event: Event): void {
  if (draft.value.kind !== "config-map-key") {
    return;
  }
  const configMap = (event.target as HTMLSelectElement).value;
  const firstKey =
    draft.value.options.find((option) => option.name === configMap)?.keys[0] ??
    "";
  draft.value = {
    ...draft.value,
    configMap,
    key: firstKey,
  };
}

function setConfigMapKey(event: Event): void {
  if (draft.value.kind === "config-map-key") {
    draft.value = {
      ...draft.value,
      key: (event.target as HTMLSelectElement).value,
    };
  }
}
</script>

<template>
  <input
    v-if="draft.kind === 'text'"
    :id="`field-${node.id}`"
    class="field-input"
    type="text"
    :value="draft.value"
    :placeholder="draft.placeholder"
    @input="setText"
  >

  <input
    v-else-if="draft.kind === 'number'"
    :id="`field-${node.id}`"
    class="field-input number-input"
    type="number"
    :value="draft.value"
    :min="draft.minimum"
    :max="draft.maximum"
    @input="setNumber"
  >

  <label
    v-else-if="draft.kind === 'boolean'"
    class="switch-control"
    :for="`field-${node.id}`"
  >
    <input
      :id="`field-${node.id}`"
      type="checkbox"
      :checked="draft.value"
      @change="setBoolean"
    >
    <span class="switch-track" aria-hidden="true">
      <span class="switch-thumb" />
    </span>
    <span>{{ draft.value ? "Enabled" : "Disabled" }}</span>
  </label>

  <select
    v-else-if="draft.kind === 'enum'"
    :id="`field-${node.id}`"
    class="field-input"
    :value="draft.value"
    @change="setEnum"
  >
    <option
      v-for="option in draft.options"
      :key="option.value"
      :value="option.value"
    >
      {{ option.label }}
    </option>
  </select>

  <div v-else-if="draft.kind === 'config-map-key'" class="reference-control">
    <label>
      <span>ConfigMap</span>
      <select
        :id="`field-${node.id}`"
        class="field-input"
        :value="draft.configMap"
        @change="setConfigMap"
      >
        <option
          v-for="option in draft.options"
          :key="option.name"
          :value="option.name"
        >
          {{ option.name }}
        </option>
      </select>
    </label>
    <span class="reference-separator" aria-hidden="true">/</span>
    <label>
      <span>Key</span>
      <select
        class="field-input"
        :value="draft.key"
        @change="setConfigMapKey"
      >
        <option v-for="key in selectedKeys" :key="key" :value="key">
          {{ key }}
        </option>
      </select>
    </label>
    <code>{{ draft.configMap }}/{{ draft.key }}</code>
  </div>
</template>
