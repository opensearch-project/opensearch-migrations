import { useEffect, useRef, type RefObject } from "react";


const ESCAPE_LAYER_SELECTOR = "[data-escape-cancel-layer]";


export function useEscapeCancel<T extends HTMLElement>(
  onCancel: () => void,
  disabled = false,
): RefObject<T> {
  const layerRef = useRef<T>(null);
  const cancelRef = useRef(onCancel);

  useEffect(() => {
    cancelRef.current = onCancel;
  }, [onCancel]);

  useEffect(() => {
    if (disabled) return;

    const cancelTopLayer = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "Escape" || !layerRef.current) return;
      const layers = document.querySelectorAll(ESCAPE_LAYER_SELECTOR);
      if (layers.item(layers.length - 1) !== layerRef.current) return;

      event.preventDefault();
      event.stopImmediatePropagation();
      cancelRef.current();
    };

    document.addEventListener("keydown", cancelTopLayer);
    return () => document.removeEventListener("keydown", cancelTopLayer);
  }, [disabled]);

  return layerRef;
}
