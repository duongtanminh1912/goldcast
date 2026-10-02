import Link from "next/link";
import type { ReactNode } from "react";
import { api, ApiError } from "@/lib/api";
import {
  changeColor,
  formatDate,
  formatDateTime,
  formatPercent,
  formatPrice,
  formatVndCompact,
} from "@/lib/format";
import {
  Badge,
  Card,
  ChangePill,
  EmptyState,
  MiniStat,
  SectionHeading,
  Sparkline,
  StatTile,
  UpdatedAt,
  WarningList,
} from "@/components/ui";
import type { DomesticQuote, MarketSummary } from "@/lib/types";

// Prices move through the day; a short window keeps the dashboard fresh without
// hammering the API on every request.
export const revalidate = 60;

const SPARK_POINTS = 60;

/** Closing prices for the sparklines. A missing series just draws nothing. */
async function loadSparks(codes: string[]): Promise<Record<string, number[]>> {
  const results = await Promise.allSettled(codes.map((code) => api.prices(code, SPARK_POINTS)));
  const sparks: Record<string, number[]> = {};
  results.forEach((result, index) => {
    sparks[codes[index]] =
      result.status === "fulfilled" ? result.value.points.map((point) => point.close) : [];
  });
  return sparks;
}

export default async function DashboardPage() {
  let summary: MarketSummary;
  try {
    summary = await api.summary();
  } catch (error) {
    const message =
      error instanceof ApiError
        ? error.message
        : "Không lấy được dữ liệu thị trường.";
    return (
      <EmptyState
        title="Chưa hiển thị được dữ liệu"
        description={
          <>
            <p>{message}</p>
            <p className="mt-2">
              Nếu bạn vừa khởi động stack, backend có thể đang chạy lượt thu thập dữ liệu
              đầu tiên. Thử tải lại sau ít giây.
            </p>
          </>
        }
      />
    );
  }

  const { world, fx, conversion, domestic } = summary;
  const sparks = await loadSparks([
    world?.instrument.code ?? "XAUUSD",
    fx?.instrument.code ?? "USDVND",
    ...domestic.map((quote) => quote.instrument.code),
  ]);

  return (
    <div className="space-y-6">
      <SectionHeading
        eyebrow={<UpdatedAt>Cập nhật lúc {formatDateTime(summary.generatedAt)}</UpdatedAt>}
        title="Tổng quan thị trường vàng"
        description="Giá vàng thế giới, tỷ giá USD/VND và giá vàng miếng trong nước, cùng mức chênh lệch giữa giá trong nước và giá thế giới quy đổi."
        right={
          <div className="flex flex-wrap gap-2">
            <Link href="/methodology" className="btn">
              Cách tính
            </Link>
            <Link href="/forecast/XAUUSD" className="btn-primary">
              Xem dự báo
              <span aria-hidden="true">→</span>
            </Link>
          </div>
        }
      />

      <WarningList warnings={summary.warnings} />

      <div className="grid gap-4 lg:grid-cols-3">
        {/* Hero: world gold is the anchor every other number on the page is measured against. */}
        <section className="card relative overflow-hidden lg:col-span-2">
          <div
            aria-hidden="true"
            className="pointer-events-none absolute -right-24 -top-24 h-64 w-64 rounded-full bg-gold/15 blur-3xl"
          />
          <div className="relative flex h-full flex-col p-5 sm:p-6">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <p className="label">Vàng thế giới · XAU/USD</p>
                {world && (
                  <p className="mt-1 text-xs text-ink-subtle">
                    {formatDate(world.asOf)} · nguồn {world.source}
                  </p>
                )}
              </div>
              <Link
                href="/instruments/XAUUSD"
                className="text-xs font-medium text-ink-muted transition-colors hover:text-gold-deep"
              >
                Biểu đồ chi tiết →
              </Link>
            </div>

            {world ? (
              <>
                <div className="mt-4 flex flex-wrap items-end gap-x-3 gap-y-2">
                  <span className="tabular text-4xl font-bold tracking-tight sm:text-5xl">
                    {formatPrice(world.usdPerOunce, world.instrument)}
                  </span>
                  <span className="pb-1.5 text-sm font-medium text-ink-subtle">
                    {world.instrument.unitLabel}
                  </span>
                  <span className="pb-1.5">
                    <ChangePill value={world.change1dPercent} size="md" />
                  </span>
                </div>

                {/* Grows to fill the card, which the side column can make taller than its content. */}
                <div className="mt-5 min-h-28 flex-1 sm:min-h-32">
                  <Sparkline
                    values={sparks[world.instrument.code] ?? []}
                    tone="gold"
                    className="h-full w-full"
                  />
                </div>

                <dl className="mt-4 grid grid-cols-3 gap-4 border-t border-border pt-4">
                  <MiniStat
                    label="1 ngày"
                    value={formatPercent(world.change1dPercent)}
                    className={changeColor(world.change1dPercent)}
                  />
                  <MiniStat
                    label="7 ngày"
                    value={formatPercent(world.change7dPercent)}
                    className={changeColor(world.change7dPercent)}
                  />
                  <MiniStat
                    label="30 ngày"
                    value={formatPercent(world.change30dPercent)}
                    className={changeColor(world.change30dPercent)}
                  />
                </dl>
              </>
            ) : (
              <p className="mt-4 text-sm text-ink-muted">Chưa có dữ liệu vàng thế giới.</p>
            )}
          </div>
        </section>

        <div className="grid gap-4">
          <Card>
            <div className="flex items-start justify-between gap-3">
              <StatTile
                label="Tỷ giá USD/VND"
                value={fx ? formatPrice(fx.vndPerUsd, fx.instrument) : "—"}
                unit={fx?.instrument.unitLabel}
              />
              {fx && <ChangePill value={fx.change30dPercent} />}
            </div>
            <Sparkline values={sparks[fx?.instrument.code ?? "USDVND"] ?? []} className="mt-3 h-10 w-full" />
            <p className="mt-3 text-xs leading-relaxed text-ink-subtle">
              {fx ? `${formatDate(fx.asOf)} · nguồn ${fx.source} · thay đổi 30 ngày. ` : ""}
              Vàng thế giới đi ngang mà tỷ giá tăng thì giá quy đổi vẫn tăng.
            </p>
          </Card>

          <Card>
            <StatTile
              label="Thế giới quy đổi / lượng"
              value={conversion ? formatPrice(conversion.worldVndPerTael, { displayScale: 0 }) : "—"}
              unit="VNĐ"
            />
            {conversion ? (
              <>
                <dl className="mt-3 grid grid-cols-2 gap-3">
                  <MiniStat
                    label="Mỗi chỉ"
                    value={formatPrice(conversion.worldVndPerChi, { displayScale: 0 })}
                  />
                  <MiniStat
                    label="Mỗi gram"
                    value={formatPrice(conversion.worldVndPerGram, { displayScale: 0 })}
                  />
                </dl>
                <p className="mt-3 rounded-lg bg-surface-sunken px-2.5 py-2 font-mono text-[10.5px] leading-relaxed text-ink-subtle">
                  {conversion.formula}
                </p>
              </>
            ) : (
              <p className="mt-2 text-xs text-ink-muted">
                Cần cả giá vàng thế giới và tỷ giá mới quy đổi được.
              </p>
            )}
          </Card>
        </div>
      </div>

      <Card
        title="Giá vàng trong nước"
        subtitle="Thanh chênh lệch cho thấy giá bán trong nước cao hơn giá thế giới quy đổi bao nhiêu — phần này biến động theo cung cầu và chính sách trong nước, không theo thị trường thế giới."
        action={<Badge tone="gold">VNĐ/lượng</Badge>}
      >
        {domestic.length === 0 ? (
          <p className="text-sm leading-relaxed text-ink-muted">
            Chưa có dữ liệu giá trong nước. Nguồn SJC chỉ công bố giá của ngày hiện tại, nên
            chuỗi lịch sử sẽ dày lên dần sau mỗi lượt thu thập.
          </p>
        ) : (
          <>
            <DomesticTable domestic={domestic} sparks={sparks} />
            <div className="grid gap-3 md:hidden">
              {domestic.map((quote) => (
                <DomesticCard key={quote.instrument.code} quote={quote} spark={sparks[quote.instrument.code]} />
              ))}
            </div>
          </>
        )}
      </Card>

      <div className="grid gap-4 sm:grid-cols-2">
        <FeatureLink
          href="/instruments/XAUUSD"
          title="Biểu đồ và chỉ báo kỹ thuật"
          description="Lịch sử giá, đường trung bình động, RSI và Bollinger Bands cho từng chuỗi."
          icon={
            <path strokeLinecap="round" strokeLinejoin="round" d="M4 19h16M7 15l3-4 3 2 4-6" />
          }
          links={[
            { href: "/instruments/XAUUSD", label: "Vàng thế giới" },
            { href: "/instruments/SJC_HCM", label: "SJC TP.HCM" },
            { href: "/instruments/USDVND", label: "USD/VND" },
          ]}
        />
        <FeatureLink
          href="/forecast/XAUUSD"
          title="Dự báo có kiểm chứng"
          badge={<Badge tone="warn">Không phải lời khuyên đầu tư</Badge>}
          description="Mỗi dự báo đi kèm sai số đo bằng backtest rolling-origin và khoảng tin cậy suy ra từ chính sai số đó — kể cả khi mô hình không vượt được baseline naive."
          icon={
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M4 17c3-1 5-6 8-6s4 3 8 1M4 13c3-1 5-6 8-6s4 3 8 1"
              strokeDasharray="3 2"
            />
          }
          links={[
            { href: "/forecast/XAUUSD", label: "Dự báo vàng thế giới" },
            { href: "/methodology", label: "Cách tính" },
          ]}
        />
      </div>

      <p className="text-xs leading-relaxed text-ink-subtle">{summary.disclaimer}</p>
    </div>
  );
}

function DomesticTable({
  domestic,
  sparks,
}: {
  domestic: DomesticQuote[];
  sparks: Record<string, number[]>;
}) {
  // Bars share one scale so the premiums can be compared against each other at a glance.
  const maxPremium = Math.max(...domestic.map((quote) => Math.abs(quote.premiumPercent ?? 0)), 1);

  return (
    <div className="-mx-6 hidden overflow-x-auto md:block">
      <table className="w-full min-w-[860px] border-collapse text-sm">
        <thead>
          <tr className="border-y border-border bg-surface-sunken/70 text-left">
            <th className="table-head px-6 py-2.5">Thương hiệu</th>
            <th className="table-head px-3 py-2.5 text-right">Mua vào</th>
            <th className="table-head px-3 py-2.5 text-right">Bán ra</th>
            <th className="table-head px-3 py-2.5 text-right">Chênh mua–bán</th>
            <th className="table-head px-3 py-2.5">So với thế giới</th>
            <th className="table-head px-3 py-2.5 text-right">1 ngày</th>
            <th className="table-head px-3 py-2.5">30 ngày</th>
            <th className="px-6 py-2.5" />
          </tr>
        </thead>
        <tbody>
          {domestic.map((quote) => (
            <tr
              key={quote.instrument.code}
              className="group border-b border-border/60 transition-colors last:border-b-0 hover:bg-gold/[0.04]"
            >
              <td className="px-6 py-3.5">
                <p className="font-semibold text-ink">{quote.instrument.name}</p>
                <p className="text-xs text-ink-subtle">
                  {formatDate(quote.asOf)} · {quote.source}
                </p>
              </td>
              <td className="tabular px-3 py-3.5 text-right text-ink-muted">
                {formatPrice(quote.buy, quote.instrument)}
              </td>
              <td className="tabular px-3 py-3.5 text-right font-semibold">
                {formatPrice(quote.sell, quote.instrument)}
              </td>
              <td className="tabular px-3 py-3.5 text-right text-ink-muted">
                {formatVndCompact(quote.spreadVnd)}
                {quote.spreadPercent != null && (
                  <span className="ml-1 text-xs text-ink-subtle">
                    ({formatPercent(quote.spreadPercent, 1)})
                  </span>
                )}
              </td>
              <td className="px-3 py-3.5">
                <PremiumBar quote={quote} max={maxPremium} />
              </td>
              <td className="px-3 py-3.5 text-right">
                <ChangePill value={quote.change1dPercent} />
              </td>
              <td className="px-3 py-3.5">
                <div className="flex items-center gap-2">
                  <Sparkline values={sparks[quote.instrument.code] ?? []} className="h-7 w-20" />
                  <span className={`tabular text-xs font-semibold ${changeColor(quote.change30dPercent)}`}>
                    {formatPercent(quote.change30dPercent)}
                  </span>
                </div>
              </td>
              <td className="px-6 py-3.5 text-right">
                <Link
                  href={`/instruments/${quote.instrument.code}`}
                  aria-label={`Chi tiết ${quote.instrument.name}`}
                  className="inline-flex h-8 w-8 items-center justify-center rounded-full border border-border text-ink-muted transition group-hover:border-gold/60 group-hover:text-gold-deep"
                >
                  →
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function PremiumBar({ quote, max }: { quote: DomesticQuote; max: number }) {
  if (quote.premiumVnd == null) {
    return <span className="text-ink-subtle">—</span>;
  }
  const width = Math.min(100, (Math.abs(quote.premiumPercent ?? 0) / max) * 100);
  return (
    <div className="min-w-[150px]">
      <div className="flex items-baseline justify-between gap-2 text-xs">
        <span className="tabular font-semibold text-gold-deep">
          {quote.premiumVnd > 0 ? "+" : ""}
          {formatVndCompact(quote.premiumVnd)}
        </span>
        <span className="tabular text-ink-subtle">{formatPercent(quote.premiumPercent, 1)}</span>
      </div>
      <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-border/70">
        <div
          className="h-full rounded-full bg-gradient-to-r from-gold to-gold-deep"
          style={{ width: `${width}%` }}
        />
      </div>
    </div>
  );
}

function DomesticCard({ quote, spark }: { quote: DomesticQuote; spark?: number[] }) {
  return (
    <Link
      href={`/instruments/${quote.instrument.code}`}
      className="block rounded-xl border border-border bg-surface p-4 transition hover:border-gold/50"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="font-semibold text-ink">{quote.instrument.name}</p>
          <p className="text-xs text-ink-subtle">
            {formatDate(quote.asOf)} · {quote.source}
          </p>
        </div>
        <ChangePill value={quote.change1dPercent} />
      </div>
      <dl className="mt-3 grid grid-cols-2 gap-3">
        <MiniStat label="Mua vào" value={formatPrice(quote.buy, quote.instrument)} />
        <MiniStat label="Bán ra" value={formatPrice(quote.sell, quote.instrument)} />
      </dl>
      <div className="mt-3 flex items-end justify-between gap-3 border-t border-border pt-3">
        <div className="text-xs">
          <p className="text-ink-subtle">So với thế giới</p>
          <p className="tabular font-semibold text-gold-deep">
            {quote.premiumVnd != null
              ? `${quote.premiumVnd > 0 ? "+" : ""}${formatVndCompact(quote.premiumVnd)} (${formatPercent(quote.premiumPercent, 1)})`
              : "—"}
          </p>
        </div>
        <Sparkline values={spark ?? []} className="h-8 w-24" />
      </div>
    </Link>
  );
}

function FeatureLink({
  href,
  title,
  description,
  icon,
  badge,
  links,
}: {
  href: string;
  title: string;
  description: string;
  icon: ReactNode;
  badge?: ReactNode;
  links: { href: string; label: string }[];
}) {
  return (
    <section className="card card-pad group relative overflow-hidden">
      <div className="flex items-start gap-4">
        <span
          aria-hidden="true"
          className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-gold/20 to-gold/5 text-gold-deep ring-1 ring-inset ring-gold/25"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-5 w-5">
            {icon}
          </svg>
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="text-[15px] font-semibold tracking-tight">
              <Link href={href} className="hover:text-gold-deep">
                {title}
              </Link>
            </h2>
            {badge}
          </div>
          <p className="mt-1.5 text-sm leading-relaxed text-ink-muted">{description}</p>
          <div className="mt-4 flex flex-wrap gap-2">
            {links.map((link) => (
              <Link key={link.href} href={link.href} className="btn">
                {link.label}
              </Link>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}

export const dynamic = "force-dynamic";
