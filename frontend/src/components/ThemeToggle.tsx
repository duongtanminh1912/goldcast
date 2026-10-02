"use client";

import { useEffect, useState, type ReactNode } from "react";

type Theme = "light" | "dark" | "system";

const STORAGE_KEY = "goldcast-theme";

/** Inlined in <head>; must stay dependency-free and tolerate blocked storage. */
export const THEME_INIT_SCRIPT = `(function(){try{var t=localStorage.getItem("${STORAGE_KEY}");if(t==="light"||t==="dark"){document.documentElement.dataset.theme=t}}catch(e){}})();`;

function apply(theme: Theme) {
  const root = document.documentElement;
  if (theme === "system") {
    delete root.dataset.theme;
  } else {
    root.dataset.theme = theme;
  }
  try {
    if (theme === "system") {
      localStorage.removeItem(STORAGE_KEY);
    } else {
      localStorage.setItem(STORAGE_KEY, theme);
    }
  } catch {
    // Private windows can refuse storage; the choice then lasts only for this page.
  }
}

const OPTIONS: { value: Theme; label: string; icon: ReactNode }[] = [
  {
    value: "light",
    label: "Giao diện sáng",
    icon: (
      <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" className="h-3.5 w-3.5">
        <circle cx="10" cy="10" r="3.5" />
        <path
          strokeLinecap="round"
          d="M10 1.75v1.5M10 16.75v1.5M18.25 10h-1.5M3.25 10h-1.5M15.83 4.17l-1.06 1.06M5.23 14.77l-1.06 1.06M15.83 15.83l-1.06-1.06M5.23 5.23L4.17 4.17"
        />
      </svg>
    ),
  },
  {
    value: "system",
    label: "Theo hệ thống",
    icon: (
      <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" className="h-3.5 w-3.5">
        <rect x="2.5" y="3.5" width="15" height="10" rx="1.5" />
        <path strokeLinecap="round" d="M7 17h6M10 13.5V17" />
      </svg>
    ),
  },
  {
    value: "dark",
    label: "Giao diện tối",
    icon: (
      <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" className="h-3.5 w-3.5">
        <path
          strokeLinejoin="round"
          d="M16.5 12.2A6.75 6.75 0 0 1 7.8 3.5a6.75 6.75 0 1 0 8.7 8.7Z"
        />
      </svg>
    ),
  },
];

export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>("system");

  useEffect(() => {
    const pinned = document.documentElement.dataset.theme;
    setTheme(pinned === "light" || pinned === "dark" ? pinned : "system");
  }, []);

  return (
    <div role="radiogroup" aria-label="Chế độ màu" className="segmented">
      {OPTIONS.map((option) => (
        <button
          key={option.value}
          type="button"
          role="radio"
          aria-checked={theme === option.value}
          aria-label={option.label}
          title={option.label}
          onClick={() => {
            setTheme(option.value);
            apply(option.value);
          }}
          className={`segment px-1.5 ${theme === option.value ? "segment-active" : ""}`}
        >
          {option.icon}
        </button>
      ))}
    </div>
  );
}
