"use client";

import { SnapshotConfig } from "@/generated/api";

interface SourceConfigureProps {
  readonly sessionName: string;
}

export default function SourceConfigure({ sessionName }: SourceConfigureProps) {
  const isLoading: boolean
  const data: SnapshotConfig
  const error: String;
}
