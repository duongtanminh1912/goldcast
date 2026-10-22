"use client";

import { useEffect, useState } from "react";
import { formatPrice } from "@/lib/format";
import type { LiveGold } from "@/lib/types";
import { Sparkline } from "@/components/ui";

function sourceTime(value: string): string {
  return new Intl.DateTimeFormat("vi-VN", {
    dateStyle: "short", timeStyle: "medium", timeZone: "Asia/Ho_Chi_Minh",
  }).format(new Date(value));
}

export default function LiveWorldGold() {
  const [data, setData] = useState<LiveGold | null>(null);
  const [failed, setFailed] = useState(false);
  const [now, setNow] = useState(0);

  useEffect(() => {
    let stopped = false;
    let inFlight: AbortController | null = null;
    async function refresh() {
      if (inFlight || document.visibilityState === "hidden") return;
      const controller = new AbortController();
      inFlight = controller;
      const timeout = setTimeout(() => controller.abort(), 10_000);
      try {
        const response = await fetch("/api/v1/market/world/live?limit=120", {
          cache: "no-store", signal: controller.signal,
        });
        if (!response.ok) throw new Error("Live quote unavailable");
        const payload: LiveGold = await response.json();
        if (!stopped) { setData(payload); setFailed(false); setNow(Date.now()); }
      } catch {
        if (!stopped) setFailed(true);
      } finally {
        clearTimeout(timeout);
        inFlight = null;
      }
    }
    void refresh();
    const polling = setInterval(() => { void refresh(); }, 60_000);
    // Advance the age even when requests fail, so a cached quote cannot stay green forever.
    const age = setInterval(() => setNow(Date.now()), 15_000);
    const visible = () => { if (document.visibilityState === "visible") void refresh(); };
    document.addEventListener("visibilitychange", visible);
    return () => {
      stopped = true;
      clearInterval(polling); clearInterval(age);
      inFlight?.abort();
      document.removeEventListener("visibilitychange", visible);
    };
  }, []);

  const latest = data?.latest;
  const ageSeconds = latest ? Math.max(0, Math.floor((now - Date.parse(latest.observedAt)) / 1000)) : null;
  const stale = !!latest && ageSeconds !== null && ageSeconds > (data?.staleAfterSeconds ?? 180);
  const paused = stale && (data?.status === "SESSION_PAUSE" || !data?.referenceSession);
  const problem = failed || stale || (!!latest && !data?.lastFetchSucceeded);
  const healthy = !!latest && !!data?.enabled && !problem;
  const label = failed ? "Mất kết nối cập nhật" : data?.status === "DISABLED" ? "Đang tạm dừng"
    : !data ? "Đang kết nối" : !latest ? "Chưa có giá" : paused ? "Giá gần nhất ngoài phiên tham chiếu"
    : stale ? "Giá chưa được cập nhật" : !data.lastFetchSucceeded ? "Đang dùng giá đã lưu" : "Cập nhật mỗi phút";

  return (
    <section className="card relative overflow-hidden" aria-label="Giá vàng thế giới theo phút">
      <div aria-hidden="true" className="pointer-events-none absolute -right-20 -top-24 h-64 w-64 rounded-full bg-gold/15 blur-3xl" />
      <div className="relative p-5 sm:p-6">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="label">Giá vàng thế giới theo phút · XAU/USD</h2>
            <p className="mt-1 text-xs text-ink-subtle">Giá spot tham khảo · USD trên một troy ounce (31,1034768 g)</p>
          </div>
          <span role="status" className={`rounded-full px-3 py-1 text-xs font-medium ${healthy ? "bg-up/10 text-up" : "bg-gold/15 text-gold-deep"}`}>
            {label}
          </span>
        </div>
        <div className="mt-4 flex flex-wrap items-end gap-x-3 gap-y-2">
          <span className="tabular text-4xl font-bold tracking-tight sm:text-5xl">{formatPrice(latest?.usdPerOunce)}</span>
          <span className="pb-1.5 text-sm text-ink-subtle">USD/oz</span>
        </div>
        {data && latest ? (
          <>
            <p className="mt-2 text-xs text-ink-subtle">
              Nguồn cập nhật lúc {sourceTime(latest.observedAt)} (giờ Việt Nam) · đã {ageSeconds ?? 0} giây
            </p>
            {data.points.length >= 2 ? (
              <Sparkline values={data.points.map(point => point.usdPerOunce)} tone="gold" className="mt-5 h-28 w-full" />
            ) : (
              <p className="mt-5 rounded-lg bg-gold/5 p-3 text-xs leading-relaxed text-ink-subtle">
                Biểu đồ sẽ xuất hiện khi có quan sát ở ít nhất hai phút khác nhau.
              </p>
            )}
            <div className="mt-2 flex flex-wrap justify-between gap-2 text-xs text-ink-subtle">
              <span>{data.points.length} quan sát phút gần nhất</span>
              <a href="https://gold-api.com/" target="_blank" rel="noopener noreferrer" className="underline underline-offset-2">Nguồn: gold-api.com</a>
            </div>
          </>
        ) : (
          <p className="mt-3 text-sm text-ink-muted">{data?.status === "DISABLED" ? "Tính năng lấy giá theo phút đang tắt." : data ? "Chưa nhận được quan sát từ nguồn giá. Hệ thống sẽ thử lại ở lượt tiếp theo." : failed ? "Tạm thời chưa kết nối được dịch vụ giá vàng." : "Đang lấy giá vàng thế giới…"}</p>
        )}
        {problem && (
          <p className="mt-3 rounded-lg bg-gold/10 p-3 text-xs leading-relaxed text-ink-muted">
            {paused ? "Đang ngoài khung phiên giao dịch tham chiếu. Giá và thời điểm gần nhất được giữ nguyên; ngày lễ chưa được mô hình hóa."
              : failed || !data?.lastFetchSucceeded ? "Chưa lấy được cập nhật mới. Giá đã nhận trước đó được giữ kèm thời điểm nguồn công bố."
              : "Dữ liệu nguồn đã vượt ngưỡng độ mới. Giá hiển thị là quan sát gần nhất đã nhận."}
          </p>
        )}
        <p className="mt-4 border-t border-border pt-3 text-xs leading-relaxed text-ink-subtle">
          Quan sát theo phút phục vụ theo dõi giá hiện tại. Các thống kê, quy đổi và dự báo bên dưới dùng dữ liệu theo ngày, với thời điểm và nguồn riêng.
        </p>
      </div>
    </section>
  );
}
