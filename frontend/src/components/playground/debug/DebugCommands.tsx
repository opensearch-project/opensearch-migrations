"use client";

import { useSearchParams } from "next/navigation";
import Box from "@cloudscape-design/components/box";
import SpaceBetween from "@cloudscape-design/components/space-between";
import { Suspense, type ReactNode } from "react";

interface DebugCommandProps {
  readonly children: ReactNode;
}

/**
 * Container to keep commands for debugging/troubleshooting page experience, hidden by default.
 *
 * These commands are visible when `?debug=true` is included in the query parameters.
 **/
export default function DebugCommands({ children }: DebugCommandProps) {
  return (
    <Suspense fallback={null}>
      <DebugCommandsInner>{children}</DebugCommandsInner>
    </Suspense>
  );
}

function DebugCommandsInner({ children }: DebugCommandProps) {
  const backgroundColor = "#fff4e5";
  const borderColor = "#ec7211";

  const searchParams = useSearchParams();
  const isDebugMode = searchParams?.get("debug") === "true";

  if (!isDebugMode) return null;

  return (
    <div
      style={{
        backgroundColor: backgroundColor,
        border: `2px dashed ${borderColor}`,
        borderRadius: "8px",
        padding: "2px",
      }}
    >
      <SpaceBetween size="xs">
        <Box fontSize="body-s" color="text-status-warning">
          ⚠️ Debug Commands
        </Box>
        {children}
      </SpaceBetween>
    </div>
  );
}
