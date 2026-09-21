"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const LINKS = [
  { href: "/", label: "Tổng quan" },
  { href: "/instruments/XAUUSD", label: "Vàng thế giới" },
  { href: "/instruments/SJC_HCM", label: "Vàng SJC" },
  { href: "/forecast/XAUUSD", label: "Dự báo" },
  { href: "/methodology", label: "Phương pháp" },
];

export function SiteHeader() {
  const pathname = usePathname();

  return (
    <header className="sticky top-0 z-20 border-b border-border bg-surface/85 backdrop-blur">
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-3 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
        <Link href="/" className="flex items-center gap-2">
          <span
            aria-hidden="true"
            className="inline-block h-5 w-5 rounded-full bg-gradient-to-br from-gold to-gold-deep"
          />
          <span className="text-base font-semibold tracking-tight">goldcast</span>
          <span className="hidden text-xs text-ink-subtle sm:inline">
            giá vàng &amp; dự báo thống kê
          </span>
        </Link>

        <nav aria-label="Điều hướng chính">
          <ul className="flex flex-wrap items-center gap-1 text-sm">
            {LINKS.map((link) => {
              // Exact match only: several links point into the same section, and a prefix
              // test would light up more than one of them at a time.
              const active = pathname === link.href;
              return (
                <li key={link.href}>
                  <Link
                    href={link.href}
                    aria-current={active ? "page" : undefined}
                    className={`rounded-lg px-2.5 py-1.5 transition-colors ${
                      active
                        ? "bg-gold/10 font-medium text-ink"
                        : "text-ink-muted hover:bg-border/40 hover:text-ink"
                    }`}
                  >
                    {link.label}
                  </Link>
                </li>
              );
            })}
          </ul>
        </nav>
      </div>
    </header>
  );
}
