import Link from "next/link";
import type { ReactNode } from "react";
import { changeColor, formatPercent } from "@/lib/format";

export function Card({
  title,
  subtitle,
  action,
  children,
  className = "",
}: {
  title?: ReactNode;
  subtitle?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`card card-pad ${className}`}>
      {(title || action) && (
        <header className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            {title && <h2 className="text-[15px] font-semibold tracking-tight text-ink">{title}</h2>}
            {subtitle && (
              <p className="mt-1 max-w-3xl text-xs leading-relaxed text-ink-subtle">{subtitle}</p>
            )}
          </div>
          {action}
        </header>
      )}
      {children}
    </section>
  );
}

/** Signed change as a coloured pill with an arrow, so direction survives colour blindness. */
export function ChangePill({
  value,
  digits = 2,
  size = "sm",
}: {
  value: number | null | undefined;
  digits?: number;
  size?: "sm" | "md";
}) {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return <span className="text-xs text-ink-subtle">—</span>;
  }
  const tone =
    value > 0
      ? "bg-up/10 text-up ring-up/20"
      : value < 0
        ? "bg-down/10 text-down ring-down/20"
        : "bg-border/50 text-ink-muted ring-border";
  const arrow = value > 0 ? "▲" : value < 0 ? "▼" : "•";
  const sizing = size === "md" ? "px-2 py-0.5 text-xs" : "px-1.5 py-px text-[11px]";
  return (
    <span
      className={`tabular inline-flex items-center gap-1 rounded-full font-semibold ring-1 ring-inset ${tone} ${sizing}`}
    >
      <span aria-hidden="true" className="text-[0.7em]">
        {arrow}
      </span>
      {formatPercent(value, digits)}
    </span>
  );
}

/** A headline figure with its label and optional signed change. */
export function StatTile({
  label,
  value,
  unit,
  change,
  hint,
  icon,
}: {
  label: string;
  value: ReactNode;
  unit?: string;
  change?: number | null;
  hint?: ReactNode;
  icon?: ReactNode;
}) {
  return (
    <div>
      <p className="label flex items-center gap-1.5">
        {icon && <span className="text-gold">{icon}</span>}
        {label}
      </p>
      <p className="mt-2 flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <span className="tabular text-2xl font-bold tracking-tight sm:text-[28px]">{value}</span>
        {unit && <span className="text-xs font-medium text-ink-subtle">{unit}</span>}
        {change !== undefined && change !== null && <ChangePill value={change} />}
      </p>
      {hint && <p className="mt-1.5 text-xs leading-relaxed text-ink-subtle">{hint}</p>}
    </div>
  );
}

/** Small labelled figure for secondary stats under a headline. */
export function MiniStat({
  label,
  value,
  className = "",
}: {
  label: ReactNode;
  value: ReactNode;
  className?: string;
}) {
  return (
    <div className="min-w-0">
      <dt className="text-[11px] text-ink-subtle">{label}</dt>
      <dd className={`tabular mt-0.5 text-sm font-semibold ${className}`}>{value}</dd>
    </div>
  );
}

/**
 * A price sparkline drawn as plain SVG on the server: no chart library, no hydration,
 * and it scales with its box. Colour follows the direction of the whole window.
 */
export function Sparkline({
  values,
  className = "h-12 w-full",
  tone,
}: {
  values: number[];
  className?: string;
  tone?: "up" | "down" | "gold";
}) {
  const clean = values.filter((value) => Number.isFinite(value));
  if (clean.length < 2) {
    return <div className={className} />;
  }

  const width = 120;
  const height = 40;
  const min = Math.min(...clean);
  const max = Math.max(...clean);
  const span = max - min || 1;
  const step = width / (clean.length - 1);
  const coords = clean.map((value, index) => [
    index * step,
    height - 3 - ((value - min) / span) * (height - 6),
  ]);
  const line = coords.map(([x, y], i) => `${i === 0 ? "M" : "L"}${x.toFixed(2)},${y.toFixed(2)}`).join("");
  const area = `${line}L${width},${height}L0,${height}Z`;

  const direction = tone ?? (clean[clean.length - 1] >= clean[0] ? "up" : "down");
  const colour =
    direction === "up" ? "rgb(var(--up))" : direction === "down" ? "rgb(var(--down))" : "rgb(var(--gold))";
  const gradientId = `spark-${direction}`;

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      aria-hidden="true"
      className={`overflow-visible ${className}`}
    >
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={colour} stopOpacity="0.22" />
          <stop offset="1" stopColor={colour} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#${gradientId})`} />
      <path
        d={line}
        fill="none"
        stroke={colour}
        strokeWidth="1.6"
        strokeLinejoin="round"
        strokeLinecap="round"
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  );
}

export function Badge({
  children,
  tone = "neutral",
}: {
  children: ReactNode;
  tone?: "neutral" | "good" | "bad" | "warn" | "gold";
}) {
  const tones: Record<string, string> = {
    neutral: "border-border bg-surface-sunken text-ink-muted",
    good: "border-up/30 bg-up/10 text-up",
    bad: "border-down/30 bg-down/10 text-down",
    warn: "border-warn/30 bg-warn/10 text-warn",
    gold: "border-gold/40 bg-gold/10 text-gold-deep",
  };
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-semibold ${tones[tone]}`}
    >
      {children}
    </span>
  );
}

/**
 * Warnings from the API, rendered as a block rather than a footnote.
 *
 * These carry things like "this model does not beat a naive forecast" and "this is
 * synthetic data" — exactly the information a prediction site is tempted to bury.
 */
export function WarningList({
  warnings,
  title = "Cần lưu ý",
}: {
  warnings: string[];
  title?: string;
}) {
  if (!warnings || warnings.length === 0) {
    return null;
  }
  return (
    <div
      role="note"
      className="flex gap-3 rounded-card border border-warn/30 bg-gradient-to-r from-warn/10 to-warn/[0.03] p-4"
    >
      <span
        aria-hidden="true"
        className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-warn/15 text-warn"
      >
        <svg viewBox="0 0 20 20" fill="currentColor" className="h-3.5 w-3.5">
          <path d="M10 2.5a1 1 0 0 1 .87.5l7 12.25A1 1 0 0 1 17 16.75H3a1 1 0 0 1-.87-1.5l7-12.25a1 1 0 0 1 .87-.5Zm0 4.5a.9.9 0 0 0-.9.9v3.6a.9.9 0 0 0 1.8 0V7.9a.9.9 0 0 0-.9-.9Zm0 8a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z" />
        </svg>
      </span>
      <div className="min-w-0">
        <p className="text-xs font-semibold uppercase tracking-wider text-warn">{title}</p>
        <ul className="mt-1.5 space-y-1">
          {warnings.map((warning) => (
            <li key={warning} className="text-sm leading-relaxed text-ink-muted">
              {warning}
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description: ReactNode;
  action?: { href: string; label: string };
}) {
  return (
    <div className="card mx-auto max-w-xl px-6 py-12 text-center">
      <span
        aria-hidden="true"
        className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-gold/10 text-gold"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" className="h-6 w-6">
          <path strokeLinecap="round" strokeLinejoin="round" d="M3 17l5-5 4 4 8-8M15 8h5v5" />
        </svg>
      </span>
      <p className="mt-4 text-base font-semibold text-ink">{title}</p>
      <div className="mx-auto mt-2 max-w-prose text-sm leading-relaxed text-ink-muted">
        {description}
      </div>
      {action && (
        <Link href={action.href} className="btn-primary mt-6">
          {action.label}
        </Link>
      )}
    </div>
  );
}

export function SectionHeading({
  title,
  description,
  right,
  eyebrow,
}: {
  title: string;
  description?: ReactNode;
  right?: ReactNode;
  eyebrow?: ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0">
        {eyebrow && <p className="mb-2 text-xs font-semibold text-gold-deep">{eyebrow}</p>}
        <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">{title}</h1>
        {description && (
          <p className="mt-2 max-w-2xl text-sm leading-relaxed text-ink-muted">{description}</p>
        )}
      </div>
      {right}
    </div>
  );
}

/** Live-ish indicator: a pulsing dot next to the data timestamp. */
export function UpdatedAt({ children }: { children: ReactNode }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-ink-subtle">
      <span className="relative flex h-2 w-2">
        <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-up/60" />
        <span className="relative inline-flex h-2 w-2 rounded-full bg-up" />
      </span>
      {children}
    </span>
  );
}
