import { Plus } from 'lucide-react';
import Modal from './Modal.jsx';
export default function HabitForm({
  habit,
  today,
  onSave,
  onClose,
  busy,
  error,
}) {
  return (
    <Modal
      title={habit ? 'Editar hábito' : 'Novo hábito'}
      onClose={onClose}
      error={error}
      busy={busy}
    >
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const b = Object.fromEntries(new FormData(e.currentTarget));
          if (habit) b.endDate ||= null;
          onSave(b);
        }}
      >
        <fieldset disabled={busy} className="space-y-5">
          <label>
            Nome do hábito
            <input
              name="name"
              maxLength={100}
              defaultValue={habit?.name || ''}
              placeholder="Ex.: ler por 20 minutos"
              required
              autoFocus
            />
          </label>
          <label>
            Começar em
            <input
              name="startDate"
              type="date"
              min="1900-01-01"
              max="9999-12-31"
              defaultValue={habit?.startDate || today}
              required
            />
          </label>
          {habit && (
            <label>
              Último dia de acompanhamento
              <input
                name="endDate"
                type="date"
                min="1900-01-01"
                max="9999-12-31"
                defaultValue={habit.endDate || ''}
              />
              <span className="field-hint">
                Deixe em branco para continuar nos próximos meses.
              </span>
            </label>
          )}
          <p className="text-sm muted leading-relaxed">
            O hábito aparece nos próximos meses até você definir uma data final.
          </p>
          <button
            disabled={busy}
            type="submit"
            className="primary w-full justify-center"
          >
            {busy
              ? 'Salvando…'
              : habit
                ? 'Salvar alterações'
                : 'Adicionar hábito'}
            <Plus size={16} />
          </button>
        </fieldset>
      </form>
    </Modal>
  );
}
