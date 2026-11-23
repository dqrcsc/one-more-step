/** Bound memory while consuming responses, including responses without Content-Length. */
export async function readResponseBounded(response: Response, limit: number): Promise<Buffer> {
  const length = Number(response.headers.get("content-length"));
  if (length > limit) {
    await response.body?.cancel();
    throw new Error(`Response exceeds ${limit} byte limit`);
  }
  if (!response.body) return Buffer.alloc(0);
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) return Buffer.concat(chunks, total);
      total += value.byteLength;
      if (total > limit) throw new Error(`Response exceeds ${limit} byte limit`);
      chunks.push(value);
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
