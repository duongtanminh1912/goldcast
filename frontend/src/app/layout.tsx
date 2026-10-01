import type { Metadata, Viewport } from "next";
import { Be_Vietnam_Pro } from "next/font/google";
import "./globals.css";
import { SiteHeader } from "@/components/SiteHeader";
import { SiteFooter } from "@/components/SiteFooter";
import { THEME_INIT_SCRIPT } from "@/components/ThemeToggle";

// Be Vietnam Pro was drawn for Vietnamese diacritics, which system fonts often stack badly.
const sans = Be_Vietnam_Pro({
  subsets: ["latin", "vietnamese"],
  weight: ["400", "500", "600", "700"],
  variable: "--font-sans",
  display: "swap",
});

export const metadata: Metadata = {
  title: {
    default: "goldcast — Theo dõi & dự báo giá vàng",
    template: "%s · goldcast",
  },
  description:
    "Theo dõi giá vàng thế giới, tỷ giá USD/VND và giá vàng miếng trong nước, kèm dự báo thống kê có backtest và khoảng tin cậy đo từ sai số thực tế.",
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#faf8f4" },
    { media: "(prefers-color-scheme: dark)", color: "#0b0b0d" },
  ],
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="vi" className={sans.variable} suppressHydrationWarning>
      <head>
        {/* Runs before paint so a pinned theme never flashes the OS one first. */}
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />
      </head>
      <body className="page-glow flex min-h-dvh flex-col antialiased">
        <SiteHeader />
        <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 sm:py-10">{children}</main>
        <SiteFooter />
      </body>
    </html>
  );
}
