import { BookOpen } from 'lucide-react';
import { moodNames, moodFaces } from '../calendar.js';

export default function MoodChart({ data, dates, onSelect, busy }) {
  const width = dates.length * 38;
  const points = dates.map((date, dayIndex) =>
    data.moods[date]
      ? [dayIndex * 38 + 19, 132 - (data.moods[date] - 1) * 25]
      : null,
  );
  return (
    <div
      className="mood-grid"
      style={{ width: `calc(var(--habit-col) + ${width}px + 105px)` }}
    >
      <div className="mood-labels">
        <span className="eyebrow mb-4">COMO EU ME SENTI</span>
        {[4, 3, 2, 1, 0].map((moodIndex) => (
          <span key={moodIndex}>
            {moodFaces[moodIndex]} <span>{moodNames[moodIndex]}</span>
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
            (point, dayIndex) =>
              point &&
              points[dayIndex - 1] && (
                <line
                  key={`line-${dayIndex}`}
                  x1={points[dayIndex - 1][0]}
                  y1={points[dayIndex - 1][1]}
                  x2={point[0]}
                  y2={point[1]}
                  className="mood-line"
                  strokeWidth="2"
                />
              ),
          )}
          {points.map(
            (point, dayIndex) =>
              point && (
                <circle
                  key={`point-${dayIndex}`}
                  cx={point[0]}
                  cy={point[1]}
                  r="4.5"
                  className="mood-point"
                  strokeWidth="2"
                />
              ),
          )}
        </svg>
        <div className="flex absolute inset-0">
          {dates.map((date, dayIndex) => (
            <button
              key={date}
              disabled={busy || date > data.today}
              onClick={() => onSelect(date)}
              className={`mood-day ${date === data.today ? 'is-today' : ''}`}
              style={{ width: 38 }}
              title={`${dayIndex + 1}: ${data.moods[date] ? moodNames[data.moods[date] - 1] : 'Registrar humor'}`}
              aria-label={`Humor de ${date}: ${data.moods[date] ? moodNames[data.moods[date] - 1] : 'sem registro'}`}
            >
              <span className="mood-day-number">{dayIndex + 1}</span>
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
