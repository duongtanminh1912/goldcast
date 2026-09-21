/**
 * Mirrors the backend DTOs in `vn.goldcast.api.dto`.
 *
 * Keeping these hand-written rather than generated is a deliberate trade for a project
 * this size: one file to read, no build step. If the API grows, generate them from the
 * OpenAPI document at /v3/api-docs instead of letting the two drift apart by hand.
 */

export type InstrumentKind = "SPOT_GOLD" | "VN_GOLD" | "FX";

export interface Instrument {
  code: string;
  name: string;
  kind: InstrumentKind;
  currency: string;
  unit: string;
  unitLabel: string;
  region?: string | null;
  source: string;
  hasSpread: boolean;
  displayScale: number;
}

export interface PricePoint {
  date: string;
  close: number;
  buy?: number | null;
  sell?: number | null;
  spread?: number | null;
  source: string;
}

export interface Series {
  instrument: Instrument;
  points: PricePoint[];
  from?: string | null;
  to?: string | null;
  count: number;
  latestClose?: number | null;
  changeAbsolute?: number | null;
  changePercent?: number | null;
  sources: string[];
  containsSyntheticData: boolean;
}

export interface WorldQuote {
  instrument: Instrument;
  usdPerOunce: number;
  asOf: string;
  change1dPercent?: number | null;
  change7dPercent?: number | null;
  change30dPercent?: number | null;
  source: string;
}

export interface FxQuote {
  instrument: Instrument;
  vndPerUsd: number;
  asOf: string;
  change30dPercent?: number | null;
  source: string;
}

export interface DomesticQuote {
  instrument: Instrument;
  buy?: number | null;
  sell?: number | null;
  spreadVnd?: number | null;
  spreadPercent?: number | null;
  asOf: string;
  change1dPercent?: number | null;
  change7dPercent?: number | null;
  change30dPercent?: number | null;
  worldEquivalentVnd?: number | null;
  premiumVnd?: number | null;
  premiumPercent?: number | null;
  source: string;
}

export interface Conversion {
  worldVndPerTael: number;
  worldVndPerChi: number;
  worldVndPerGram: number;
  taelInGrams: number;
  troyOunceInGrams: number;
  formula: string;
}

export interface MarketSummary {
  generatedAt: string;
  world?: WorldQuote | null;
  fx?: FxQuote | null;
  domestic: DomesticQuote[];
  conversion?: Conversion | null;
  warnings: string[];
  disclaimer: string;
}

export type ForecastModelId =
  | "NAIVE"
  | "DRIFT"
  | "SMA"
  | "HOLT_DAMPED"
  | "AR_DIFF"
  | "AUTO";

export interface ForecastStep {
  step: number;
  date: string;
  value: number;
  lower80?: number | null;
  upper80?: number | null;
  lower95?: number | null;
  upper95?: number | null;
  changeFromLastPercent?: number | null;
}

export interface Accuracy {
  mae?: number | null;
  rmse?: number | null;
  mape?: number | null;
  mase?: number | null;
  sampleSize: number;
  origins: number;
  beatsNaive: boolean;
}

export interface HorizonAccuracy {
  step: number;
  mae?: number | null;
  rmse?: number | null;
  mape?: number | null;
  sampleSize: number;
}

export interface ModelScore {
  model: string;
  modelLabel: string;
  mase?: number | null;
  rmse?: number | null;
  mape?: number | null;
  selected: boolean;
}

export interface Forecast {
  instrument: Instrument;
  model: string;
  modelLabel: string;
  modelDescription: string;
  horizon: number;
  trainSize: number;
  lastClose: number;
  lastCloseOn: string;
  generatedAt: string;
  points: ForecastStep[];
  accuracy: Accuracy;
  accuracyByHorizon: HorizonAccuracy[];
  modelScores: ModelScore[];
  params: Record<string, number>;
  warnings: string[];
  basedOnSyntheticData: boolean;
}

export interface BacktestModelResult {
  model: string;
  modelLabel: string;
  description: string;
  mae?: number | null;
  rmse?: number | null;
  mape?: number | null;
  mase?: number | null;
  sampleSize: number;
  origins: number;
  beatsNaive: boolean;
  byHorizon: HorizonAccuracy[];
}

export interface Backtest {
  instrument: Instrument;
  horizon: number;
  seriesLength: number;
  minTrain: number;
  models: BacktestModelResult[];
  bestModel?: string | null;
  note: string;
}

export interface IndicatorSummary {
  latestClose?: number | null;
  sma20?: number | null;
  sma50?: number | null;
  rsi14?: number | null;
  trend: "UP" | "DOWN" | "SIDEWAYS" | "UNKNOWN";
  rsiState: "OVERBOUGHT" | "OVERSOLD" | "NEUTRAL" | "UNKNOWN";
  change7dPercent?: number | null;
  change30dPercent?: number | null;
  volatilityPct?: number | null;
  note: string;
}

export interface Indicators {
  instrument: Instrument;
  dates: string[];
  close: (number | null)[];
  sma20: (number | null)[];
  sma50: (number | null)[];
  ema20: (number | null)[];
  rsi14: (number | null)[];
  bollingerUpper: (number | null)[];
  bollingerMiddle: (number | null)[];
  bollingerLower: (number | null)[];
  summary: IndicatorSummary;
}

export interface ModelInfo {
  id: ForecastModelId;
  label: string;
  description: string;
  fittable: boolean;
}

/** RFC 7807 problem document, as returned by the backend's exception handler. */
export interface ApiProblem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  available?: number;
  required?: number;
}
