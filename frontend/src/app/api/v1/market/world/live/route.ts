import { NextResponse } from "next/server";

export const dynamic = "force-dynamic";
const headers = { "Cache-Control": "no-store, max-age=0" };

/** A fixed read-only proxy; browsers never call the external provider or an admin route. */
export async function GET(request: Request) {
  const limit = new URL(request.url).searchParams.get("limit") ?? "120";
  if (!/^\d+$/.test(limit) || Number(limit) < 1 || Number(limit) > 1440) {
    return NextResponse.json({ detail: "Số quan sát phải từ 1 đến 1440." }, { status: 400, headers });
  }
  try {
    const base = process.env.API_BASE_URL_INTERNAL ?? "http://localhost:8080";
    const response = await fetch(`${base}/api/v1/market/world/live?limit=${Number(limit)}`, {
      cache: "no-store", redirect: "error", signal: AbortSignal.timeout(8000),
      headers: { Accept: "application/json" },
    });
    if (!response.ok) {
      return NextResponse.json({ detail: "Chưa lấy được giá vàng theo phút." }, { status: response.status, headers });
    }
    return NextResponse.json(await response.json(), { headers });
  } catch {
    return NextResponse.json({ detail: "Tạm thời không kết nối được dịch vụ giá vàng." }, { status: 503, headers });
  }
}
