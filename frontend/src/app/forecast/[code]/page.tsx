import Link from "next/link";
import type { Metadata } from "next";
import { api, ApiError } from "@/lib/api";
import { ForecastChart } from "@/components/charts";
import { Badge, Card, EmptyState, SectionHeading, StatTile, WarningList } from "@/components/ui";
import {
  changeColor,
  formatDate,
  formatDateTime,
  formatNumber,
  formatPercent,
  formatPrice,
} from "@/lib/format";
import type { Forecast, Series } from "@/lib/types";

export const revalidate = 300;

const MODELS = [
  { key: "AUTO", label: "Tự chọn" },
  { key: "NAIVE", label: "Naive" },
  { key: "DRIFT", label: "Drift" },
  { key: "SMA", label: "SMA" },
  { key: "HOLT_DAMPED", label: "Holt" },
  { key: "AR_DIFF", label: "AR(p)" },
];

const HORIZONS = [7, 14, 30, 60, 90];

type PageProps = {
  params: Promise<{ code: string }>;
  searchParams: Promise<{ model?: string; horizon?: string }>;
};

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { code } = await params;
  return { title: `Dự báo ${decodeURIComponent(code).toUpperCase()}` };
}

export default async function ForecastPage({ params, searchParams }: PageProps) {
  const { code } = await params;
  const search = await searchParams;

  const model = MODELS.some((option) => option.key === search.model) ? search.model : "AUTO";
  const parsedHorizon = Number.parseInt(search.horizon ?? "", 10);
  const horizon = HORIZONS.includes(parsedHorizon) ? parsedHorizon : 14;

  let forecast: Forecast;
  try {
    forecast = await api.forecast(code, model, horizon);
  } catch (error) {
    if (error instanceof ApiError && error.isInsufficientHistory) {
      return (
        <EmptyState
          title="Chưa đủ dữ liệu để dự báo"
          description={
            <>
              <p>{error.message}</p>
              <p className="mt-2">
                Đây là hành vi cố ý: một dự báo khớp trên vài quan sát không phải là dự báo
                yếu, mà là dự báo vô nghĩa. Hệ thống từ chối đưa ra con số thay vì hiển thị
                nó kèm khoảng tin cậy rộng.
              </p>
            </>
          }
          action={{ href: `/instruments/${code}`, label: "Xem lịch sử giá" }}
        />
      );
    }
    if (error instanceof ApiError && error.isNotFound) {
      return (
        <EmptyState
          title="Không có chuỗi giá này"
          description={`Mã '${code}' chưa được khai báo.`}
          action={{ href: "/", label: "Về trang tổng quan" }}
        />
      );
    }
    return (
      <EmptyState
        title="Không tạo được dự báo"
        description={error instanceof ApiError ? error.message : "Lỗi không xác định."}
        action={{ href: "/", label: "Về trang tổng quan" }}
      />
    );
  }

  // Enough history to give the forecast visual context without dwarfing it.
  let history: Series | null = null;
  try {
    history = await api.prices(code, Math.max(90, horizon * 4));
  } catch {
    history = null;
  }

  const { instrument, accuracy } = forecast;
  const finalStep = forecast.points[forecast.points.length - 1];

  return (
    <div className="space-y-6">
      <SectionHeading
        title={`Dự báo — ${instrument.name}`}
        description={
          <>
            Mô hình {forecast.modelLabel}, khớp trên {forecast.trainSize} quan sát tính đến{" "}
            {formatDate(forecast.lastCloseOn)}. Tạo lúc {formatDateTime(forecast.generatedAt)}.
          </>
        }
        right={
          <Link href={`/instruments/${instrument.code}`} className="btn">
            ← Lịch sử giá
          </Link>
        }
      />

      <WarningList warnings={forecast.warnings} />

      <Card>
        <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <p className="label">Mô hình</p>
            <div className="mt-1.5 flex flex-wrap gap-1">
              {MODELS.map((option) => (
                <Link
                  key={option.key}
                  href={`/forecast/${instrument.code}?model=${option.key}&horizon=${horizon}`}
                  className={`btn ${option.key === model ? "btn-active" : ""}`}
                >
                  {option.label}
                </Link>
              ))}
            </div>
          </div>
          <div>
            <p className="label">Số ngày dự báo</p>
            <div className="mt-1.5 flex flex-wrap gap-1">
              {HORIZONS.map((option) => (
                <Link
                  key={option}
                  href={`/forecast/${instrument.code}?model=${model}&horizon=${option}`}
                  className={`btn ${option === horizon ? "btn-active" : ""}`}
                >
                  {option}
                </Link>
              ))}
            </div>
          </div>
        </div>
        <p className="mt-3 border-t border-border pt-3 text-xs leading-relaxed text-ink-subtle">
          {forecast.modelDescription}
        </p>
      </Card>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card>
          <StatTile
            label="Giá hiện tại"
            value={formatPrice(forecast.lastClose, instrument)}
            unit={instrument.unitLabel}
            hint={`Ngày ${formatDate(forecast.lastCloseOn)}`}
          />
        </Card>
        <Card>
          <StatTile
            label={`Dự báo sau ${horizon} bước`}
            value={formatPrice(finalStep?.value, instrument)}
            unit={instrument.unitLabel}
            change={finalStep?.changeFromLastPercent}
          />
        </Card>
        <Card>
          <StatTile
            label="Khoảng 95% cuối kỳ"
            value={
              finalStep?.lower95 != null && finalStep?.upper95 != null ? (
                <span className="text-base sm:text-lg">
                  {formatPrice(finalStep.lower95, instrument)} –{" "}
                  {formatPrice(finalStep.upper95, instrument)}
                </span>
              ) : (
                "—"
              )
            }
            hint="Suy ra từ sai số out-of-sample, không phải phần dư in-sample"
          />
        </Card>
        <Card>
          <StatTile
            label="MASE"
            value={formatNumber(accuracy.mase, 3)}
            hint={
              accuracy.beatsNaive
                ? "Dưới 1 — mô hình tốt hơn giả định giá đứng yên"
                : "Từ 1 trở lên — không tốt hơn giả định giá đứng yên"
            }
          />
        </Card>
      </div>

      <Card
        title="Giá thực tế và đường dự báo"
        subtitle="Vùng tô là khoảng tin cậy. Nếu vùng này rộng hơn nhiều so với mức thay đổi dự báo, con số điểm không nên được đọc như một mức giá cụ thể."
        action={
          <Badge tone={accuracy.beatsNaive ? "good" : "warn"}>
            {accuracy.beatsNaive ? "Vượt baseline naive" : "Không vượt baseline naive"}
          </Badge>
        }
      >
        <ForecastChart
          history={history?.points ?? []}
          forecast={forecast}
          scale={instrument.displayScale}
        />
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card
          title="Độ chính xác đo bằng backtest"
          subtitle={`Đánh giá rolling-origin trên ${accuracy.origins} điểm gốc, tổng ${accuracy.sampleSize} lần dự báo out-of-sample.`}
        >
          <dl className="grid grid-cols-2 gap-4 text-sm">
            <div>
              <dt className="label">MAE</dt>
              <dd className="tabular mt-1 font-medium">
                {formatNumber(accuracy.mae, instrument.displayScale)}{" "}
                <span className="text-xs text-ink-subtle">{instrument.unitLabel}</span>
              </dd>
            </div>
            <div>
              <dt className="label">RMSE</dt>
              <dd className="tabular mt-1 font-medium">
                {formatNumber(accuracy.rmse, instrument.displayScale)}
              </dd>
            </div>
            <div>
              <dt className="label">MAPE</dt>
              <dd className="tabular mt-1 font-medium">{formatNumber(accuracy.mape, 2)}%</dd>
            </div>
            <div>
              <dt className="label">MASE</dt>
              <dd className="tabular mt-1 font-medium">{formatNumber(accuracy.mase, 3)}</dd>
            </div>
          </dl>
          <p className="mt-4 border-t border-border pt-3 text-xs leading-relaxed text-ink-muted">
            MASE chia sai số tuyệt đối trung bình cho sai số mà dự báo naive mắc phải trên
            cùng chuỗi. Dưới 1 nghĩa là mô hình mang thêm thông tin; từ 1 trở lên thì không,
            dù MAPE trông đẹp đến đâu.
          </p>
        </Card>

        <Card
          title="Sai số theo số bước dự báo"
          subtitle="Sai số nở ra theo khoảng cách dự báo — đây là lý do khoảng tin cậy ngày thứ 30 rộng hơn hẳn ngày mai."
        >
          {forecast.accuracyByHorizon.length === 0 ? (
            <p className="text-xs text-ink-muted">Chưa đo được sai số theo bước.</p>
          ) : (
            <div className="max-h-72 overflow-y-auto">
              <table className="w-full border-collapse text-sm">
                <thead className="sticky top-0 bg-surface-raised">
                  <tr className="border-b border-border text-left">
                    <th className="py-2 pr-2 font-medium text-ink-subtle">Bước</th>
                    <th className="py-2 pr-2 text-right font-medium text-ink-subtle">MAE</th>
                    <th className="py-2 pr-2 text-right font-medium text-ink-subtle">RMSE</th>
                    <th className="py-2 pr-2 text-right font-medium text-ink-subtle">MAPE</th>
                    <th className="py-2 text-right font-medium text-ink-subtle">Mẫu</th>
                  </tr>
                </thead>
                <tbody>
                  {forecast.accuracyByHorizon.map((row) => (
                    <tr key={row.step} className="border-b border-border/50">
                      <td className="py-1.5 pr-2">{row.step}</td>
                      <td className="tabular py-1.5 pr-2 text-right">
                        {formatNumber(row.mae, instrument.displayScale)}
                      </td>
                      <td className="tabular py-1.5 pr-2 text-right">
                        {formatNumber(row.rmse, instrument.displayScale)}
                      </td>
                      <td className="tabular py-1.5 pr-2 text-right">
                        {formatNumber(row.mape, 2)}%
                      </td>
                      <td className="tabular py-1.5 text-right text-ink-subtle">
                        {row.sampleSize}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      {forecast.modelScores.length > 1 && (
        <Card
          title="So sánh các mô hình"
          subtitle="Mọi mô hình đều được backtest trên chính chuỗi này trước khi chọn. Mô hình được đánh dấu là mô hình có MASE thấp nhất."
        >
          <div className="-mx-4 overflow-x-auto sm:mx-0">
            <table className="w-full min-w-[520px] border-collapse text-sm">
              <thead>
                <tr className="border-b border-border text-left">
                  <th className="px-4 py-2 font-medium text-ink-subtle sm:px-2">Mô hình</th>
                  <th className="px-4 py-2 text-right font-medium text-ink-subtle sm:px-2">MASE</th>
                  <th className="px-4 py-2 text-right font-medium text-ink-subtle sm:px-2">RMSE</th>
                  <th className="px-4 py-2 text-right font-medium text-ink-subtle sm:px-2">MAPE</th>
                </tr>
              </thead>
              <tbody>
                {forecast.modelScores.map((score) => (
                  <tr
                    key={score.model}
                    className={`border-b border-border/50 ${score.selected ? "bg-gold/5" : ""}`}
                  >
                    <td className="px-4 py-2 sm:px-2">
                      <span className="font-medium">{score.modelLabel}</span>
                      {score.selected && (
                        <span className="ml-2">
                          <Badge tone="good">đang dùng</Badge>
                        </span>
                      )}
                    </td>
                    <td className="tabular px-4 py-2 text-right sm:px-2">
                      {formatNumber(score.mase, 3)}
                    </td>
                    <td className="tabular px-4 py-2 text-right sm:px-2">
                      {formatNumber(score.rmse, instrument.displayScale)}
                    </td>
                    <td className="tabular px-4 py-2 text-right sm:px-2">
                      {formatNumber(score.mape, 2)}%
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      <Card title="Chi tiết từng bước dự báo">
        <div className="-mx-4 overflow-x-auto sm:mx-0">
          <table className="w-full min-w-[640px] border-collapse text-sm">
            <thead>
              <tr className="border-b border-border text-left">
                <th className="px-4 py-2 font-medium text-ink-subtle sm:px-2">Ngày</th>
                <th className="px-4 py-2 text-right font-medium text-ink-subtle sm:px-2">
                  Dự báo
                </th>
                <th className="px-4 py-2 text-right font-medium text-ink-subtle sm:px-2">
                  So với hiện tại
                </th>
                <th className="px-4 py-2 text-right font-medium text-ink-subtle sm:px-2">
                  Khoảng 80%
                </th>
                <th className="px-4 py-2 text-right font-medium text-ink-subtle sm:px-2">
                  Khoảng 95%
                </th>
              </tr>
            </thead>
            <tbody>
              {forecast.points.map((step) => (
                <tr key={step.step} className="border-b border-border/50">
                  <td className="px-4 py-2 sm:px-2">{formatDate(step.date)}</td>
                  <td className="tabular px-4 py-2 text-right font-medium sm:px-2">
                    {formatPrice(step.value, instrument)}
                  </td>
                  <td
                    className={`tabular px-4 py-2 text-right sm:px-2 ${changeColor(
                      step.changeFromLastPercent,
                    )}`}
                  >
                    {formatPercent(step.changeFromLastPercent)}
                  </td>
                  <td className="tabular px-4 py-2 text-right text-ink-muted sm:px-2">
                    {formatPrice(step.lower80, instrument)} – {formatPrice(step.upper80, instrument)}
                  </td>
                  <td className="tabular px-4 py-2 text-right text-ink-muted sm:px-2">
                    {formatPrice(step.lower95, instrument)} – {formatPrice(step.upper95, instrument)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      {Object.keys(forecast.params).length > 0 && (
        <Card
          title="Tham số mô hình đã khớp"
          subtitle="Công khai để kết quả có thể kiểm chứng lại, thay vì phải tin vào một hộp đen."
        >
          <dl className="grid grid-cols-2 gap-3 text-xs sm:grid-cols-4">
            {Object.entries(forecast.params).map(([key, value]) => (
              <div key={key}>
                <dt className="font-mono text-ink-subtle">{key}</dt>
                <dd className="tabular mt-0.5 font-medium">{formatNumber(value, 4)}</dd>
              </div>
            ))}
          </dl>
        </Card>
      )}
    </div>
  );
}
