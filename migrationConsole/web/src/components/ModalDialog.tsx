import {
  useEffect,
  useId,
  useRef,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";

import { useEscapeCancel } from "../hooks/useEscapeCancel";


const FOCUSABLE_SELECTOR = [
  "a[href]",
  "button:not(:disabled)",
  "input:not(:disabled)",
  "select:not(:disabled)",
  "textarea:not(:disabled)",
  '[tabindex]:not([tabindex="-1"])',
].join(", ");


function focusableElements(container: HTMLElement): HTMLElement[] {
  return [...container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)];
}


export interface ModalDialogProps {
  backdropClassName?: string;
  children: ReactNode;
  className?: string;
  /** aria-label for dialogs without a rendered title (bare mode). */
  label?: string;
  /** Omit the standard header entirely; children own the chrome. */
  bare?: boolean;
  icon?: ReactNode;
  kicker?: string;
  title?: ReactNode;
  subtitle?: ReactNode;
  headerActions?: ReactNode;
  footer?: ReactNode;
  /** Invoked by Escape and the header close button. */
  onClose?: () => void;
  closeLabel?: string;
  hideCloseButton?: boolean;
  /** Keep Escape inert (e.g. while a mutation is in flight). */
  escapeDisabled?: boolean;
  /** Render into document.body so stacking is independent of the caller. */
  portal?: boolean;
}


/**
 * Shared modal scaffold: backdrop, labelled header with close button, and
 * the modal focus contract (initial focus, Tab containment, focus restore).
 * Escape routes through useEscapeCancel so nested layers keep working.
 */
export function ModalDialog({
  backdropClassName = "",
  children,
  className = "",
  label,
  bare = false,
  icon,
  kicker,
  title,
  subtitle,
  headerActions,
  footer,
  onClose,
  closeLabel = "Close dialog",
  hideCloseButton = false,
  escapeDisabled = false,
  portal = false,
}: Readonly<ModalDialogProps>) {
  const titleId = useId();
  const sectionRef = useRef<HTMLDialogElement | null>(null);
  const escapeRef = useEscapeCancel<HTMLDialogElement>(
    onClose ?? (() => undefined),
    escapeDisabled || !onClose,
  );

  useEffect(() => {
    const dialog = sectionRef.current;
    if (!dialog) return;
    const previous = document.activeElement;
    const preferred = dialog.querySelector<HTMLElement>("[data-autofocus]")
      ?? focusableElements(dialog)[0]
      ?? dialog;
    preferred.focus();
    return () => {
      if (
        previous instanceof HTMLElement
        && document.contains(previous)
      ) {
        previous.focus();
      }
    };
  }, []);

  useEffect(() => {
    const dialog = sectionRef.current;
    if (!dialog) return;
    const containFocus = (event: KeyboardEvent) => {
      if (event.key !== "Tab") return;
      const focusable = focusableElements(dialog);
      if (focusable.length === 0) {
        event.preventDefault();
        return;
      }
      const first = focusable[0];
      const last = focusable.at(-1)!;
      const active = document.activeElement;
      if (event.shiftKey && (active === first || active === dialog)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    };
    dialog.addEventListener("keydown", containFocus);
    return () => dialog.removeEventListener("keydown", containFocus);
  }, []);

  const dialog = (
    <div className={`modal-backdrop ${backdropClassName}`.trim()}>
      <dialog
        aria-label={bare || title === undefined ? label : undefined}
        aria-labelledby={bare || title === undefined ? undefined : titleId}
        className={`confirmation-dialog ${className}`.trim()}
        data-escape-cancel-layer
        open
        ref={(element) => {
          sectionRef.current = element;
          escapeRef.current = element;
        }}
        tabIndex={-1}
      >
        {bare ? null : (
          <header>
            {icon}
            <div>
              {kicker ? <span>{kicker}</span> : null}
              <h2 id={titleId}>{title}</h2>
              {subtitle ? <small>{subtitle}</small> : null}
            </div>
            {headerActions}
            {onClose && !hideCloseButton ? (
              <button
                aria-label={closeLabel}
                className="icon-button"
                onClick={onClose}
                title={closeLabel}
                type="button"
              >
                <X aria-hidden="true" />
              </button>
            ) : null}
          </header>
        )}
        {children}
        {footer !== undefined ? <footer>{footer}</footer> : null}
      </dialog>
    </div>
  );

  return portal ? createPortal(dialog, document.body) : dialog;
}
