import Link from "next/link";
import type { Metadata } from "next";
import { api, ApiError } from "@/lib/api";
import { IndicatorChart, PriceHistoryChart, RsiChart } from "@/components/charts";
import { Badge, Card, EmptyState, SectionHeading, StatTile, WarningList } from "@/components/ui";
import {
  changeColor,
  formatDate,
  formatNumber,
  formatPercent,
  formatPrice,
  rsiLabel,
  trendLabel,
} from "@/lib/format";
import type { Indicators, Series } from "@/lib/types";

export const revalidate = 60;

const RANGES = [
  { key: "30", label: "1 tháng", limit: 30 },
  { key: "90", label: "3 tháng", limit: 90 },
  { key: "180", label: "6 tháng", limit: 180 },
  { key: "365", label: "1 năm", limit: 365 },
  { key: "max", label: "Tất cả", limit: 2000 },
];

type PageProps = {
  params: Promise<{ code: string }>;
  searchParams: Promise<{ range?: string }>;
};

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { code } = await params;
  return { title: `Biểu đồ ${decodeURIComponent(code).toUpperCase()}` };
}

export default async function InstrumentPage({ params, searchParams }: PageProps) {
  const { code } = await params;
  const { range } = await searchParams;

  const selected = RANGES.find((option) => option.key === range) ?? RANGES[2];

  let series: Series;
  try {
    series = await api.prices(code, selected.limit);
  } catch (error) {
    if (error instanceof ApiError && error.isNotFound) {
      return (
        <EmptyState
          title="Không có chuỗi giá này"
          description={`Mã '${code}' chưa được khai báo. Xem danh sách các chuỗi đang theo dõi ở trang tổng quan.`}
          action={{ href: "/", label: "Về trang tổng quan" }}
        />
      );
    }
    return (
      <EmptyState
        title="Không tải được dữ liệu"
        description={error instanceof ApiError ? error.message : "Lỗi không xác định."}
        action={{ href: "/", label: "Về trang tổng quan" }}
      />
    );
  }

  // Indicators need a longer history than the chart range; a short series is a normal
  // state for a freshly seeded instrument, not an error worth interrupting the page for.
  let indicators: Indicators | null = null;
  let indicatorNotice: string | null = null;
  try {
    indicators = await api.indicators(code, Math.max(selected.limit, 180));
  } catch (error) {
    indicatorNotice =
      error instanceof ApiError && error.isInsufficientHistory
        ? error.message
        : "Chưa tính được chỉ báo kỹ thuật cho chuỗi này.";
  }

  const { instrument } = series;
  const latest = series.points.length > 0 ? series.points[series.points.length - 1] : null;

  return (
    <div className="space-y-6">
      <SectionHeading
        title={instrument.name}
        description={
          <>
            {instrument.code} · đơn vị {instrument.unitLabel} · nguồn{" "}
            {series.sources.join(", ") || instrument.source}
            {series.from && series.to && (
              <>
                {" "}
                · dữ liệu từ {formatDate(series.from)} đến {formatDate(series.to)} (
                {series.count} quan sát)
              </>
            )}
          </>
        }
        right={
          <Link href={`/forecast/${instrument.code}`} className="btn">
            Xem dự báo →
          </Link>
        }
      />

      {series.containsSyntheticData && (
        <WarningList
          warnings={[
            'Chuỗi này chứa dữ liệu mô phỏng (source = "synthetic"), được sinh ra để ứng dụng chạy được khi chưa kết nối nguồn thật. Đừng diễn giải các con số như giá thị trường.',
          ]}
        />
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card>
          <StatTile
            label="Giá mới nhất"
            value={formatPrice(series.latestClose, instrument)}
            unit={instrument.unitLabel}
            hint={latest ? `Ngày ${formatDate(latest.date)}` : undefined}
          />
        </Card>
        <Card>
          <StatTile
            label={`Thay đổi trong ${selected.label.toLowerCase()}`}
            value={formatPercent(series.changePercent)}
            hint={
              series.changeAbsolute != null
                ? `${series.changeAbsolute > 0 ? "+" : ""}${formatPrice(
                    series.changeAbsolute,
                    instrument,
                  )} ${instrument.unitLabel}`
                : undefined
            }
          />
        </Card>
        {instrument.hasSpread && latest && (
          <Card>
            <StatTile
              label="Chênh lệch mua–bán"
              value={formatPrice(latest.spread, instrument)}
              unit={instrument.unitLabel}
              hint="Khoản mất ngay khi mua rồi bán lại"
            />
          </Card>
        )}
        {indicators && (
          <Card>
            <StatTile
              label="Biến động năm hoá"
              value={formatNumber(indicators.summary.volatilityPct, 1)}
              unit="%"
              hint="Độ lệch chuẩn của log-return, quy về năm"
            />
          </Card>
        )}
      </div>

      <Card
        title="Lịch sử giá"
        action={
          <div className="flex flex-wrap gap-1">
            {RANGES.map((option) => (
              <Link
                key={option.key}
                href={`/instruments/${instrument.code}?range=${option.key}`}
                className={`btn ${option.key === selected.key ? "btn-active" : ""}`}
              >
                {option.label}
              </Link>
            ))}
          </div>
        }
      >
        {series.points.length === 0 ? (
          <p className="text-xs text-ink-muted">Chưa có quan sát nào trong khoảng này.</p>
        ) : (
          <PriceHistoryChart points={series.points} scale={instrument.displayScale} />
        )}
      </Card>

      {indicators ? (
        <>
          <Card
            title="Chỉ báo kỹ thuật"
            subtitle={indicators.summary.note}
            action={
              <div className="flex flex-wrap gap-1.5">
                <Badge
                  tone={
                    indicators.summary.trend === "UP"
                      ? "good"
                      : indicators.summary.trend === "DOWN"
                        ? "bad"
                        : "neutral"
                  }
                >
                  {trendLabel(indicators.summary.trend)}
                </Badge>
                <Badge
                  tone={
                    indicators.summary.rsiState === "OVERBOUGHT"
                      ? "warn"
                      : indicators.summary.rsiState === "OVERSOLD"
                        ? "warn"
                        : "neutral"
                  }
                >
                  RSI {formatNumber(indicators.summary.rsi14, 0)} ·{" "}
                  {rsiLabel(indicators.summary.rsiState)}
                </Badge>
              </div>
            }
          >
            <IndicatorChart indicators={indicators} scale={instrument.displayScale} />

            <dl className="mt-4 grid grid-cols-2 gap-4 border-t border-border pt-4 text-xs sm:grid-cols-4">
              <div>
                <dt className="text-ink-subtle">SMA 20</dt>
                <dd className="tabular mt-0.5 font-medium">
                  {formatPrice(indicators.summary.sma20, instrument)}
                </dd>
              </div>
              <div>
                <dt className="text-ink-subtle">SMA 50</dt>
                <dd className="tabular mt-0.5 font-medium">
                  {formatPrice(indicators.summary.sma50, instrument)}
                </dd>
              </div>
              <div>
                <dt className="text-ink-subtle">Thay đổi 7 ngày</dt>
                <dd
                  className={`tabular mt-0.5 font-medium ${changeColor(
                    indicators.summary.change7dPercent,
                  )}`}
                >
                  {formatPercent(indicators.summary.change7dPercent)}
                </dd>
              </div>
              <div>
                <dt className="text-ink-subtle">Thay đổi 30 ngày</dt>
                <dd
                  className={`tabular mt-0.5 font-medium ${changeColor(
                    indicators.summary.change30dPercent,
                  )}`}
                >
                  {formatPercent(indicators.summary.change30dPercent)}
                </dd>
              </div>
            </dl>
          </Card>

          <Card
            title="RSI (14)"
            subtitle="Trên 70 thường được mô tả là quá mua, dưới 30 là quá bán. Đây là mô tả trạng thái chuỗi giá, không phải tín hiệu giao dịch."
          >
            <RsiChart indicators={indicators} />
          </Card>
        </>
      ) : (
        <Card title="Chỉ báo kỹ thuật">
          <p className="text-xs leading-relaxed text-ink-muted">{indicatorNotice}</p>
        </Card>
      )}
    </div>
  );
}
