import type {
  DragEvent,
  KeyboardEvent,
} from "react";
import { GripVertical } from "lucide-react";


export function DockReorderHandle({
  label,
  onDragEnd,
  onDragStart,
  onMove,
}: Readonly<{
  label: string;
  onDragEnd: () => void;
  onDragStart: () => void;
  onMove: (offset: -1 | 1) => void;
}>) {
  const startDragging = (event: DragEvent<HTMLButtonElement>) => {
    event.dataTransfer.effectAllowed = "move";
    onDragStart();
  };
  const moveWithKeyboard = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (!event.altKey) return;
    if (event.key === "ArrowUp") {
      event.preventDefault();
      onMove(-1);
    } else if (event.key === "ArrowDown") {
      event.preventDefault();
      onMove(1);
    }
  };
  return (
    <button
      aria-label={label}
      className="icon-button runtime-dock-reorder-handle"
      draggable
      onDragEnd={onDragEnd}
      onDragStart={startDragging}
      onKeyDown={moveWithKeyboard}
      title="Drag to reorder. Press Alt+Up or Alt+Down to move."
      type="button"
    >
      <GripVertical aria-hidden="true" />
    </button>
  );
}
