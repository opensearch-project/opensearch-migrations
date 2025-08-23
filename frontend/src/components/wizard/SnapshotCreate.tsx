"use client";

import { useSnapshotConfig } from "../session/apiHooks";
import SnapshotForm from "./SnapshotForm";

interface SnapshotCreateProps {
  readonly sessionName: string;
}

export default function SnapshotCreate({ sessionName }: SnapshotCreateProps) {
  const { isLoading, data: snapshotConfig, error } = useSnapshotConfig(sessionName);

  return (
    <SnapshotForm
      isLoading={isLoading}
      snapshotConfig={snapshotConfig}
      error={error}
    />
  );
}
