/** A stacked gold bar; the same drawing is used as the favicon in app/icon.svg. */
export function Logo({ className = "" }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true" className={className}>
      <defs>
        <linearGradient id="goldcast-logo-a" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#f6d365" />
          <stop offset="0.55" stopColor="#d4a017" />
          <stop offset="1" stopColor="#8a6208" />
        </linearGradient>
      </defs>
      <rect width="32" height="32" rx="9" fill="url(#goldcast-logo-a)" />
      <path d="M8 21.5 11 15h10l3 6.5Z" fill="#fff" fillOpacity="0.92" />
      <path d="M12 13.5 14 9h4l2 4.5Z" fill="#fff" fillOpacity="0.7" />
      <path d="M8 23.5h16" stroke="#fff" strokeOpacity="0.5" strokeWidth="1.2" strokeLinecap="round" />
    </svg>
  );
}
