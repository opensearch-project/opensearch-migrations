import {
  useRef,
  type KeyboardEvent,
  type PointerEvent,
} from "react";
import { GripHorizontal } from "lucide-react";

import {
  boundedDockHeight,
  DEFAULT_DOCK_HEIGHT,
  MAX_DOCK_HEIGHT,
  MIN_DOCK_HEIGHT,
} from "./dockPaneState";


interface ResizeStart {
  pointerId: number;
  startY: number;
  startHeight: number;
}


export function DockResizeHandle({
  edge = "top",
  height,
  label,
  onChange,
}: Readonly<{
  edge?: "top" | "bottom";
  height: number;
  label: string;
  onChange: (height: number) => void;
}>) {
  const resizeStart = useRef<ResizeStart | null>(null);
  const resizeWithKeyboard = (event: KeyboardEvent<HTMLInputElement>) => {
    if (!["ArrowUp", "ArrowDown", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    if (event.key === "Home") onChange(MIN_DOCK_HEIGHT);
    else if (event.key === "End") onChange(MAX_DOCK_HEIGHT);
    else {
      const direction = (
        event.key === "ArrowUp"
          ? (edge === "top" ? 1 : -1)
          : (edge === "top" ? -1 : 1)
      );
      onChange(boundedDockHeight(height + direction * 20));
    }
  };
  const resizeFromPointer = (event: PointerEvent<HTMLInputElement>) => {
    const start = resizeStart.current;
    if (!start || start.pointerId !== event.pointerId) return;
    const delta = edge === "top"
      ? start.startY - event.clientY
      : event.clientY - start.startY;
    onChange(boundedDockHeight(
      start.startHeight + delta,
    ));
  };
  const finishResize = (event: PointerEvent<HTMLInputElement>) => {
    if (resizeStart.current?.pointerId !== event.pointerId) return;
    resizeStart.current = null;
    event.currentTarget.releasePointerCapture(event.pointerId);
  };

  return (
    <div className={`runtime-dock-resize-handle edge-${edge}`}>
      <GripHorizontal aria-hidden="true" />
      <input
        aria-label={label}
        aria-valuenow={height}
        max={MAX_DOCK_HEIGHT}
        min={MIN_DOCK_HEIGHT}
        onChange={(event) => onChange(
          boundedDockHeight(Number(event.currentTarget.value)),
        )}
        onDoubleClick={() => onChange(DEFAULT_DOCK_HEIGHT)}
        onKeyDown={resizeWithKeyboard}
        onPointerCancel={finishResize}
        onPointerDown={(event) => {
          event.preventDefault();
          resizeStart.current = {
            pointerId: event.pointerId,
            startY: event.clientY,
            startHeight: height,
          };
          event.currentTarget.setPointerCapture(event.pointerId);
        }}
        onPointerMove={resizeFromPointer}
        onPointerUp={finishResize}
        step={20}
        title={`Drag the ${edge} edge to resize; double-click to reset`}
        type="range"
        value={height}
      />
    </div>
  );
}
