import {
  CircleAlert,
  CircleCheck,
  CircleDashed,
  CircleX,
  Clock3,
  LoaderCircle,
  Trash2,
  TriangleAlert,
} from "lucide-react";

import { normalizedStatus } from "./status";


export function StatusIndicator({
  status,
  className = "",
}: Readonly<{
  status: string | null | undefined;
  className?: string;
}>) {
  const normalized = normalizedStatus(status);
  const classes = [
    "state-icon",
    `state-${normalized.replaceAll(" ", "-")}`,
    className,
  ].filter(Boolean).join(" ");
  if (["failed", "error"].includes(normalized)) {
    return <CircleX aria-hidden="true" className={classes} />;
  }
  if (["blocked", "gated", "required"].includes(normalized)) {
    return <CircleAlert aria-hidden="true" className={classes} />;
  }
  if (normalized === "warning") {
    return <TriangleAlert aria-hidden="true" className={classes} />;
  }
  if (["running", "syncing"].includes(normalized)) {
    return <LoaderCircle aria-hidden="true" className={`${classes} spin`} />;
  }
  if (["changed"].includes(normalized)) {
    return <Clock3 aria-hidden="true" className={classes} />;
  }
  if (normalized === "removed") {
    return <Trash2 aria-hidden="true" className={classes} />;
  }
  if ([
    "ready",
    "completed",
    "succeeded",
    "skipped",
    "approved",
    "ok",
  ].includes(normalized)) {
    return <CircleCheck aria-hidden="true" className={classes} />;
  }
  return <CircleDashed aria-hidden="true" className={classes} />;
}
