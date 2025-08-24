"use client";

import { useSnapshotConfig, useSnapshotIndexes } from "../session/apiHooks";
import SnapshotConfigView from "./SnapshotForm";
import SnapshotIndexesView from "./SnapshotIndexesView";

interface SnapshotCreateProps {
  readonly sessionName: string;
}

export default function SnapshotController({ sessionName }: SnapshotCreateProps) {
  const { isLoading: isLoadingConfig, data: snapshotConfig, error: configError } = useSnapshotConfig(sessionName);
  const { isLoading: isLoadingIndexes, data: snapshotIndexes, error: indexesError } = useSnapshotIndexes(sessionName);

  return (
    <div>
      <SnapshotConfigView
        isLoading={isLoadingConfig}
        snapshotConfig={snapshotConfig}
        error={configError}
      />
      <SnapshotIndexesView
        isLoading={isLoadingIndexes}
        snapshotIndexes={snapshotIndexes || null}
        error={indexesError}
      />
      
    </div>
  );
}
