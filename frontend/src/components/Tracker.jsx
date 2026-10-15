import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ArrowDown,
  ArrowUp,
  ArrowRight,
  BookOpen,
  Check,
  ChevronLeft,
  ChevronRight,
  CircleHelp,
  LoaderCircle,
  LogOut,
  Plus,
  X,
} from 'lucide-react';
import { api } from '../api.js';
import BonfireIcon from './BonfireIcon.jsx';
import Brand from './Brand.jsx';
import Footer from './Footer.jsx';
import Modal from './Modal.jsx';
import HabitForm from './HabitForm.jsx';
import MonthGrid from './MonthGrid.jsx';
import MonthlySummary from './MonthlySummary.jsx';
import { moodNames, moodFaces, monthName, monthKey } from '../calendar.js';

export default function Tracker({ user, onLogout }) {
  const [month, setMonth] = useState(() => {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: user.timezone,
      year: 'numeric',
      month: '2-digit',
    }).formatToParts(new Date());
    return new Date(
      Number(parts.find((p) => p.type === 'year').value),
      Number(parts.find((p) => p.type === 'month').value) - 1,
      1,
    );
  });
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const [modal, setModal] = useState(null);
  const [entry, setEntry] = useState(null);
  const [moodDate, setMoodDate] = useState(null);
  const [showHelp, setShowHelp] = useState(false);
  const requestSequence = useRef(0);
  const selectedMonth = monthKey(month);

  const year = month.getFullYear();
  const monthNumber = month.getMonth() + 1;
  const load = useCallback(
    async (signal) => {
      const sequence = ++requestSequence.current;
      const next = await api(`/tracker?year=${year}&month=${monthNumber}`, {
        signal,
      });
      if (sequence === requestSequence.current) setData(next);
    },
    [year, monthNumber],
  );
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    setData(null);
    load(controller.signal)
      .catch((e) => {
        if (e.name !== 'AbortError') {
          if (e.status === 401) onLogout();
          else setError(e.message);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [load, retry, onLogout]);

  async function mutate(action, close = () => {}) {
    if (busy) return;
    setBusy(true);
    setError('');
    let saved = false;
    try {
      await action();
      saved = true;
      close();
      await load();
    } catch (e) {
      if (e.status === 401) onLogout();
      else {
        setError(
          saved
            ? 'Alteração salva, mas não foi possível atualizar a tabela. Clique em tentar novamente.'
            : e.message,
        );
        if (saved) setData(null);
      }
    } finally {
      setBusy(false);
    }
  }
  function navigate(offset) {
    if (!busy)
      setMonth(new Date(month.getFullYear(), month.getMonth() + offset, 1));
  }
  function chooseEntry(status) {
    const current = entry;
    mutate(
      () =>
        api(`/habits/${current.habit.id}/entries/${current.date}`, {
          method: status ? 'PUT' : 'DELETE',
          ...(status ? { body: { status } } : {}),
        }),
      () => setEntry(null),
    );
  }
  function chooseMood(level) {
    mutate(
      () =>
        api(`/moods/${moodDate}`, {
          method: level ? 'PUT' : 'DELETE',
          ...(level ? { body: { level } } : {}),
        }),
      () => setMoodDate(null),
    );
  }
  return (
    <div className="min-h-screen flex flex-col">
      <header className="site-header">
        <Brand />
        <div className="flex items-center gap-5">
          <span className="header-note">
            <span className="status-dot" /> Seu caderno pessoal
          </span>
          <span className="header-divider" />
          <span className="user-avatar" title={user.name}>
            {user.name.slice(0, 1).toUpperCase()}
          </span>
          <button
            aria-label="Sair da conta"
            title="Sair da conta"
            className="icon-button"
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              try {
                await api('/auth/logout', { method: 'POST' });
                onLogout();
              } catch (e) {
                setError(e.message);
                setBusy(false);
              }
            }}
          >
            <LogOut size={17} />
          </button>
        </div>
      </header>
      <main className="tracker-main flex-1">
        <section className="page-heading">
          <div>
            <span className="eyebrow">
              <span className="tiny-line" /> LOCK IN. IT’S JUST YOU OUT THERE.
            </span>
            <h1>
              BRICK BY BRICK,
              <br className="mobile-break" /> <em>DAY BY DAY.</em>
            </h1>
            <p>Olá, {user.name.split(' ')[0]}. Este é o seu registro do mês.</p>
          </div>
          <div className="heading-note">
            <BonfireIcon size={26} />
            <span>
              Screw perfection.
              <br />
              <strong>Just find a way to get it done.</strong>
            </span>
          </div>
        </section>
        <MonthlySummary data={data} />
        <section className="notebook" aria-label="Caderno mensal">
          <div className="notebook-toolbar">
            <div className="flex items-center gap-4">
              <div className="month-nav">
                <button
                  aria-label="Mês anterior"
                  className="icon-button"
                  disabled={
                    busy ||
                    (month.getFullYear() <= 1900 && month.getMonth() === 0)
                  }
                  onClick={() => navigate(-1)}
                >
                  <ChevronLeft size={18} />
                </button>
                <h2>
                  {monthName(month)} <span>{month.getFullYear()}</span>
                </h2>
                <button
                  aria-label="Próximo mês"
                  className="icon-button"
                  disabled={
                    busy ||
                    (month.getFullYear() >= 9999 && month.getMonth() === 11)
                  }
                  onClick={() => navigate(1)}
                >
                  <ChevronRight size={18} />
                </button>
              </div>
              <input
                aria-label="Escolher mês"
                className="month-picker"
                type="month"
                min="1900-01"
                max="9999-12"
                value={selectedMonth}
                disabled={busy}
                onChange={(e) => {
                  if (e.target.value) {
                    const [y, m] = e.target.value.split('-').map(Number);
                    if (y >= 1900 && y <= 9999) setMonth(new Date(y, m - 1, 1));
                  }
                }}
              />
            </div>
            <button
              className="primary"
              disabled={busy || !data}
              onClick={() => setModal({ type: 'new' })}
            >
              <Plus size={17} /> Novo hábito
            </button>
          </div>
          <div className="notebook-caption">
            <span>
              <BookOpen size={14} /> Meu acompanhamento mensal
            </span>
            <button className="text-button" onClick={() => setShowHelp(true)}>
              <CircleHelp size={14} /> Como usar
            </button>
          </div>
          {error && (
            <div className="error-banner" role="alert">
              {error}
              <button
                className="text-button"
                disabled={busy}
                onClick={() => setRetry((r) => r + 1)}
              >
                Tentar novamente
              </button>
            </div>
          )}
          {loading && (
            <div className="loading-state" role="status">
              <LoaderCircle size={25} className="animate-spin" /> Carregando
              registros…
            </div>
          )}
          {!loading && data && (
            <MonthGrid
              data={data}
              busy={busy}
              onNew={() => setModal({ type: 'new' })}
              onEdit={(habit) => setModal({ type: 'edit', habit })}
              onOptions={(habit, index) =>
                setModal({ type: 'options', habit, index })
              }
              onEntry={setEntry}
              onMood={setMoodDate}
            />
          )}
        </section>
        <div className="page-bottom">
          <p>
            <BonfireIcon size={22} />{' '}
            <strong>
              The world isn't going to change for you. Pick your target and
              strike.
            </strong>
          </p>
          <span className="eyebrow">GET ONE PERCENT BETTER TODAY</span>
        </div>
      </main>
      <Footer />
      {(modal?.type === 'new' || modal?.type === 'edit') && (
        <HabitForm
          error={error}
          habit={modal.habit}
          today={data.today}
          busy={busy}
          onClose={() => {
            if (!busy) setModal(null);
          }}
          onSave={(body) =>
            mutate(
              () =>
                api(modal.habit ? `/habits/${modal.habit.id}` : '/habits', {
                  method: modal.habit ? 'PATCH' : 'POST',
                  body,
                }),
              () => setModal(null),
            )
          }
        />
      )}
      {modal?.type === 'options' && (
        <Modal
          busy={busy}
          error={error}
          title={modal.habit.name}
          onClose={() => {
            if (!busy) setModal(null);
          }}
        >
          <div className="space-y-3">
            <button
              disabled={busy}
              className="choice"
              onClick={() => setModal({ ...modal, type: 'edit' })}
            >
              Editar nome e período <ArrowRight size={16} />
            </button>
            {[-1, 1].map((direction) => (
              <button
                key={direction}
                className="choice"
                disabled={
                  busy ||
                  modal.index + direction < 0 ||
                  modal.index + direction >= data.habits.length
                }
                onClick={() => {
                  const ids = data.habits.map((h) => h.id);
                  [ids[modal.index], ids[modal.index + direction]] = [
                    ids[modal.index + direction],
                    ids[modal.index],
                  ];
                  mutate(
                    () =>
                      api('/habits/order', { method: 'PUT', body: { ids } }),
                    () => setModal(null),
                  );
                }}
              >
                {direction < 0 ? 'Mover para cima' : 'Mover para baixo'}
                {direction < 0 ? (
                  <ArrowUp size={16} />
                ) : (
                  <ArrowDown size={16} />
                )}
              </button>
            ))}
            <button
              disabled={busy}
              className="choice danger"
              onClick={() => setModal({ ...modal, type: 'delete' })}
            >
              Excluir hábito e histórico <X size={16} />
            </button>
          </div>
        </Modal>
      )}
      {modal?.type === 'delete' && (
        <Modal
          busy={busy}
          error={error}
          title="Excluir este hábito?"
          onClose={() => {
            if (!busy) setModal(null);
          }}
        >
          <p className="muted leading-relaxed mb-6">
            {modal.habit.name} e todas as suas marcações serão excluídos
            definitivamente. Para preservar o histórico, edite a data final do
            hábito.
          </p>
          <div className="flex gap-3">
            <button
              disabled={busy}
              className="secondary flex-1"
              onClick={() => setModal({ ...modal, type: 'edit' })}
            >
              Editar período
            </button>
            <button
              disabled={busy}
              className="primary danger-bg flex-1"
              onClick={() =>
                mutate(
                  () => api(`/habits/${modal.habit.id}`, { method: 'DELETE' }),
                  () => setModal(null),
                )
              }
            >
              Excluir
            </button>
          </div>
        </Modal>
      )}
      {entry && (
        <Modal
          busy={busy}
          error={error}
          title="Como foi esse hábito?"
          onClose={() => {
            if (!busy) setEntry(null);
          }}
        >
          <p className="muted mb-5">
            {entry.habit.name} · {entry.date.split('-').reverse().join('/')}
          </p>
          <div className="space-y-3">
            <button
              disabled={busy}
              className="choice"
              onClick={() => chooseEntry('COMPLETED')}
            >
              <span className="flex gap-3">
                <Check size={20} /> Concluído
              </span>
              <span>Realizado neste dia.</span>
            </button>
            <button
              disabled={busy}
              className="choice"
              onClick={() => chooseEntry('NOT_COMPLETED')}
            >
              <span className="flex gap-3">
                <X size={20} /> Não realizado
              </span>
              <span>Não realizado neste dia.</span>
            </button>
            <button
              disabled={busy}
              className="choice muted"
              onClick={() => chooseEntry(null)}
            >
              Limpar registro <span>Deixar em branco</span>
            </button>
          </div>
        </Modal>
      )}
      {moodDate && (
        <Modal
          busy={busy}
          error={error}
          title="Como você se sentiu?"
          onClose={() => {
            if (!busy) setMoodDate(null);
          }}
        >
          <p className="muted mb-5">
            {moodDate.split('-').reverse().join('/')} · Escolha um humor para
            esta data.
          </p>
          <div className="space-y-2">
            {moodNames.map((m, i) => (
              <button
                key={m}
                disabled={busy}
                className={`choice ${data.moods[moodDate] === i + 1 ? 'selected' : ''}`}
                onClick={() => chooseMood(i + 1)}
              >
                <span className="flex items-center gap-3">
                  <span className="text-2xl">{moodFaces[i]}</span>
                  {m}
                </span>
                {data.moods[moodDate] === i + 1 && <Check size={17} />}
              </button>
            ))}
            <button
              disabled={busy}
              className="text-button mt-3"
              onClick={() => chooseMood(null)}
            >
              Limpar registro de humor
            </button>
          </div>
        </Modal>
      )}
      {showHelp && (
        <Modal title="Como usar a tabela" onClose={() => setShowHelp(false)}>
          <div className="space-y-4 muted leading-relaxed">
            <p>
              Cada linha é um hábito diário. Clique em uma célula para marcar
              como concluído, não realizado ou deixar em branco.
            </p>
            <p>
              Na área de humor, clique na coluna de um dia e escolha como se
              sentiu. Dias sem registro ficam sem ponto no gráfico.
            </p>
            <p>
              Dias futuros e dias fora do período do hábito ficam bloqueados.
              Você pode corrigir dias anteriores.
            </p>
            <p>
              O progresso considera os dias de acompanhamento até hoje,
              incluindo dias sem registro. Hábitos continuam nos meses seguintes
              até a data final.
            </p>
            <p>
              Para editar, reordenar ou excluir um hábito, use o botão de três
              pontos ao lado do nome.
            </p>
          </div>
        </Modal>
      )}
    </div>
  );
}
