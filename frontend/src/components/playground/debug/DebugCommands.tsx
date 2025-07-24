"use client";

import { useSearchParams } from "next/navigation";
import Box from "@cloudscape-design/components/box";
import SpaceBetween from "@cloudscape-design/components/space-between";
import type { ReactNode } from "react";

interface DebugCommandProps {
  children: ReactNode;
}

export default function DebugCommands({ children }: DebugCommandProps) {
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
