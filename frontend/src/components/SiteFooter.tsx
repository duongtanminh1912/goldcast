import Link from "next/link";
import { Logo } from "./Logo";

export function SiteFooter() {
  return (
    <footer className="mt-8 border-t border-border bg-surface-sunken/60">
      <div className="mx-auto grid w-full max-w-6xl gap-8 px-4 py-10 sm:grid-cols-[1.4fr_1fr_1fr]">
        <div>
          <div className="flex items-center gap-2">
            <Logo className="h-6 w-6" />
            <span className="font-bold tracking-tight">goldcast</span>
          </div>
          <p className="mt-3 max-w-sm text-xs leading-relaxed text-ink-subtle">
            goldcast tổng hợp dữ liệu từ nguồn công khai và chạy các mô hình thống kê trên
            chính dữ liệu đó. Mọi con số ở đây chỉ mang tính tham khảo, không phải khuyến
            nghị mua bán, và không thay thế cho giá niêm yết chính thức tại quầy.
          </p>
        </div>

        <FooterColumn
          title="Thị trường"
          links={[
            { href: "/instruments/XAUUSD", label: "Vàng thế giới" },
            { href: "/instruments/SJC_HCM", label: "Vàng miếng SJC" },
            { href: "/instruments/SJC_RING", label: "Vàng nhẫn SJC" },
            { href: "/instruments/USDVND", label: "Tỷ giá USD/VND" },
          ]}
        />
        <FooterColumn
          title="Dự báo"
          links={[
            { href: "/forecast/XAUUSD", label: "Dự báo vàng thế giới" },
            { href: "/forecast/SJC_HCM", label: "Dự báo vàng SJC" },
            { href: "/methodology", label: "Phương pháp luận" },
          ]}
        />
      </div>
    </footer>
  );
}

function FooterColumn({
  title,
  links,
}: {
  title: string;
  links: { href: string; label: string }[];
}) {
  return (
    <div>
      <p className="label">{title}</p>
      <ul className="mt-3 space-y-2 text-sm">
        {links.map((link) => (
          <li key={link.href}>
            <Link href={link.href} className="text-ink-muted transition-colors hover:text-gold-deep">
              {link.label}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
