"use client";

import { useTargetCluster } from "@/hooks/apiFetch";
import RemoteCluster from "./RemoteCluster";

interface TargetConfigureProps {
  readonly sessionName: string;
}

export default function TargetConfigure({ sessionName }: TargetConfigureProps) {
  const { isLoading, data: cluster, error } = useTargetCluster(sessionName);

  return (
    <RemoteCluster
      clusterType="target"
      isLoading={isLoading}
      cluster={cluster}
      error={error}
      alwaysDisplayVersionOverride={false}
    />
  );
}
