import { StepState } from '@/generated/api/types.gen';

export interface SessionStatus {
  name: string;
  created: string;
  snapshot: StepStatusInfo;
  metadata: StepStatusInfo;
  backfill: StepStatusInfo;
}

export interface StepStatusInfo {
  status: StepState;
  started?: string;
  finished?: string;
  percentage_completed?: number;
  eta_ms?: number | null;
}

export interface SessionStatusProps {
  sessionName: string;
}
