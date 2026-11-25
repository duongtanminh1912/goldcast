"use client";

import { useEffect } from "react";

// A server-side failure reaches the browser with its message hidden by Next (only the
// digest survives), so the page says what it can in Vietnamese and shows the digest,
// which matches the line in the frontend log. The response itself is HTTP 500.
export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <div className="card card-pad text-center">
      <h1 className="text-sm font-semibold">Không tải được dữ liệu</h1>
      <p className="mx-auto mt-2 max-w-prose text-xs leading-relaxed text-ink-muted">
        Máy chủ dữ liệu chưa phản hồi. Nếu bạn vừa khởi động stack, backend có thể đang chạy
        lượt thu thập dữ liệu đầu tiên. Thử lại sau ít giây.
      </p>
      {error.digest && (
        <p className="mt-2 font-mono text-[11px] text-ink-muted">Mã lỗi: {error.digest}</p>
      )}
      <button type="button" onClick={reset} className="btn mt-4">
        Thử lại
      </button>
    </div>
  );
}
