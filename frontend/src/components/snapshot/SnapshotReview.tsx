"use client";

import { useSnapshotConfig, useSnapshotIndexes } from "@/hooks/apiFetch";
import SnapshotConfigView from "./SnapshotForm";
import SnapshotIndexesView from "./SnapshotIndexesView";
import SpaceBetween from "@cloudscape-design/components/space-between";

interface SnapshotReviewProps {
  readonly sessionName: string;
}

export default function SnapshotReview({ sessionName }: SnapshotReviewProps) {
  const {
    isLoading: isLoadingConfig,
    data: snapshotConfig,
    error: configError,
  } = useSnapshotConfig(sessionName);
  const {
    isLoading: isLoadingIndexes,
    data: snapshotIndexes,
    error: indexesError,
  } = useSnapshotIndexes(sessionName);

  return (
    <SpaceBetween size="l">
      <SnapshotConfigView
        isLoading={isLoadingConfig}
        snapshotConfig={snapshotConfig}
        error={configError}
      />
      <SnapshotIndexesView
        isLoading={isLoadingIndexes}
        snapshotIndexes={snapshotIndexes?.indexes ?? null}
        error={indexesError}
      />
    </SpaceBetween>
  );
}
