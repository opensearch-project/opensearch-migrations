import { StepState } from "@/generated/api/types.gen";

export interface SessionStatusProps {
  readonly sessionName: string;
}

export type SnapshotData = {
  status: StepState;
  percentage_completed: number;
  eta_ms: number | null;
  started?: string;
  finished?: string;
};

export type MetadataData = {
  status?: StepState;
  started?: string;
  finished?: string;
  clusters?: any;
  items?: any;
  transformations?: any;
  errors?: Array<string>;
  errorCount?: number;
  errorCode?: number;
  errorMessage?: string;
};
