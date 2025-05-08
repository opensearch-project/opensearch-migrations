import { IAnnotation } from "react-ace/lib/types";

export enum SaveStatus {
  SAVED = "saved",
  UNSAVED = "unsaved",
  BLOCKED = "blocked",
}

export type SaveState = {
  status: SaveStatus;
  savedAt: Date | null;
  errors: IAnnotation[];
};
