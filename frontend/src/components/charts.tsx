"use client";

import {
  Area,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { formatDate, formatNumber, formatPrice } from "@/lib/format";
import type { Forecast, Indicators, PricePoint } from "@/lib/types";

/**
 * Charts inherit their greys from `currentColor`, so the surrounding Tailwind text
 * colour drives them and light/dark themes need no JavaScript branch. Only the data
 * colours are fixed, and they are chosen to stay legible on both backgrounds.
 */
const GOLD = "#d4a017";
const BLUE = "#3b82f6";
const VIOLET = "#8b5cf6";
const TEAL = "#14b8a6";

interface TooltipEntry {
  name?: string | number;
  value?: number | string | Array<number | string>;
  color?: string;
  dataKey?: string | number;
}

function ChartTooltip({
  active,
  payload,
  label,
  scale,
}: {
  active?: boolean;
  payload?: TooltipEntry[];
  label?: string | number;
  scale: number;
}) {
  if (!active || !payload || payload.length === 0) {
    return null;
  }

  return (
    <div className="rounded-lg border border-border bg-surface-raised px-3 py-2 text-xs shadow-lg">
      <p className="mb-1 font-medium text-ink">{formatDate(String(label))}</p>
      <ul className="space-y-0.5">
        {payload.map((entry, index) => {
          const value = entry.value;
          if (value === undefined || value === null) {
            return null;
          }
          const text = Array.isArray(value)
            ? `${formatPrice(Number(value[0]), { displayScale: scale })} – ${formatPrice(
                Number(value[1]),
                { displayScale: scale },
              )}`
            : formatPrice(Number(value), { displayScale: scale });

          return (
            <li key={`${entry.dataKey}-${index}`} className="flex items-center gap-2">
              <span
                aria-hidden="true"
                className="inline-block h-2 w-2 shrink-0 rounded-full"
                style={{ backgroundColor: entry.color }}
              />
              <span className="text-ink-muted">{entry.name}</span>
              <span className="tabular ml-auto font-medium text-ink">{text}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** Y-axis ticks: VND amounts are unreadable in full, so they collapse to millions. */
function tickFormatter(scale: number) {
  return (value: number) => {
    if (scale === 0 && Math.abs(value) >= 1_000_000) {
      return `${formatNumber(value / 1_000_000, 1)}tr`;
    }
    return formatNumber(value, scale === 0 ? 0 : 1);
  };
}

const AXIS_PROPS = {
  stroke: "currentColor",
  strokeOpacity: 0.35,
  tick: { fill: "currentColor", fontSize: 11 },
  tickLine: false,
} as const;

export function PriceHistoryChart({
  points,
  scale,
  height = 320,
}: {
  points: PricePoint[];
  scale: number;
  height?: number;
}) {
  const data = points.map((point) => ({
    date: point.date,
    close: point.close,
    buy: point.buy ?? undefined,
  }));

  return (
    <div className="text-ink-subtle" style={{ width: "100%", height }}>
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
          <CartesianGrid stroke="currentColor" strokeOpacity={0.15} vertical={false} />
          <XAxis
            dataKey="date"
            {...AXIS_PROPS}
            minTickGap={40}
            tickFormatter={(value: string) => value.slice(5)}
          />
          <YAxis
            {...AXIS_PROPS}
            width={58}
            domain={["auto", "auto"]}
            tickFormatter={tickFormatter(scale)}
          />
          <Tooltip content={<ChartTooltip scale={scale} />} />
          <Line
            type="monotone"
            dataKey="close"
            name="Giá đóng cửa"
            stroke={GOLD}
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
          />
          {data.some((row) => row.buy !== undefined) && (
            <Line
              type="monotone"
              dataKey="buy"
              name="Giá mua vào"
              stroke={BLUE}
              strokeWidth={1.25}
              strokeDasharray="4 3"
              dot={false}
              isAnimationActive={false}
            />
          )}
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

/**
 * History and forecast on one axis, with the prediction band drawn behind the line.
 *
 * The band is the honest part of this chart. Drawing the point forecast alone would
 * suggest a precision the model does not have; the 95% band is usually wide enough that
 * the picture argues against over-reading the line.
 */
export function ForecastChart({
  history,
  forecast,
  scale,
  height = 360,
}: {
  history: PricePoint[];
  forecast: Forecast;
  scale: number;
  height?: number;
}) {
  interface Row {
    date: string;
    actual?: number;
    forecast?: number;
    band80?: [number, number];
    band95?: [number, number];
  }

  const rows: Row[] = history.map((point) => ({ date: point.date, actual: point.close }));

  // Repeat the last observed value as the forecast's origin so the two lines meet
  // instead of showing a visual gap at the boundary.
  if (rows.length > 0) {
    const last = rows[rows.length - 1];
    last.forecast = last.actual;
    last.band80 = [last.actual as number, last.actual as number];
    last.band95 = [last.actual as number, last.actual as number];
  }

  for (const step of forecast.points) {
    rows.push({
      date: step.date,
      forecast: step.value,
      band80:
        step.lower80 != null && step.upper80 != null ? [step.lower80, step.upper80] : undefined,
      band95:
        step.lower95 != null && step.upper95 != null ? [step.lower95, step.upper95] : undefined,
    });
  }

  return (
    <div className="text-ink-subtle" style={{ width: "100%", height }}>
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
          <CartesianGrid stroke="currentColor" strokeOpacity={0.15} vertical={false} />
          <XAxis
            dataKey="date"
            {...AXIS_PROPS}
            minTickGap={40}
            tickFormatter={(value: string) => value.slice(5)}
          />
          <YAxis
            {...AXIS_PROPS}
            width={58}
            domain={["auto", "auto"]}
            tickFormatter={tickFormatter(scale)}
          />
          <Tooltip content={<ChartTooltip scale={scale} />} />
          <Legend
            wrapperStyle={{ fontSize: 11, paddingTop: 8 }}
            iconType="plainline"
            iconSize={14}
          />
          <Area
            dataKey="band95"
            name="Khoảng tin cậy 95%"
            stroke="none"
            fill={GOLD}
            fillOpacity={0.12}
            isAnimationActive={false}
            connectNulls
          />
          <Area
            dataKey="band80"
            name="Khoảng tin cậy 80%"
            stroke="none"
            fill={GOLD}
            fillOpacity={0.22}
            isAnimationActive={false}
            connectNulls
          />
          <ReferenceLine
            x={forecast.lastCloseOn}
            stroke="currentColor"
            strokeOpacity={0.5}
            strokeDasharray="3 3"
            label={{
              value: "hôm nay",
              position: "insideTopRight",
              fill: "currentColor",
              fontSize: 10,
            }}
          />
          <Line
            type="monotone"
            dataKey="actual"
            name="Giá thực tế"
            stroke={GOLD}
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
          />
          <Line
            type="monotone"
            dataKey="forecast"
            name="Dự báo"
            stroke={VIOLET}
            strokeWidth={2}
            strokeDasharray="5 4"
            dot={false}
            isAnimationActive={false}
            connectNulls
          />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

export function IndicatorChart({
  indicators,
  scale,
  height = 340,
}: {
  indicators: Indicators;
  scale: number;
  height?: number;
}) {
  const rows = indicators.dates.map((date, index) => ({
    date,
    close: indicators.close[index] ?? undefined,
    sma20: indicators.sma20[index] ?? undefined,
    sma50: indicators.sma50[index] ?? undefined,
    band:
      indicators.bollingerLower[index] != null && indicators.bollingerUpper[index] != null
        ? ([indicators.bollingerLower[index], indicators.bollingerUpper[index]] as [
            number,
            number,
          ])
        : undefined,
  }));

  return (
    <div className="text-ink-subtle" style={{ width: "100%", height }}>
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
          <CartesianGrid stroke="currentColor" strokeOpacity={0.15} vertical={false} />
          <XAxis
            dataKey="date"
            {...AXIS_PROPS}
            minTickGap={40}
            tickFormatter={(value: string) => value.slice(5)}
          />
          <YAxis
            {...AXIS_PROPS}
            width={58}
            domain={["auto", "auto"]}
            tickFormatter={tickFormatter(scale)}
          />
          <Tooltip content={<ChartTooltip scale={scale} />} />
          <Legend wrapperStyle={{ fontSize: 11, paddingTop: 8 }} iconType="plainline" iconSize={14} />
          <Area
            dataKey="band"
            name="Bollinger (20, 2σ)"
            stroke="none"
            fill={TEAL}
            fillOpacity={0.12}
            isAnimationActive={false}
            connectNulls
          />
          <Line
            type="monotone"
            dataKey="close"
            name="Giá đóng cửa"
            stroke={GOLD}
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
          />
          <Line
            type="monotone"
            dataKey="sma20"
            name="SMA 20"
            stroke={BLUE}
            strokeWidth={1.4}
            dot={false}
            isAnimationActive={false}
            connectNulls
          />
          <Line
            type="monotone"
            dataKey="sma50"
            name="SMA 50"
            stroke={VIOLET}
            strokeWidth={1.4}
            dot={false}
            isAnimationActive={false}
            connectNulls
          />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

export function RsiChart({
  indicators,
  height = 160,
}: {
  indicators: Indicators;
  height?: number;
}) {
  const rows = indicators.dates.map((date, index) => ({
    date,
    rsi: indicators.rsi14[index] ?? undefined,
  }));

  return (
    <div className="text-ink-subtle" style={{ width: "100%", height }}>
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
          <CartesianGrid stroke="currentColor" strokeOpacity={0.15} vertical={false} />
          <XAxis
            dataKey="date"
            {...AXIS_PROPS}
            minTickGap={40}
            tickFormatter={(value: string) => value.slice(5)}
          />
          <YAxis {...AXIS_PROPS} width={34} domain={[0, 100]} ticks={[0, 30, 50, 70, 100]} />
          <Tooltip content={<ChartTooltip scale={1} />} />
          <ReferenceLine y={70} stroke="currentColor" strokeOpacity={0.4} strokeDasharray="3 3" />
          <ReferenceLine y={30} stroke="currentColor" strokeOpacity={0.4} strokeDasharray="3 3" />
          <Line
            type="monotone"
            dataKey="rsi"
            name="RSI 14"
            stroke={TEAL}
            strokeWidth={1.6}
            dot={false}
            isAnimationActive={false}
            connectNulls
          />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}
