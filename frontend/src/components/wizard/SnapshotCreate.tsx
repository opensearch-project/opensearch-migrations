"use client";

import { useSnapshotConfig } from "../session/apiHooks";
import SnapshotConfigView from "./SnapshotForm";

interface SnapshotCreateProps {
  readonly sessionName: string;
}

export default function SnapshotController({ sessionName }: SnapshotCreateProps) {
  const { isLoading, data: snapshotConfig, error } = useSnapshotConfig(sessionName);

  return (
    <SnapshotConfigView
      isLoading={isLoading}
      snapshotConfig={snapshotConfig}
      error={error}
    />
    <SnapshotIndices></SnapshotIndices>
  );
}
