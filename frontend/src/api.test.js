import assert from 'node:assert/strict';
import { test } from 'node:test';
import { api } from './api.js';

test('cookie de sessão acompanha requisições sem expor tokens ao JavaScript', async (t) => {
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/habits');
    assert.equal(options.credentials, 'same-origin');
    assert.equal(options.method, 'POST');
    assert.equal(options.headers['Content-Type'], 'application/json');
    assert.deepEqual(JSON.parse(options.body), { name: 'Ler' });
    return Response.json({ id: 7 });
  });
  assert.deepEqual(
    await api('/habits', { method: 'POST', body: { name: 'Ler' } }),
    { id: 7 },
  );
});

test('HTML recebido por engano não vira uma sessão ou tabela vazia', async (t) => {
  t.mock.method(
    globalThis,
    'fetch',
    async () => new Response('<html>Proxy error</html>'),
  );
  await assert.rejects(api('/auth/me'), /resposta inválida/);
});

test('erros da API preservam o status para tratar sessão expirada e limites', async (t) => {
  t.mock.method(globalThis, 'fetch', async () =>
    Response.json({ message: 'Aguarde.' }, { status: 429 }),
  );
  await assert.rejects(
    api('/auth/login'),
    (error) => error.status === 429 && error.message === 'Aguarde.',
  );
});

test('erro de rede e timeout têm mensagens distintas', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => {
    throw new TypeError('network');
  });
  await assert.rejects(api('/auth/me'), /Servidor indisponível/);
  globalThis.fetch.mock.mockImplementation(async () => {
    throw new DOMException('timeout', 'TimeoutError');
  });
  await assert.rejects(api('/auth/me'), /demorou a responder/);
});

test('cancelar uma navegação preserva AbortError', async (t) => {
  const controller = new AbortController();
  controller.abort();
  t.mock.method(globalThis, 'fetch', async () => {
    throw new DOMException('cancelled', 'AbortError');
  });
  await assert.rejects(
    api('/tracker', { signal: controller.signal }),
    (error) => error.name === 'AbortError',
  );
});
