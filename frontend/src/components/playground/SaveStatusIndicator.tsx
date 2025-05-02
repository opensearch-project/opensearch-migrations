"use client";

import { useState, useEffect } from "react";
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
  // Add a state to force re-renders of the "X seconds ago" text
  const [, setTick] = useState(0);

  useEffect(() => {
    const intervalId = setInterval(() => {
      setTick((tick) => tick + 1);
    }, 1000 * 30); // 30 seconds

    // Clean up on unmount
    return () => clearInterval(intervalId);
  }, []);
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
      case SaveStatus.BLOCKED:
        return (
          "Blocked" +
          (state.errors.length
            ? ` (${state.errors.length} error${
                state.errors.length > 1 ? "s" : ""
              })`
            : "")
        );
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
