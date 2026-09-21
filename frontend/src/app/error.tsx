"use client";

import { useEffect } from "react";

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
      <h1 className="text-sm font-semibold">Trang này gặp lỗi</h1>
      <p className="mx-auto mt-2 max-w-prose text-xs leading-relaxed text-ink-muted">
        {error.message || "Đã có lỗi không xác định xảy ra."}
      </p>
      <button type="button" onClick={reset} className="btn mt-4">
        Thử lại
      </button>
    </div>
  );
}
