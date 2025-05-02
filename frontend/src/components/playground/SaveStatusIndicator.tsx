"use client";

import { useEffect, useReducer } from "react";
import { SaveStatus, SaveState } from "@/types/SaveStatus";
import StatusIndicator from "@cloudscape-design/components/status-indicator";

import { formatDistanceToNow } from "date-fns";

/**
 * Component to display the save status of a document or transformation
 */
interface SaveStatusIndicatorProps {
  state: SaveState;
}

/**
 * A component that displays a status indicator for the save state of a document or transformation
 *
 * @param status - The current save status (SAVED, UNSAVED, or BLOCKED)
 */
export default function SaveStatusIndicator({
  state,
}: Readonly<SaveStatusIndicatorProps>) {
  // Use useReducer to force re-renders of the "X seconds ago" text without causing sonarqube
  // to complain about unused variables
  const forceUpdate = useReducer((tick) => tick + 1, 0)[1];

  useEffect(() => {
    const intervalId = setInterval(() => {
      forceUpdate();
    }, 1000 * 30); // 30 seconds

    // Clean up on unmount
    return () => clearInterval(intervalId);
  }, [forceUpdate]);

  const getStatusType = (state: SaveState) => {
    switch (state?.status ?? null) {
      case SaveStatus.SAVED:
        return "success";
      case SaveStatus.BLOCKED:
        return "error";
      case SaveStatus.UNSAVED:
      default:
        return "in-progress";
    }
  };

  const getStatusText = (state: SaveState) => {
    switch (state?.status ?? null) {
      case SaveStatus.SAVED:
        return (
          "Saved" +
          (state.savedAt ? ` ${formatDistanceToNow(state.savedAt)} ago` : "")
        );
      case SaveStatus.BLOCKED: {
        const errorCount = state.errors.length;
        const errorSuffix = errorCount > 1 ? "s" : "";
        const errorText = errorCount
          ? ` (${errorCount} error${errorSuffix})`
          : "";
        return "Blocked" + errorText;
      }
      case SaveStatus.UNSAVED:
      default:
        return "Unsaved";
    }
  };

  return (
    <StatusIndicator type={getStatusType(state)}>
      {getStatusText(state)}
    </StatusIndicator>
  );
}
