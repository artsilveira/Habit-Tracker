import { useState } from 'react';
import { ArrowRight, BookOpen } from 'lucide-react';
import { api } from '../api.js';
import Brand from './Brand.jsx';
import Footer from './Footer.jsx';
import BonfireIcon from './BonfireIcon.jsx';

export default function Auth({ onLogin }) {
  const [register, setRegister] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function submit(event) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    const values = Object.fromEntries(new FormData(event.currentTarget));
    values.timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    try {
      onLogin(
        await api(`/auth/${register ? 'register' : 'login'}`, {
          method: 'POST',
          body: values,
        }),
      );
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="min-h-screen">
      <header className="site-header">
        <Brand />
        <span className="header-note">
          <span className="status-dot" /> HÁBITOS · HUMOR · HISTÓRICO
        </span>
      </header>
      <main className="auth-layout">
        <section className="auth-story">
          <span className="eyebrow">
            <BonfireIcon size={20} /> SEU REGISTRO DIÁRIO
          </span>
          <h1>
            A better reflection.
            <br />
            <em>One day at a time</em>
          </h1>
          <p>
            Marque seus hábitos, registre o humor do dia e acompanhe o mês
            inteiro em uma página.
          </p>
          <div className="mini-notebook" aria-hidden="true">
            <div className="flex items-center justify-between mb-6">
              <span className="serif text-2xl">Registro da semana</span>
              <BookOpen size={20} />
            </div>
            <div className="mini-grid">
              <span>MEUS HÁBITOS</span>
              {[1, 2, 3, 4, 5, 6, 7].map((day) => (
                <span key={day}>{day}</span>
              ))}
              {['Ler algumas páginas', 'Treinar', 'Estudar Java'].map(
                (habitName, habitIndex) => (
                  <div className="contents" key={habitName}>
                    <span>{habitName}</span>
                    {[1, 2, 3, 4, 5, 6, 7].map((day) => (
                      <span className={day > 5 ? '' : 'mini-mark'} key={day}>
                        {day > 5 ? '' : (habitIndex + day) % 4 ? '✓' : '·'}
                      </span>
                    ))}
                  </div>
                ),
              )}
            </div>
            <div className="mini-footer">
              Uma linha por hábito. Uma coluna por dia.{' '}
              <BonfireIcon size={22} />
            </div>
          </div>
          <span className="story-caption">
            <BookOpen size={16} /> Um mês por página.
          </span>
        </section>
        <section className="auth-card">
          <span className="eyebrow">HABIT TRACKER</span>
          <h2>{register ? 'Crie sua conta.' : 'Entre no seu caderno.'}</h2>
          <p>
            {register
              ? 'Seus registros ficam vinculados à sua conta.'
              : 'Use seu email e senha.'}
          </p>
          <form onSubmit={submit} className="mt-8">
            <fieldset disabled={busy} className="space-y-4">
              {register && (
                <label>
                  Seu nome
                  <input
                    name="name"
                    autoComplete="name"
                    maxLength={100}
                    placeholder="Como podemos te chamar?"
                    required
                  />
                </label>
              )}
              <label>
                Email
                <input
                  name="email"
                  type="email"
                  autoComplete="email"
                  maxLength={255}
                  placeholder="voce@exemplo.com"
                  required
                />
              </label>
              <label>
                Senha
                <input
                  name="password"
                  type="password"
                  autoComplete={register ? 'new-password' : 'current-password'}
                  minLength={register ? 15 : 8}
                  maxLength={128}
                  placeholder={
                    register ? 'Pelo menos 15 caracteres' : 'Sua senha'
                  }
                  required
                />
              </label>
              {error && (
                <p className="error-message" role="alert">
                  {error}
                </p>
              )}
              <button
                disabled={busy}
                className="primary w-full justify-center"
                type="submit"
              >
                {busy
                  ? 'Só um instante…'
                  : register
                    ? 'Criar meu caderno'
                    : 'Entrar no meu caderno'}
                <ArrowRight size={17} />
              </button>
            </fieldset>
          </form>
          <p className="auth-switch">
            {register ? 'Já tem uma conta?' : 'Ainda não tem uma conta?'}{' '}
            <button
              disabled={busy}
              onClick={() => {
                setRegister(!register);
                setError('');
              }}
            >
              {register ? 'Entrar' : 'Criar conta'}
            </button>
          </p>
          <div className="auth-footnote">
            <BonfireIcon size={17} /> Seus registros ficam na sua conta.
          </div>
        </section>
      </main>
      <Footer />
    </div>
  );
}
