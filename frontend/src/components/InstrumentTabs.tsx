import Link from "next/link";
import type { Instrument } from "@/lib/types";

const KIND_LABEL: Record<string, string> = {
  SPOT_GOLD: "Thế giới",
  VN_GOLD: "Trong nước",
  FX: "Tỷ giá",
};

/** Lets the reader hop between series without going back to the dashboard. */
export function InstrumentTabs({
  instruments,
  current,
  basePath,
  query = "",
}: {
  instruments: Instrument[];
  current: string;
  basePath: "/instruments" | "/forecast";
  query?: string;
}) {
  if (instruments.length < 2) {
    return null;
  }
  return (
    <nav aria-label="Chọn chuỗi giá" className="no-scrollbar -mx-4 mb-6 overflow-x-auto px-4">
      <ul className="flex w-max gap-2">
        {instruments.map((instrument) => {
          const active = instrument.code === current.toUpperCase();
          return (
            <li key={instrument.code}>
              <Link
                href={`${basePath}/${instrument.code}${query}`}
                aria-current={active ? "page" : undefined}
                className={`flex flex-col rounded-xl border px-3.5 py-2 text-left transition ${
                  active
                    ? "border-gold/60 bg-gold/10 shadow-sm"
                    : "border-border bg-surface-raised hover:border-gold/40"
                }`}
              >
                <span className="text-[10px] font-semibold uppercase tracking-wider text-ink-subtle">
                  {KIND_LABEL[instrument.kind] ?? instrument.kind}
                </span>
                <span className={`whitespace-nowrap text-sm ${active ? "font-semibold text-ink" : "text-ink-muted"}`}>
                  {instrument.name}
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
