import { useEffect, useId, useRef } from 'react';
import { X } from 'lucide-react';
export default function Modal({
  title,
  onClose,
  children,
  error,
  busy = false,
}) {
  const ref = useRef(null);
  const titleId = useId();
  useEffect(() => {
    const dialog = ref.current;
    dialog.showModal();
    return () => dialog.close();
  }, []);
  return (
    <dialog
      ref={ref}
      className="modal"
      aria-labelledby={titleId}
      aria-busy={busy}
      onCancel={(e) => {
        e.preventDefault();
        if (!busy) onClose();
      }}
      onClick={(e) => {
        const bounds = ref.current.getBoundingClientRect();
        if (
          !busy &&
          e.target === ref.current &&
          (e.clientX < bounds.left ||
            e.clientX > bounds.right ||
            e.clientY < bounds.top ||
            e.clientY > bounds.bottom)
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
