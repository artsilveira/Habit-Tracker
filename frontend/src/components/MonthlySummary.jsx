import { BookOpen, Check, ChartNoAxesCombined, Smile } from 'lucide-react';

export default function MonthlySummary({ data }) {
  const eligibleDays =
    data?.habits.reduce((total, habit) => total + habit.eligibleDays, 0) || 0;
  const completed =
    data?.habits.reduce((total, habit) => total + habit.completed, 0) || 0;
  const moodCount = Object.keys(data?.moods || {}).length;

  return (
    <section className="summary-grid" aria-label="Resumo do mês">
      <div className="summary-card">
        <span className="summary-icon">
          <BookOpen size={20} />
        </span>
        <div>
          <span className="summary-label">HÁBITOS NO MÊS</span>
          <strong>
            {data ? String(data.habits.length).padStart(2, '0') : '—'}
            <small>hábitos acompanhados</small>
          </strong>
        </div>
      </div>
      <div className="summary-card">
        <span className="summary-icon completions">
          <Check size={20} />
        </span>
        <div>
          <span className="summary-label">CONCLUSÕES</span>
          <strong>
            {data ? String(completed).padStart(2, '0') : '—'}
            <small>hábitos concluídos</small>
          </strong>
        </div>
      </div>
      <div className="summary-card">
        <span className="summary-icon completion-rate">
          <ChartNoAxesCombined size={20} />
        </span>
        <div>
          <span className="summary-label">CONCLUSÃO DO MÊS</span>
          <strong>
            {eligibleDays
              ? `${Math.round((completed / eligibleDays) * 100)}%`
              : '—'}
            <small>dos dias previstos até hoje</small>
          </strong>
        </div>
      </div>
      <div className="summary-card">
        <span className="summary-icon mood">
          <Smile size={20} />
        </span>
        <div>
          <span className="summary-label">HUMOR REGISTRADO</span>
          <strong>
            {data ? String(moodCount).padStart(2, '0') : '—'}
            <small>com humor registrado</small>
          </strong>
        </div>
      </div>
    </section>
  );
}
