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
        <header className="mb-3 flex items-start justify-between gap-3">
          <div>
            {title && <h2 className="text-sm font-semibold text-ink">{title}</h2>}
            {subtitle && (
              <p className="mt-0.5 text-xs leading-relaxed text-ink-subtle">{subtitle}</p>
            )}
          </div>
          {action}
        </header>
      )}
      {children}
    </section>
  );
}

/** A headline figure with its label and optional signed change. */
export function StatTile({
  label,
  value,
  unit,
  change,
  hint,
}: {
  label: string;
  value: ReactNode;
  unit?: string;
  change?: number | null;
  hint?: ReactNode;
}) {
  return (
    <div>
      <p className="label">{label}</p>
      <p className="mt-1 flex items-baseline gap-1.5">
        <span className="tabular text-xl font-semibold tracking-tight sm:text-2xl">{value}</span>
        {unit && <span className="text-xs text-ink-subtle">{unit}</span>}
      </p>
      {change !== undefined && change !== null && (
        <p className={`tabular mt-0.5 text-xs font-medium ${changeColor(change)}`}>
          {formatPercent(change)}
        </p>
      )}
      {hint && <p className="mt-1 text-xs leading-relaxed text-ink-subtle">{hint}</p>}
    </div>
  );
}

export function Badge({
  children,
  tone = "neutral",
}: {
  children: ReactNode;
  tone?: "neutral" | "good" | "bad" | "warn";
}) {
  const tones: Record<string, string> = {
    neutral: "border-border text-ink-muted",
    good: "border-up/40 bg-up/10 text-up",
    bad: "border-down/40 bg-down/10 text-down",
    warn: "border-warn/40 bg-warn/10 text-warn",
  };
  return (
    <span
      className={`inline-flex items-center rounded-md border px-1.5 py-0.5 text-[11px] font-medium ${tones[tone]}`}
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
    <div className="rounded-card border border-warn/35 bg-warn/5 p-4">
      <p className="text-xs font-semibold uppercase tracking-wide text-warn">{title}</p>
      <ul className="mt-2 space-y-1.5">
        {warnings.map((warning) => (
          <li key={warning} className="flex gap-2 text-xs leading-relaxed text-ink-muted">
            <span aria-hidden="true" className="mt-[2px] text-warn">
              •
            </span>
            <span>{warning}</span>
          </li>
        ))}
      </ul>
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
    <div className="card card-pad text-center">
      <p className="text-sm font-semibold text-ink">{title}</p>
      <div className="mx-auto mt-2 max-w-prose text-xs leading-relaxed text-ink-muted">
        {description}
      </div>
      {action && (
        <Link href={action.href} className="btn mt-4">
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
}: {
  title: string;
  description?: ReactNode;
  right?: ReactNode;
}) {
  return (
    <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-lg font-semibold tracking-tight sm:text-xl">{title}</h1>
        {description && (
          <p className="mt-1 max-w-prose text-xs leading-relaxed text-ink-muted">
            {description}
          </p>
        )}
      </div>
      {right}
    </div>
  );
}
