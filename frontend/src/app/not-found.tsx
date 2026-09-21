import Link from "next/link";

export default function NotFound() {
  return (
    <div className="card card-pad text-center">
      <h1 className="text-sm font-semibold">Không tìm thấy trang</h1>
      <p className="mx-auto mt-2 max-w-prose text-xs leading-relaxed text-ink-muted">
        Đường dẫn này không tồn tại. Có thể bạn đang tìm một mã chuỗi giá khác.
      </p>
      <Link href="/" className="btn mt-4">
        Về trang tổng quan
      </Link>
    </div>
  );
}
