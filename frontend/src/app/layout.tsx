import type { Metadata } from "next";
import "./globals.css";
import { SiteHeader } from "@/components/SiteHeader";

export const metadata: Metadata = {
  title: {
    default: "goldcast — Theo dõi & dự báo giá vàng",
    template: "%s · goldcast",
  },
  description:
    "Theo dõi giá vàng thế giới, tỷ giá USD/VND và giá vàng miếng trong nước, kèm dự báo thống kê có backtest và khoảng tin cậy đo từ sai số thực tế.",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="vi">
      <body className="min-h-dvh antialiased">
        <SiteHeader />
        <main className="mx-auto w-full max-w-6xl px-4 py-6 sm:py-8">{children}</main>
        <footer className="mx-auto w-full max-w-6xl px-4 pb-10 pt-4">
          <p className="border-t border-border pt-4 text-xs leading-relaxed text-ink-subtle">
            goldcast tổng hợp dữ liệu từ nguồn công khai và chạy các mô hình thống kê trên
            chính dữ liệu đó. Mọi con số ở đây chỉ mang tính tham khảo, không phải khuyến
            nghị mua bán, và không thay thế cho giá niêm yết chính thức tại quầy.
          </p>
        </footer>
      </body>
    </html>
  );
}
