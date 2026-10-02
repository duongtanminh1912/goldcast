import { Card } from "@/components/ui";
import { formatNumber } from "@/lib/format";

/** Human names for the backend's MlFeatures, in the same order. */
const FEATURE_LABELS: Record<string, string> = {
  ret_lag1: "Lợi suất hôm nay",
  ret_lag2: "Lợi suất 1 phiên trước",
  ret_lag3: "Lợi suất 2 phiên trước",
  ret_lag4: "Lợi suất 3 phiên trước",
  ret_lag5: "Lợi suất 4 phiên trước",
  ret_mean5: "Lợi suất TB 5 phiên",
  ret_mean10: "Lợi suất TB 10 phiên",
  ret_mean20: "Lợi suất TB 20 phiên",
  vol10: "Độ biến động 10 phiên",
  vol20: "Độ biến động 20 phiên",
  dist_sma20: "Khoảng cách tới SMA20",
  rsi14: "RSI 14",
};

/** Splits "importance.rsi14" style keys off the flat params map the API returns. */
export function extractWeights(
  params: Record<string, number>,
  prefix: string,
): { key: string; value: number }[] {
  return Object.entries(params)
    .filter(([key]) => key.startsWith(prefix))
    .map(([key, value]) => ({ key: key.slice(prefix.length), value }));
}

/**
 * What the ML model learned, as bars.
 *
 * Gradient boosting reports importance (share of error reduction, all positive);
 * ridge reports standardised coefficients (signed). Both are sorted by magnitude so the
 * features doing the work sit on top.
 */
export function FeatureWeights({ params }: { params: Record<string, number> }) {
  const importance = extractWeights(params, "importance.");
  const betas = extractWeights(params, "beta.");
  const signed = importance.length === 0;
  const rows = (signed ? betas : importance)
    .slice()
    .sort((a, b) => Math.abs(b.value) - Math.abs(a.value));

  if (rows.length === 0) {
    return null;
  }
  const max = Math.max(...rows.map((row) => Math.abs(row.value)), Number.EPSILON);

  return (
    <Card
      title={signed ? "Hệ số mô hình Ridge" : "Độ quan trọng của đặc trưng"}
      subtitle={
        signed
          ? "Hệ số trên đặc trưng đã chuẩn hoá: thanh xanh đẩy dự báo lợi suất lên, thanh đỏ kéo xuống. Hệ số gần 0 nghĩa là λ đã triệt tiêu đặc trưng đó."
          : "Tỷ lệ mức giảm sai số bình phương mà mỗi đặc trưng đóng góp qua toàn bộ các cây. Tổng bằng 100%."
      }
    >
      <ul className="space-y-2.5">
        {rows.map((row) => {
          const width = (Math.abs(row.value) / max) * 100;
          const tone = !signed
            ? "from-gold to-gold-deep"
            : row.value >= 0
              ? "from-up/70 to-up"
              : "from-down/70 to-down";
          return (
            <li key={row.key} className="grid grid-cols-[minmax(0,11rem)_1fr_4.5rem] items-center gap-3 text-xs">
              <span className="truncate text-ink-muted" title={row.key}>
                {FEATURE_LABELS[row.key] ?? row.key}
              </span>
              <span className="h-2 overflow-hidden rounded-full bg-border/60">
                <span
                  className={`block h-full rounded-full bg-gradient-to-r ${tone}`}
                  style={{ width: `${width}%` }}
                />
              </span>
              <span className="tabular text-right font-semibold">
                {signed
                  ? `${row.value > 0 ? "+" : ""}${formatNumber(row.value * 10_000, 2)}`
                  : `${formatNumber(row.value * 100, 1)}%`}
              </span>
            </li>
          );
        })}
      </ul>
      {signed && (
        <p className="mt-3 text-[11px] text-ink-subtle">
          Đơn vị: điểm cơ bản lợi suất ngày (×10⁻⁴) cho mỗi độ lệch chuẩn của đặc trưng.
        </p>
      )}
    </Card>
  );
}
