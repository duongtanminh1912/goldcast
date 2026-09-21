import type { Instrument } from "./types";

const VN = "vi-VN";

/**
 * Formats a price in the instrument's own unit and scale.
 *
 * The backend already carries `displayScale` per unit, so VND never shows decimals and
 * USD/oz always shows two. Keeping that decision server-side means the API and the UI
 * cannot disagree about it.
 */
export function formatPrice(
  value: number | null | undefined,
  instrument?: Pick<Instrument, "displayScale"> | null,
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "—";
  }
  const scale = instrument?.displayScale ?? 2;
  return new Intl.NumberFormat(VN, {
    minimumFractionDigits: scale,
    maximumFractionDigits: scale,
  }).format(value);
}

/** Large VND amounts read better as "80,5 triệu" than as nine digits. */
export function formatVndCompact(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "—";
  }
  const abs = Math.abs(value);
  if (abs >= 1_000_000_000) {
    return `${trim(value / 1_000_000_000)} tỷ`;
  }
  if (abs >= 1_000_000) {
    return `${trim(value / 1_000_000)} triệu`;
  }
  if (abs >= 1_000) {
    return `${trim(value / 1_000)} nghìn`;
  }
  return trim(value);
}

function trim(value: number): string {
  return new Intl.NumberFormat(VN, { maximumFractionDigits: 2 }).format(value);
}

export function formatNumber(
  value: number | null | undefined,
  digits = 2,
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "—";
  }
  return new Intl.NumberFormat(VN, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(value);
}

/** Signed percentage, e.g. "+1,25%". The sign is the point of it. */
export function formatPercent(
  value: number | null | undefined,
  digits = 2,
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "—";
  }
  const sign = value > 0 ? "+" : "";
  return `${sign}${new Intl.NumberFormat(VN, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(value)}%`;
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) {
    return "—";
  }
  const [year, month, day] = iso.slice(0, 10).split("-");
  if (!year || !month || !day) {
    return iso;
  }
  return `${day}/${month}/${year}`;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return "—";
  }
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) {
    return iso;
  }
  return new Intl.DateTimeFormat(VN, {
    dateStyle: "short",
    timeStyle: "short",
    timeZone: "Asia/Ho_Chi_Minh",
  }).format(parsed);
}

/** Tailwind text colour for a signed change: green up, red down, muted flat. */
export function changeColor(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value) || value === 0) {
    return "text-ink-muted";
  }
  return value > 0 ? "text-up" : "text-down";
}

export function trendLabel(trend: string): string {
  switch (trend) {
    case "UP":
      return "Xu hướng tăng";
    case "DOWN":
      return "Xu hướng giảm";
    case "SIDEWAYS":
      return "Đi ngang";
    default:
      return "Chưa xác định";
  }
}

export function rsiLabel(state: string): string {
  switch (state) {
    case "OVERBOUGHT":
      return "Vùng quá mua";
    case "OVERSOLD":
      return "Vùng quá bán";
    case "NEUTRAL":
      return "Trung tính";
    default:
      return "Chưa xác định";
  }
}
