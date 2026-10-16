export async function api(path, { method = 'GET', body, signal } = {}) {
  let response;
  try {
    const timeout = AbortSignal.timeout(25_000);
    response = await fetch(`/api${path}`, {
      method,
      credentials: 'same-origin',
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (error) {
    if (signal?.aborted) throw error;
    if (error.name === 'TimeoutError')
      throw new Error('O servidor demorou a responder. Tente novamente.');
    throw new Error('Servidor indisponível. Tente novamente em instantes.');
  }
  const data = await response.json().catch(() => null);
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    throw new Error(
      'O servidor enviou uma resposta inválida. Tente novamente.',
    );
  }
  if (!response.ok) {
    const error = new Error(
      data.message || 'Não foi possível concluir a operação.',
    );
    error.status = response.status;
    throw error;
  }
  return data;
}
