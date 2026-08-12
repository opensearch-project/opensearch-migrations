<script setup lang="ts">
import { nextTick, ref, watch } from "vue";
import { Pause, Play, Trash2 } from "lucide-vue-next";

const props = defineProps<{
  lines: ReadonlyArray<string>;
  running: boolean;
}>();

const emit = defineEmits<{
  start: [];
  stop: [];
  clear: [];
}>();

const output = ref<HTMLElement>();

watch(
  () => props.lines.length,
  async () => {
    if (!props.running) {
      return;
    }
    await nextTick();
    if (output.value) {
      output.value.scrollTop = output.value.scrollHeight;
    }
  },
);
</script>

<template>
  <div class="logs-tool">
    <div class="logs-toolbar">
      <div class="log-source">
        <span :class="running ? 'pulse-dot' : 'idle-dot'" />
        <div>
          <strong>traffic-replayer</strong>
          <span>replayer-0 · migration-console</span>
        </div>
      </div>
      <div class="toolbar-actions">
        <button
          class="button"
          :class="running ? 'danger-subtle' : 'primary'"
          type="button"
          data-testid="log-stream-control"
          @click="running ? emit('stop') : emit('start')"
        >
          <Pause v-if="running" :size="16" aria-hidden="true" />
          <Play v-else :size="16" aria-hidden="true" />
          {{ running ? "Stop stream" : "Start stream" }}
        </button>
        <button
          class="icon-button"
          type="button"
          aria-label="Clear logs"
          title="Clear logs"
          :disabled="lines.length === 0"
          @click="emit('clear')"
        >
          <Trash2 :size="16" aria-hidden="true" />
        </button>
      </div>
    </div>
    <div ref="output" class="log-output" role="log" aria-label="Resource logs">
      <span v-if="lines.length === 0" class="log-placeholder">
        Log stream is stopped.
      </span>
      <div v-for="(line, index) in lines" v-else :key="`${index}-${line}`" class="log-line">
        <span>{{ String(index + 1).padStart(3, "0") }}</span>
        <code>{{ line }}</code>
      </div>
    </div>
  </div>
</template>
