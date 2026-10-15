import { useCallback, useEffect, useState } from 'react';
import { LoaderCircle } from 'lucide-react';
import { api } from './api.js';
import Brand from './components/Brand.jsx';
import Auth from './components/Auth.jsx';
import Tracker from './components/Tracker.jsx';

export default function App() {
  const [user, setUser] = useState(null);
  const [checking, setChecking] = useState(true);
  const [sessionError, setSessionError] = useState('');
  const [retry, setRetry] = useState(0);
  const logout = useCallback(() => setUser(null), []);
  useEffect(() => {
    const controller = new AbortController();
    setChecking(true);
    setSessionError('');
    api('/auth/me', { signal: controller.signal })
      .then(setUser)
      .catch((error) => {
        if (!controller.signal.aborted && error.status !== 401)
          setSessionError(error.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setChecking(false);
      });
    return () => controller.abort();
  }, [retry]);
  if (checking)
    return (
      <div className="min-h-screen grid place-items-center">
        <span className="flex items-center gap-3 muted">
          <LoaderCircle size={24} className="animate-spin" /> Carregando…
        </span>
      </div>
    );
  if (sessionError)
    return (
      <div className="min-h-screen grid place-items-center p-6">
        <div className="auth-card text-center">
          <Brand />
          <p className="my-6" role="alert">
            {sessionError}
          </p>
          <button
            className="primary"
            onClick={() => setRetry((value) => value + 1)}
          >
            Tentar novamente
          </button>
        </div>
      </div>
    );
  return user ? (
    <Tracker user={user} onLogout={logout} />
  ) : (
    <Auth onLogin={setUser} />
  );
}
