/**
 * The sentence the server sent with a refused request, or a fallback when it sent none.
 *
 * Never Angular's own `err.message`: that reads "Http failure response for https://...: 400 OK",
 * which tells the user nothing and shows them the server's address. The server puts its sentence
 * under `message` or `error`, depending on the route; both are accepted.
 */
export function serverMessage(err: unknown, fallback: string): string {
  const body = (err as { error?: unknown } | null | undefined)?.error;
  if (body && typeof body === 'object') {
    const { message, error } = body as { message?: unknown; error?: unknown };
    for (const candidate of [message, error]) {
      if (typeof candidate === 'string' && candidate.trim().length > 0) {
        return candidate;
      }
    }
  }
  return fallback;
}
