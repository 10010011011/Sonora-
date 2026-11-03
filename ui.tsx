import type { ReactNode, CSSProperties } from "react";

const P: Record<string, string> = {
  play: "M7 5.5v13a1 1 0 0 0 1.5.86l10.5-6.5a1 1 0 0 0 0-1.72L8.5 4.64A1 1 0 0 0 7 5.5z",
  pause: "M7 5h3.5v14H7zM13.5 5H17v14h-3.5z",
  next: "M6 6l8.5 6L6 18zM16 6h2.2v12H16z",
  prev: "M18 6l-8.5 6L18 18zM5.8 6H8v12H5.8z",
  shuffle: "M16 4l4 4-4 4M4 8h4.5c2 0 3 1 4 2.5l1 1.5c1 1.5 2 2.5 4 2.5H20M16 12l4 4-4 4M4 16h4.5c1.3 0 2.2-.4 2.9-1.1M14.3 9.1c.7-.7 1.6-1.1 2.9-1.1H20",
  repeat: "M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3",
  folder: "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
  "folder-open": "M6 14l1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2",
  music: "M19 6l-5.2-3.9a.5.5 0 0 0-.8.4V18M13 18a4 4 0 1 1-8 0 4 4 0 0 1 8 0z",
  "list-music": "M16 5H3M11 12H3M11 19H3M21 16V5M21 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z",
  "audio-lines": "M2 10v3M6 6v11M10 3v18M14 8v7M18 5v13M22 10v3",
  mic: "M11 7.6l-6 8.2a1 1 0 0 0 .1 1.3l.8.8a1 1 0 0 0 1.3.1L15.1 12M16.5 21.2c-1-.7-2.1-1.2-3.5-1.2-2.1 0-3.9 2.4-6 2-2.1-.4-2.8-3.4-1.5-4.5M21 7a5 5 0 1 1-10 0 5 5 0 0 1 10 0z",
  home: "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z",
  lyrics: "M4 6h16M4 10h10M4 14h16M4 18h8",
  sliders: "M10 5H3M12 19H3M14 3v4M16 17v4M21 12h-9M21 19h-5M21 5h-7M8 10v4M8 12H3",
  wave: "M2 12h2.5a2 2 0 0 0 1.9-1.5l2.3-8.3.5 0 3.5 19.6.5 0 2.3-8.3A2 2 0 0 1 17.5 12H22",
  gear: "M14 17H5M19 7h-9M20 17a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM10 7a3 3 0 1 1-6 0 3 3 0 0 1 6 0z",
  plus: "M12 5v14M5 12h14",
  minus: "M5 12h14",
  x: "M6 6l12 12M18 6L6 18",
  check: "M20 6L9 17l-5-5",
  timer: "M10 2h4M12 14l3-3M20 14a8 8 0 1 1-16 0 8 8 0 0 1 16 0z",
  clock: "M12 6v6h4M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z",
  loop: "M4 12a8 8 0 0 1 14-5.3M20 12a8 8 0 0 1-14 5.3M18 3v4h-4M6 21v-4h4",
  up: "M6 15l6-6 6 6",
  down: "M6 9l6 6 6-6",
  right: "M9 18l6-6-6-6",
  file: "M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9zM14 3v6h6",
  headphones: "M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3",
  bolt: "M13 2L4 14h7l-1 8 9-12h-7z",
  sparkles: "M11 2.8a1 1 0 0 1 2 0l1 5.6a2 2 0 0 0 1.6 1.6l5.6 1a1 1 0 0 1 0 2l-5.6 1a2 2 0 0 0-1.6 1.6l-1 5.6a1 1 0 0 1-2 0l-1-5.6a2 2 0 0 0-1.6-1.6l-5.6-1a1 1 0 0 1 0-2l5.6-1a2 2 0 0 0 1.6-1.6zM20 2v4M22 4h-4",
  download: "M12 15V3M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5",
  upload: "M12 3v12M17 8l-5-5-5 5M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
  search: "M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0zM21 21l-4.3-4.3",
  tap: "M9 11V5a2 2 0 1 1 4 0v6M13 10a2 2 0 1 1 4 0v2M17 11a2 2 0 1 1 4 0v4a7 7 0 0 1-7 7h-1a7 7 0 0 1-6-3.4L4 15.5a1.8 1.8 0 0 1 3-2L9 16",
  widget: "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z",
  heart: "M2 9.5a5.5 5.5 0 0 1 9.6-3.7.6.6 0 0 0 .8 0A5.5 5.5 0 0 1 22 9.5c0 2.3-1.5 4-3 5.5l-5.5 5.3a2 2 0 0 1-3 0L5 15c-1.5-1.5-3-3.2-3-5.5z",
  more: "M12 12h.01M19 12h.01M5 12h.01",
  volume: "M11 4.7a.7.7 0 0 0-1.2-.5L6.4 7.6A1.4 1.4 0 0 1 5.4 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.4a1.4 1.4 0 0 1 1 .4l3.4 3.4a.7.7 0 0 0 1.2-.5zM16 9a5 5 0 0 1 0 6M19.4 18.4a9 9 0 0 0 0-12.7",
  "volume-x": "M11 4.7a.7.7 0 0 0-1.2-.5L6.4 7.6A1.4 1.4 0 0 1 5.4 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.4a1.4 1.4 0 0 1 1 .4l3.4 3.4a.7.7 0 0 0 1.2-.5zM16.5 14.5l5-5M16.5 9.5l5 5",
  package: "M16.5 9.4l-9-5.19M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16zM3.27 6.96L12 12.01l8.73-5.05M12 22.08V12",
};

export function Icon({
  n,
  s = 20,
  className = "",
  fill = false,
  strokeWidth = 1.9,
}: {
  n: keyof typeof P | string;
  s?: number;
  className?: string;
  fill?: boolean;
  strokeWidth?: number;
}) {
  const isFill = fill || n === "play" || n === "pause" || n === "next" || n === "prev";
  return (
    <svg
      width={s}
      height={s}
      viewBox="0 0 24 24"
      className={className}
      fill={isFill ? "currentColor" : "none"}
      stroke={isFill && n !== "heart" ? "none" : "currentColor"}
      strokeWidth={n === "more" ? 3 : strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={P[n] || P.music} />
    </svg>
  );
}

export function Card({
  title,
  children,
  right,
  className = "",
}: {
  title?: ReactNode;
  children: ReactNode;
  right?: ReactNode;
  className?: string;
}) {
  return (
    <section className={`rounded-lg border border-line bg-surface p-5 ${className}`}>
      {(title || right) && (
        <div className="mb-3 flex items-center justify-between gap-2">
          <h3 className="text-[11px] font-extrabold uppercase tracking-wider text-muted">{title}</h3>
          {right}
        </div>
      )}
      {children}
    </section>
  );
}

export function Slider({
  label,
  value,
  min,
  max,
  step = 0.5,
  onChange,
  fmt,
  className = "",
  vertical = false,
}: {
  label?: ReactNode;
  value: number;
  min: number;
  max: number;
  step?: number;
  onChange: (v: number) => void;
  fmt?: (v: number) => string;
  className?: string;
  vertical?: boolean;
}) {
  const p = ((value - min) / (max - min)) * 100;
  const input = (
    <input
      type="range"
      aria-label={typeof label === "string" ? label : "Slider"}
      min={min}
      max={max}
      step={step}
      value={value}
      onChange={(e) => onChange(parseFloat(e.target.value))}
      onDoubleClick={() => onChange(min < 0 && max > 0 ? 0 : min === 0 && max === 2 ? 1 : value)}
      className={vertical ? "vslider" : "w-full"}
      style={{ "--fill": `${p}%`, "--p": `${p}%` } as CSSProperties}
    />
  );
  if (vertical) return input;
  return (
    <div className={`slider-row ${className}`}>
      {label != null && (
        <div className="slider-label">
          <span>{label}</span>
          <strong className="tabular-nums">{fmt ? fmt(value) : `${value > 0 ? "+" : ""}${value} dB`}</strong>
        </div>
      )}
      {input}
    </div>
  );
}

export function Toggle({
  on,
  onChange,
  label,
  hint,
}: {
  on: boolean;
  onChange: (v: boolean) => void;
  label: ReactNode;
  hint?: ReactNode;
}) {
  return (
    <div className="sound-setting">
      <div>
        <b>{label}</b>
        {hint && <p>{hint}</p>}
      </div>
      <button
        type="button"
        className={`switch ${on ? "on" : ""}`}
        onClick={() => onChange(!on)}
        aria-label={typeof label === "string" ? label : "Toggle setting"}
        aria-pressed={on}
      >
        <span />
      </button>
    </div>
  );
}

export function Chip({
  active,
  onClick,
  children,
  className = "",
  title,
}: {
  active?: boolean;
  onClick?: () => void;
  children: ReactNode;
  className?: string;
  title?: string;
}) {
  return (
    <button
      type="button"
      title={title}
      onClick={onClick}
      className={`rounded-md border px-3 py-1.5 text-[11px] font-bold transition active:scale-95 whitespace-nowrap shrink-0 ${
        active
          ? "border-accent bg-accent text-onaccent"
          : "border-line bg-surface2 text-muted hover:border-accent hover:text-fg"
      } ${className}`}
    >
      {children}
    </button>
  );
}

export function Btn({
  onClick,
  children,
  primary,
  className = "",
  title,
  disabled,
}: {
  onClick?: () => void;
  children: ReactNode;
  primary?: boolean;
  className?: string;
  title?: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      title={title}
      disabled={disabled}
      onClick={onClick}
      className={`${primary ? "accent-button" : "outline-button"} disabled:opacity-40 ${className}`}
    >
      {children}
    </button>
  );
}

export function Cover({
  src,
  size = 44,
  className = "",
  alt = "",
}: {
  src?: string;
  size?: number;
  className?: string;
  alt?: string;
}) {
  return (
    <span
      className={`queue-cover ${className}`}
      style={{ width: size, height: size }}
    >
      {src ? (
        <img src={src} alt={alt} referrerPolicy="no-referrer" draggable={false} />
      ) : (
        <Icon n="music" s={Math.max(16, Math.round(size * 0.42))} />
      )}
    </span>
  );
}
