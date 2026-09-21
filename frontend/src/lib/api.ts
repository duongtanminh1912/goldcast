import type {
  ApiProblem,
  Backtest,
  Forecast,
  Indicators,
  Instrument,
  MarketSummary,
  ModelInfo,
  Series,
} from "./types";

/**
 * Server components talk to the backend over the compose network; the browser cannot
 * resolve that hostname, so it uses the public URL. Reading both keeps one deployment
 * working from inside and outside the container without a rewrite proxy.
 */
const BASE_URL =
  (typeof window === "undefined"
    ? process.env.API_BASE_URL_INTERNAL ?? process.env.NEXT_PUBLIC_API_BASE_URL
    : process.env.NEXT_PUBLIC_API_BASE_URL) ?? "http://localhost:8080";

/** An HTTP failure carrying the backend's problem document, when it sent one. */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: ApiProblem | null;

  constructor(status: number, problem: ApiProblem | null, fallback: string) {
    super(problem?.detail ?? problem?.title ?? fallback);
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
  }

  /** True when the series simply has not accumulated enough history yet. */
  get isInsufficientHistory(): boolean {
    return this.status === 422;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }
}

interface FetchOptions {
  /** Seconds to cache. 0 disables caching, which is what live prices want. */
  revalidate?: number;
  signal?: AbortSignal;
}

async function request<T>(path: string, options: FetchOptions = {}): Promise<T> {
  const url = `${BASE_URL}${path}`;
  const revalidate = options.revalidate ?? 60;

  let response: Response;
  try {
    response = await fetch(url, {
      headers: { Accept: "application/json" },
      signal: options.signal,
      // next.revalidate is ignored outside the Next server, which is harmless.
      next: { revalidate },
    });
  } catch (cause) {
    // A connection failure is by far the most common problem in local development,
    // so say which URL failed rather than surfacing a bare "fetch failed".
    throw new ApiError(
      0,
      null,
      `Không kết nối được tới API tại ${url}. Kiểm tra backend đã chạy chưa.`,
    );
  }

  if (!response.ok) {
    let problem: ApiProblem | null = null;
    try {
      problem = (await response.json()) as ApiProblem;
    } catch {
      problem = null;
    }
    throw new ApiError(response.status, problem, `API trả về lỗi ${response.status}`);
  }

  return (await response.json()) as T;
}

function query(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      search.set(key, String(value));
    }
  }
  const serialised = search.toString();
  return serialised ? `?${serialised}` : "";
}

export const api = {
  instruments(): Promise<Instrument[]> {
    return request<Instrument[]>("/api/v1/instruments", { revalidate: 300 });
  },

  summary(): Promise<MarketSummary> {
    return request<MarketSummary>("/api/v1/market/summary", { revalidate: 60 });
  },

  prices(code: string, limit = 365): Promise<Series> {
    return request<Series>(`/api/v1/prices/${encodeURIComponent(code)}${query({ limit })}`, {
      revalidate: 60,
    });
  },

  forecast(code: string, model?: string, horizon?: number): Promise<Forecast> {
    return request<Forecast>(
      `/api/v1/forecast/${encodeURIComponent(code)}${query({ model, horizon })}`,
      { revalidate: 300 },
    );
  },

  backtest(code: string, horizon?: number): Promise<Backtest> {
    return request<Backtest>(
      `/api/v1/backtest/${encodeURIComponent(code)}${query({ horizon })}`,
      { revalidate: 600 },
    );
  },

  indicators(code: string, lookback = 180): Promise<Indicators> {
    return request<Indicators>(
      `/api/v1/indicators/${encodeURIComponent(code)}${query({ lookback })}`,
      { revalidate: 300 },
    );
  },

  models(): Promise<ModelInfo[]> {
    return request<ModelInfo[]>("/api/v1/models", { revalidate: 3600 });
  },
};
