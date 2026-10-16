import { Check, MoreHorizontal, Plus, X } from 'lucide-react';
import BonfireIcon from './BonfireIcon.jsx';
import MoodChart from './MoodChart.jsx';
import { dateKey } from '../calendar.js';

export default function MonthGrid({
  data,
  busy,
  onNew,
  onEdit,
  onOptions,
  onEntry,
  onMood,
}) {
  const dates = data
    ? Array.from({ length: data.daysInMonth }, (_, dayIndex) =>
        dateKey(data.year, data.month, dayIndex + 1),
      )
    : [];

  return (
    <>
      <div className="notebook-scroll">
        <table className="habit-table">
          <thead>
            <tr>
              <th className="habit-name-head">
                HÁBITOS <span>DIARIAMENTE</span>
              </th>
              {dates.map((date, dayIndex) => {
                const day = new Date(data.year, data.month - 1, dayIndex + 1);
                return (
                  <th
                    key={date}
                    className={`${date === data.today ? 'today-head' : ''} ${[0, 6].includes(day.getDay()) ? 'weekend' : ''}`}
                  >
                    <span>
                      {day
                        .toLocaleDateString('pt-BR', {
                          weekday: 'short',
                        })
                        .slice(0, 1)}
                    </span>
                    <strong>{dayIndex + 1}</strong>
                    {date === data.today && <i />}
                  </th>
                );
              })}
              <th className="progress-head">PROGRESSO</th>
            </tr>
          </thead>
          <tbody>
            {data.habits.map((habit, index) => (
              <tr key={habit.id}>
                <th scope="row" className="habit-name">
                  <span className="habit-number">
                    {String(index + 1).padStart(2, '0')}
                  </span>
                  <button
                    className="habit-title"
                    onClick={() => onEdit(habit)}
                    disabled={busy}
                  >
                    {habit.name}
                    {habit.endDate && (
                      <small>
                        Até {habit.endDate.split('-').reverse().join('/')}
                      </small>
                    )}
                  </button>
                  <button
                    className="habit-menu icon-button"
                    aria-label={`Opções de ${habit.name}`}
                    onClick={() => onOptions(habit, index)}
                    disabled={busy}
                  >
                    <MoreHorizontal size={17} />
                  </button>
                </th>
                {dates.map((date) => {
                  const status = habit.entries[date];
                  const disabled =
                    date > data.today ||
                    date < habit.startDate ||
                    (habit.endDate && date > habit.endDate);
                  return (
                    <td
                      key={date}
                      className={`${date === data.today ? 'today-cell' : ''} ${disabled ? 'unavailable' : ''}`}
                    >
                      <button
                        disabled={busy || disabled}
                        className={`entry-button ${status === 'COMPLETED' ? 'completed' : status ? 'missed' : ''}`}
                        onClick={() => onEntry({ habit, date })}
                        title={`${habit.name} · ${date} · ${disabled ? 'Fora do período disponível' : status === 'COMPLETED' ? 'Concluído' : status ? 'Não realizado' : 'Sem registro'}`}
                        aria-label={`${habit.name}, ${date}: ${status === 'COMPLETED' ? 'concluído' : status ? 'não realizado' : 'sem registro'}`}
                      >
                        {status === 'COMPLETED' ? (
                          <Check size={16} strokeWidth={2} />
                        ) : status ? (
                          <X size={13} />
                        ) : (
                          <span className="empty-dot">·</span>
                        )}
                      </button>
                    </td>
                  );
                })}
                <td className="progress-cell">
                  <span>
                    {habit.progress === null ? '—' : `${habit.progress}%`}
                  </span>
                  <div className="progress-track">
                    <i style={{ width: `${habit.progress || 0}%` }} />
                  </div>
                  <small>
                    {habit.completed}/{habit.eligibleDays} dias
                  </small>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!data.habits.length && (
          <div className="empty-state">
            <span className="empty-icon">
              <BonfireIcon size={29} />
            </span>
            <h3>Every long road begins right under your feet.</h3>
            <p>Você ainda não adicionou hábitos a este mês.</p>
            <button className="text-button" onClick={() => onNew()}>
              <Plus size={16} /> Adicionar meu primeiro hábito
            </button>
          </div>
        )}
        <div className="add-row">
          <button
            className="text-button"
            disabled={busy}
            onClick={() => onNew()}
          >
            <Plus size={15} /> Adicionar hábito
          </button>
          <span>As long as you move, you're winning.</span>
        </div>
        <div className="mood-section">
          <MoodChart data={data} dates={dates} busy={busy} onSelect={onMood} />
        </div>
      </div>
      <div className="notebook-bottom">
        <div className="legend">
          <span>
            <i className="legend-check">✓</i> Concluído
          </span>
          <span>
            <i>×</i> Não realizado
          </span>
          <span>
            <i>·</i> Sem registro
          </span>
        </div>
        <span className="text-xs muted">
          Clique em um dia para registrar seu humor{' '}
          <span className="ml-1">↗</span>
        </span>
      </div>
    </>
  );
}
