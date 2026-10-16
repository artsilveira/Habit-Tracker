import { useEffect, useId, useRef } from 'react';
import { X } from 'lucide-react';

export default function Modal({
  title,
  onClose,
  children,
  error,
  busy = false,
}) {
  const dialogRef = useRef(null);
  const titleId = useId();
  useEffect(() => {
    const dialog = dialogRef.current;
    dialog.showModal();
    return () => dialog.close();
  }, []);
  return (
    <dialog
      ref={dialogRef}
      className="modal"
      aria-labelledby={titleId}
      aria-busy={busy}
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) onClose();
      }}
      onClick={(event) => {
        const bounds = dialogRef.current.getBoundingClientRect();
        if (
          !busy &&
          event.target === dialogRef.current &&
          (event.clientX < bounds.left ||
            event.clientX > bounds.right ||
            event.clientY < bounds.top ||
            event.clientY > bounds.bottom)
        )
          onClose();
      }}
    >
      <div className="flex items-center justify-between gap-4 mb-6">
        <h2 id={titleId} className="serif text-2xl">
          {title}
        </h2>
        <button
          disabled={busy}
          className="icon-button"
          onClick={onClose}
          aria-label="Fechar"
        >
          <X size={19} />
        </button>
      </div>
      {error && (
        <p className="error-message mb-4" role="alert">
          {error}
        </p>
      )}
      {children}
    </dialog>
  );
}
