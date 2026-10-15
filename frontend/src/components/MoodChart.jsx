import { BookOpen } from 'lucide-react';
import { moodNames, moodFaces } from '../calendar.js';
export default function MoodChart({ data, dates, onSelect, busy }) {
  const width = dates.length * 38;
  const points = dates.map((date, i) =>
    data.moods[date] ? [i * 38 + 19, 132 - (data.moods[date] - 1) * 25] : null,
  );
  // Only join consecutive recorded days; missing moods remain gaps.
  return (
    <div
      className="mood-grid"
      style={{ width: `calc(var(--habit-col) + ${width}px + 105px)` }}
    >
      <div className="mood-labels">
        <span className="eyebrow mb-4">COMO EU ME SENTI</span>
        {[4, 3, 2, 1, 0].map((i) => (
          <span key={i}>
            {moodFaces[i]} <span>{moodNames[i]}</span>
          </span>
        ))}
      </div>
      <div className="relative" style={{ width, height: 172 }}>
        <svg
          width={width}
          height="172"
          className="absolute inset-0 pointer-events-none"
          aria-hidden="true"
        >
          {[32, 57, 82, 107, 132].map((y) => (
            <line
              key={y}
              x1="0"
              x2={width}
              y1={y}
              y2={y}
              className="mood-guide"
              strokeDasharray="3 5"
            />
          ))}
          {points.map(
            (point, i) =>
              point &&
              points[i - 1] && (
                <line
                  key={`l${i}`}
                  x1={points[i - 1][0]}
                  y1={points[i - 1][1]}
                  x2={point[0]}
                  y2={point[1]}
                  className="mood-line"
                  strokeWidth="2"
                />
              ),
          )}
          {points.map(
            (p, i) =>
              p && (
                <circle
                  key={`p${i}`}
                  cx={p[0]}
                  cy={p[1]}
                  r="4.5"
                  className="mood-point"
                  strokeWidth="2"
                />
              ),
          )}
        </svg>
        <div className="flex absolute inset-0">
          {dates.map((date, i) => (
            <button
              key={date}
              disabled={busy || date > data.today}
              onClick={() => onSelect(date)}
              className={`mood-day ${date === data.today ? 'is-today' : ''}`}
              style={{ width: 38 }}
              title={`${i + 1}: ${data.moods[date] ? moodNames[data.moods[date] - 1] : 'Registrar humor'}`}
              aria-label={`Humor de ${date}: ${data.moods[date] ? moodNames[data.moods[date] - 1] : 'sem registro'}`}
            >
              <span className="mood-day-number">{i + 1}</span>
            </button>
          ))}
        </div>
      </div>
      <div className="mood-end">
        <BookOpen size={20} />
        <span>
          Registro
          <br />
          diário.
        </span>
      </div>
    </div>
  );
}
