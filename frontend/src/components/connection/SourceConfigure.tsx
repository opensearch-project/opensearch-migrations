"use client";

import { useSourceCluster } from "@/hooks/apiFetch";
import RemoteCluster from "./RemoteCluster";

interface SourceConfigureProps {
  readonly sessionName: string;
}

export default function SourceConfigure({ sessionName }: SourceConfigureProps) {
  const { isLoading, data: cluster, error } = useSourceCluster(sessionName);

  return (
    <RemoteCluster
      clusterType="source"
      isLoading={isLoading}
      cluster={cluster}
      error={error}
      alwaysDisplayVersionOverride={true}
    />
  );
}
