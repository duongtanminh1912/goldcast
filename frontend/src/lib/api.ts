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
 * Every API call in this app runs in a Server Component, so the real address comes
 * from API_BASE_URL_INTERNAL, read at runtime. Nothing is baked into the image.
 *
 * The browser branch is deliberately an empty string -- a relative path. If someone
 * later writes a client component that calls this module, it will fail the same way
 * everywhere instead of working on the developer's machine and breaking on the server,
 * which is what a hardcoded localhost:8080 here would do.
 */
const BASE_URL =
  typeof window === "undefined"
    ? process.env.API_BASE_URL_INTERNAL ?? "http://localhost:8080"
    : "";

/**
 * Upper bound for one backend call, headers and body together. Without it a stalled JVM
 * holds every page render for undici's default of about five minutes, and requests pile
 * up in the Node process. It stays under the 15 s each page gets in check-pages.mjs.
 */
const TIMEOUT_MS = Number(process.env.API_TIMEOUT_MS) || 5000;

/** Shown to visitors; the URL and the cause go to the server log only. */
const UNREACHABLE = "Không kết nối được tới máy chủ dữ liệu. Vui lòng thử lại sau ít phút.";
const TOO_SLOW = "Máy chủ dữ liệu không phản hồi kịp. Vui lòng thử lại sau ít phút.";

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

function transportError(url: string, cause: unknown): ApiError {
  const timedOut = cause instanceof Error && cause.name === "TimeoutError";
  // The internal URL helps whoever reads the logs and nobody else.
  console.error(
    `API ${url}: ${timedOut ? `không phản hồi sau ${TIMEOUT_MS} ms` : "lỗi kết nối hoặc phản hồi hỏng"}`,
    cause,
  );
  return new ApiError(0, null, timedOut ? TOO_SLOW : UNREACHABLE);
}

async function request<T>(path: string, options: FetchOptions = {}): Promise<T> {
  const url = `${BASE_URL}${path}`;
  const revalidate = options.revalidate ?? 60;

  let response: Response;
  try {
    response = await fetch(url, {
      headers: { Accept: "application/json" },
      signal: options.signal ?? AbortSignal.timeout(TIMEOUT_MS),
      // next.revalidate is ignored outside the Next server, which is harmless.
      next: { revalidate },
    });
  } catch (cause) {
    throw transportError(url, cause);
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

  try {
    // The timeout also covers the body: a backend that sends headers and then stalls
    // must end the same way as one that never answers.
    return (await response.json()) as T;
  } catch (cause) {
    throw transportError(url, cause);
  }
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
