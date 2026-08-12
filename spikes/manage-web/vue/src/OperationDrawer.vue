<script setup lang="ts">
import { Activity, CircleDashed } from "lucide-vue-next";
import type { OperationState } from "@manage-spike/shared";

defineProps<{
  operations: ReadonlyArray<OperationState>;
}>();
</script>

<template>
  <aside class="operations-drawer" aria-label="Active operations">
    <header>
      <div>
        <span class="eyebrow">Session</span>
        <h2>Operations</h2>
      </div>
      <span class="live-indicator">
        <span />
        Live
      </span>
    </header>
    <div class="operation-list">
      <article v-for="operation in operations" :key="operation.id" class="operation-item">
        <div class="operation-title">
          <span class="operation-icon">
            <CircleDashed
              v-if="operation.state !== 'succeeded'"
              :size="17"
              class="spinning"
              aria-hidden="true"
            />
            <Activity v-else :size="17" aria-hidden="true" />
          </span>
          <div>
            <strong>{{ operation.label }}</strong>
            <p>{{ operation.phase }}</p>
          </div>
          <span class="operation-state">{{ operation.state }}</span>
        </div>
        <div class="operation-progress" aria-label="Operation progress">
          <span :style="{ width: `${operation.progress}%` }" />
        </div>
        <footer>
          <span>{{ operation.progress }}%</span>
          <span>Cluster watch active</span>
        </footer>
      </article>
    </div>
  </aside>
</template>
