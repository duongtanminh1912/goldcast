import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import {
  changeColor,
  formatDate,
  formatDateTime,
  formatPercent,
  formatPrice,
  formatVndCompact,
} from "@/lib/format";
import { Badge, Card, EmptyState, SectionHeading, StatTile, WarningList } from "@/components/ui";
import type { MarketSummary } from "@/lib/types";

// Prices move through the day; a short window keeps the dashboard fresh without
// hammering the API on every request.
export const revalidate = 60;

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

  return (
    <div className="space-y-6">
      <SectionHeading
        title="Tổng quan thị trường vàng"
        description={
          <>
            Giá vàng thế giới, tỷ giá USD/VND và giá vàng miếng trong nước, cùng mức chênh
            lệch giữa giá trong nước và giá thế giới quy đổi. Cập nhật lúc{" "}
            {formatDateTime(summary.generatedAt)}.
          </>
        }
      />

      <WarningList warnings={summary.warnings} />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Card
          title="Vàng thế giới"
          subtitle={world ? `Cập nhật ${formatDate(world.asOf)} · nguồn ${world.source}` : undefined}
        >
          {world ? (
            <>
              <StatTile
                label="Giá spot"
                value={formatPrice(world.usdPerOunce, world.instrument)}
                unit={world.instrument.unitLabel}
                change={world.change1dPercent}
              />
              <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-border pt-3 text-xs">
                <div>
                  <dt className="text-ink-subtle">7 ngày</dt>
                  <dd className={`tabular font-medium ${changeColor(world.change7dPercent)}`}>
                    {formatPercent(world.change7dPercent)}
                  </dd>
                </div>
                <div>
                  <dt className="text-ink-subtle">30 ngày</dt>
                  <dd className={`tabular font-medium ${changeColor(world.change30dPercent)}`}>
                    {formatPercent(world.change30dPercent)}
                  </dd>
                </div>
              </dl>
            </>
          ) : (
            <p className="text-xs text-ink-muted">Chưa có dữ liệu.</p>
          )}
        </Card>

        <Card
          title="Tỷ giá USD/VND"
          subtitle={fx ? `Cập nhật ${formatDate(fx.asOf)} · nguồn ${fx.source}` : undefined}
        >
          {fx ? (
            <>
              <StatTile
                label="Tỷ giá"
                value={formatPrice(fx.vndPerUsd, fx.instrument)}
                unit={fx.instrument.unitLabel}
                change={fx.change30dPercent}
                hint="Thay đổi 30 ngày"
              />
              <p className="mt-4 border-t border-border pt-3 text-xs leading-relaxed text-ink-subtle">
                Tỷ giá quyết định giá thế giới quy ra đồng. Vàng thế giới đi ngang mà tỷ giá
                tăng thì giá quy đổi vẫn tăng.
              </p>
            </>
          ) : (
            <p className="text-xs text-ink-muted">Chưa có dữ liệu.</p>
          )}
        </Card>

        <Card
          title="Giá thế giới quy đổi"
          subtitle="Chưa gồm thuế, phí gia công hay chênh lệch của tiệm vàng"
        >
          {conversion ? (
            <>
              <StatTile
                label="Mỗi lượng"
                value={formatPrice(conversion.worldVndPerTael, { displayScale: 0 })}
                unit="VNĐ"
              />
              <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-border pt-3 text-xs">
                <div>
                  <dt className="text-ink-subtle">Mỗi chỉ</dt>
                  <dd className="tabular font-medium">
                    {formatPrice(conversion.worldVndPerChi, { displayScale: 0 })}
                  </dd>
                </div>
                <div>
                  <dt className="text-ink-subtle">Mỗi gram</dt>
                  <dd className="tabular font-medium">
                    {formatPrice(conversion.worldVndPerGram, { displayScale: 0 })}
                  </dd>
                </div>
              </dl>
              <p className="mt-3 font-mono text-[11px] leading-relaxed text-ink-subtle">
                {conversion.formula}
              </p>
            </>
          ) : (
            <p className="text-xs text-ink-muted">
              Cần cả giá vàng thế giới và tỷ giá mới quy đổi được.
            </p>
          )}
        </Card>
      </div>

      <Card
        title="Giá vàng trong nước"
        subtitle="Cột chênh lệch cho biết giá trong nước cao hơn giá thế giới quy đổi bao nhiêu — đây là phần biến động theo cung cầu và chính sách trong nước, không theo thị trường thế giới."
      >
        {domestic.length === 0 ? (
          <p className="text-xs leading-relaxed text-ink-muted">
            Chưa có dữ liệu giá trong nước. Nguồn SJC chỉ công bố giá của ngày hiện tại, nên
            chuỗi lịch sử sẽ dày lên dần sau mỗi lượt thu thập.
          </p>
        ) : (
          <div className="-mx-4 overflow-x-auto sm:mx-0">
            <table className="w-full min-w-[720px] border-collapse text-sm">
              <thead>
                <tr className="border-b border-border text-left">
                  <th className="px-4 py-2 font-medium text-ink-subtle sm:px-2">Thương hiệu</th>
                  <th className="px-4 py-2 text-right font-medium text-ink-subtle sm:px-2">
                    Mua vào
                  </th>
                  <th className="px-4 py-2 text-right font-medium text-ink-subtle sm:px-2">
                    Bán ra
                  </th>
                  <th className="px-4 py-2 text-right font-medium text-ink-subtle sm:px-2">
                    Chênh mua–bán
                  </th>
                  <th className="px-4 py-2 text-right font-medium text-ink-subtle sm:px-2">
                    So với thế giới
                  </th>
                  <th className="px-4 py-2 text-right font-medium text-ink-subtle sm:px-2">
                    1 ngày
                  </th>
                  <th className="px-4 py-2 text-right font-medium text-ink-subtle sm:px-2">
                    30 ngày
                  </th>
                  <th className="px-4 py-2 sm:px-2" />
                </tr>
              </thead>
              <tbody>
                {domestic.map((quote) => (
                  <tr key={quote.instrument.code} className="border-b border-border/60">
                    <td className="px-4 py-3 sm:px-2">
                      <p className="font-medium text-ink">{quote.instrument.name}</p>
                      <p className="text-xs text-ink-subtle">
                        {formatDate(quote.asOf)} · {quote.source}
                      </p>
                    </td>
                    <td className="tabular px-4 py-3 text-right sm:px-2">
                      {formatPrice(quote.buy, quote.instrument)}
                    </td>
                    <td className="tabular px-4 py-3 text-right font-medium sm:px-2">
                      {formatPrice(quote.sell, quote.instrument)}
                    </td>
                    <td className="tabular px-4 py-3 text-right text-ink-muted sm:px-2">
                      {formatVndCompact(quote.spreadVnd)}
                      {quote.spreadPercent != null && (
                        <span className="ml-1 text-xs text-ink-subtle">
                          ({formatPercent(quote.spreadPercent, 1)})
                        </span>
                      )}
                    </td>
                    <td className="tabular px-4 py-3 text-right sm:px-2">
                      {quote.premiumVnd != null ? (
                        <>
                          <span className={changeColor(quote.premiumVnd)}>
                            {quote.premiumVnd > 0 ? "+" : ""}
                            {formatVndCompact(quote.premiumVnd)}
                          </span>
                          <span className="ml-1 text-xs text-ink-subtle">
                            ({formatPercent(quote.premiumPercent, 1)})
                          </span>
                        </>
                      ) : (
                        <span className="text-ink-subtle">—</span>
                      )}
                    </td>
                    <td
                      className={`tabular px-4 py-3 text-right sm:px-2 ${changeColor(
                        quote.change1dPercent,
                      )}`}
                    >
                      {formatPercent(quote.change1dPercent)}
                    </td>
                    <td
                      className={`tabular px-4 py-3 text-right sm:px-2 ${changeColor(
                        quote.change30dPercent,
                      )}`}
                    >
                      {formatPercent(quote.change30dPercent)}
                    </td>
                    <td className="px-4 py-3 text-right sm:px-2">
                      <Link
                        href={`/instruments/${quote.instrument.code}`}
                        className="text-xs font-medium text-ink-muted underline decoration-dotted underline-offset-4 hover:text-ink"
                      >
                        Chi tiết
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <div className="grid gap-4 sm:grid-cols-2">
        <Card title="Xem biểu đồ và chỉ báo">
          <p className="text-xs leading-relaxed text-ink-muted">
            Lịch sử giá, đường trung bình động, RSI và Bollinger Bands cho từng chuỗi.
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <Link href="/instruments/XAUUSD" className="btn">
              Vàng thế giới
            </Link>
            <Link href="/instruments/SJC_HCM" className="btn">
              SJC TP.HCM
            </Link>
            <Link href="/instruments/USDVND" className="btn">
              USD/VND
            </Link>
          </div>
        </Card>

        <Card
          title="Dự báo có kiểm chứng"
          action={<Badge tone="warn">Không phải lời khuyên đầu tư</Badge>}
        >
          <p className="text-xs leading-relaxed text-ink-muted">
            Mỗi dự báo đi kèm sai số đo được bằng backtest rolling-origin và khoảng tin cậy
            suy ra từ chính sai số đó — bao gồm cả trường hợp mô hình không vượt được
            baseline naive.
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <Link href="/forecast/XAUUSD" className="btn">
              Dự báo vàng thế giới
            </Link>
            <Link href="/methodology" className="btn">
              Cách tính
            </Link>
          </div>
        </Card>
      </div>

      <p className="text-xs leading-relaxed text-ink-subtle">{summary.disclaimer}</p>
    </div>
  );
}

export const dynamic = "force-dynamic";
