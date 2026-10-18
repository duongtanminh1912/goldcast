import { NextResponse } from "next/server";

export const dynamic = "force-dynamic";

// Set at container startup, so the same tested image can be promoted unchanged.
export async function GET() {
  const releaseId = process.env.GOLDCAST_RELEASE_ID;
  let backendReleaseId: string | null = null;
  if (releaseId) {
    try {
      const base = process.env.API_BASE_URL_INTERNAL ?? "http://localhost:8080";
      const response = await fetch(`${base}/api/v1/release`, {
        cache: "no-store", signal: AbortSignal.timeout(5000),
      });
      if (response.ok) backendReleaseId = (await response.json()).releaseId ?? null;
    } catch {
      // The public probe must fail when the frontend cannot reach its backend.
    }
  }
  return NextResponse.json(
    { releaseId: releaseId ?? null, backendReleaseId },
    {
      status: releaseId && backendReleaseId === releaseId ? 200 : 503,
      headers: { "Cache-Control": "no-store, max-age=0" },
    },
  );
}
