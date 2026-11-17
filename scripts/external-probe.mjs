// External availability probe, run on GitHub's infrastructure so it keeps working when
// the Mac that hosts Prometheus is off. It applies the same definition of "healthy" as
// G3: frontend and backend agree on one release identity over public HTTPS, and the main
// pages render real content. Unlike G3 it does not know which release *should* run, so it
// only requires the two halves to agree with each other.
import { pathToFileURL } from 'node:url';
import { checkPages } from './check-pages.mjs';

const RELEASE_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$/;

export async function probe(base, { attempts = 3, retryDelayMs = 5000 } = {}) {
  if (!base || new URL(base).protocol !== 'https:') throw new Error('An https base URL is required');
  let identity = {};
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt++) {
    try {
      const response = await fetch(base + '/api/release', {
        signal: AbortSignal.timeout(15000), redirect: 'error',
        headers: { 'Cache-Control': 'no-cache' },
      });
      identity = response.ok ? await response.json() : {};
      if (response.ok) break;
      lastError = new Error(`/api/release returned HTTP ${response.status}`);
    } catch (error) {
      lastError = error;
    }
    if (attempt < attempts) await new Promise(resolve => setTimeout(resolve, retryDelayMs));
  }
  const releaseId = identity.releaseId;
  if (!releaseId) throw lastError ?? new Error('/api/release has no releaseId');
  if (!RELEASE_PATTERN.test(releaseId)) throw new Error('Invalid public release identity');
  if (identity.backendReleaseId !== releaseId) {
    throw new Error(`Frontend ${releaseId} and backend ${identity.backendReleaseId} disagree`);
  }
  await checkPages(base, { releaseId, attempts, retryDelayMs });
  return releaseId;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const releaseId = await probe(process.argv[2]);
    console.log(`UP ${process.argv[2]} release ${releaseId}`);
  } catch (error) {
    console.log(`DOWN ${process.argv[2]}: ${error.message}`);
    process.exitCode = 1;
  }
}
