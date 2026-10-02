"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { ThemeToggle } from "./ThemeToggle";
import { Logo } from "./Logo";

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
    <header className="sticky top-0 z-30 border-b border-border/80 bg-surface/80 backdrop-blur-md">
      <div className="mx-auto flex w-full max-w-6xl items-center gap-4 px-4 py-3">
        <Link href="/" className="group flex shrink-0 items-center gap-2.5">
          <Logo className="h-7 w-7 transition-transform group-hover:rotate-[-8deg]" />
          <span className="leading-tight">
            <span className="block text-[15px] font-bold tracking-tight">goldcast</span>
            <span className="hidden text-[11px] text-ink-subtle sm:block">
              giá vàng &amp; dự báo thống kê
            </span>
          </span>
        </Link>

        <div className="ml-auto flex items-center gap-3">
          <nav aria-label="Điều hướng chính" className="hidden md:block">
            <NavLinks pathname={pathname} />
          </nav>
          <ThemeToggle />
        </div>
      </div>

      {/* On phones the links get their own scrollable row instead of wrapping into two. */}
      <nav aria-label="Điều hướng chính" className="border-t border-border/60 md:hidden">
        <div className="no-scrollbar mx-auto w-full max-w-6xl overflow-x-auto px-3 py-2">
          <NavLinks pathname={pathname} />
        </div>
      </nav>
    </header>
  );
}

function NavLinks({ pathname }: { pathname: string }) {
  return (
    <ul className="flex items-center gap-1 whitespace-nowrap text-sm">
      {LINKS.map((link) => {
        // Exact match only: several links point into the same section, and a prefix
        // test would light up more than one of them at a time.
        const active = pathname === link.href;
        return (
          <li key={link.href}>
            <Link
              href={link.href}
              aria-current={active ? "page" : undefined}
              className={`relative rounded-lg px-3 py-1.5 transition-colors ${
                active
                  ? "bg-gold/10 font-semibold text-ink"
                  : "text-ink-muted hover:bg-border/50 hover:text-ink"
              }`}
            >
              {link.label}
              {active && (
                <span
                  aria-hidden="true"
                  className="absolute inset-x-3 -bottom-[13px] hidden h-0.5 rounded-full bg-gold md:block"
                />
              )}
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
