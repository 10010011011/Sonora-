import { useMemo, useRef, useState, type CSSProperties } from "react";
import { useStore, player, library, clock, settings, fmt, toggleLikeTrack, toast } from "./store";
import { engine, waveformSignatureForTrack } from "./engine";
import { setShuffle } from "./library";
import { openQueue } from "./sheets";
import { userdata } from "./userdata";
import { Icon, Cover } from "./ui";

export function useCurrent() {
  const id = useStore(player, (s) => s.trackId);
  const tracks = useStore(library, (s) => s.tracks);
  const qpos = useStore(library, (s) => s.qpos);
  const queue = useStore(library, (s) => s.queue);
  return useMemo(() => {
    if (id) {
      const hit = tracks.find((t) => t.id === id);
      if (hit) return hit;
    }
    return tracks[queue[qpos]] ?? tracks[0];
  }, [id, tracks, qpos, queue]);
}

export function SeekBar({ compact = false }: { compact?: boolean }) {
  const track = useCurrent();
  const t = useStore(clock, (s) => s.t);
  const dur = useStore(player, (s) => s.duration);
  const a = useStore(player, (s) => s.abA);
  const b = useStore(player, (s) => s.abB);
  const waveformOn = useStore(settings, (s) => s.waveformSeekbar);
  const showRem = useStore(settings, (s) => s.showRemainingTime);
  const bookmarks = useStore(userdata, (u) => u.bookmarks);
  const [drag, setDrag] = useState<number | null>(null);
  const ref = useRef<HTMLDivElement>(null);

  const ratio = (e: React.PointerEvent) => {
    const r = ref.current!.getBoundingClientRect();
    return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
  };

  const cur = drag != null ? drag * dur : t;
  const p = dur ? Math.min(100, (cur / dur) * 100) : 0;
  const bars = useMemo(() => waveformSignatureForTrack(track, 72), [track?.id, track?.duration]);
  const trackBookmarks = useMemo(
    () => (track ? bookmarks.filter((bm) => bm.trackId === track.id) : []),
    [bookmarks, track?.id]
  );

  return (
    <div className={compact ? "" : "select-none"}>
      <div
        ref={ref}
        className={`group relative cursor-pointer touch-none ${compact ? "h-1" : waveformOn ? "h-9" : "h-5"} flex items-center`}
        onPointerDown={(e) => {
          (e.target as HTMLElement).setPointerCapture(e.pointerId);
          setDrag(ratio(e));
        }}
        onPointerMove={(e) => drag != null && setDrag(ratio(e))}
        onPointerUp={(e) => {
          if (drag != null) engine.seek(ratio(e) * dur);
          setDrag(null);
        }}
      >
        {!compact && waveformOn ? (
          <div className="relative flex h-8 w-full items-center justify-between gap-[2px] overflow-hidden rounded-md px-0.5">
            {a != null && dur > 0 && (
              <div
                className="pointer-events-none absolute inset-y-0 rounded bg-accent2/25"
                style={{ left: `${(a / dur) * 100}%`, width: `${Math.max(1, (((b ?? cur) - a) / dur) * 100)}%` }}
              />
            )}
            {bars.map((v, i) => {
              const barPct = ((i + 0.5) / bars.length) * 100;
              const active = barPct <= p;
              return (
                <span
                  key={i}
                  className="flex-1 rounded-full transition-colors duration-75"
                  style={{
                    height: `${Math.round(v * 100)}%`,
                    backgroundColor: active ? "var(--accent)" : "var(--border)",
                    opacity: active ? 0.95 : 0.65,
                  }}
                />
              );
            })}
            {trackBookmarks.map((bm) =>
              dur > 0 ? (
                <span
                  key={bm.id}
                  title={bm.label || fmt(bm.t)}
                  className="pointer-events-none absolute bottom-0 h-1.5 w-1.5 -translate-x-1/2 rounded-full bg-accent2"
                  style={{ left: `${Math.min(100, (bm.t / dur) * 100)}%` }}
                />
              ) : null
            )}
          </div>
        ) : (
          <div className={`relative w-full overflow-hidden rounded-full bg-line ${compact ? "h-1" : "h-1.5"}`}>
            {a != null && dur > 0 && (
              <div
                className="absolute inset-y-0 bg-accent2/45"
                style={{ left: `${(a / dur) * 100}%`, width: `${Math.max(1, (((b ?? cur) - a) / dur) * 100)}%` }}
              />
            )}
            <div className="absolute inset-y-0 left-0 bg-accent" style={{ width: `${p}%` }} />
          </div>
        )}
        {!compact && !waveformOn && (
          <div
            className="absolute h-3 w-3 -translate-x-1/2 rounded-full bg-accent shadow ring-2 ring-bg transition-transform group-hover:scale-110"
            style={{ left: `${p}%` }}
          />
        )}
      </div>
      {!compact && (
        <div className="mt-1 flex justify-between font-mono text-[10px] tabular-nums text-muted">
          <span>{fmt(cur)}</span>
          <button
            type="button"
            className="bg-transparent p-0 font-mono text-[10px] tabular-nums text-muted hover:text-fg"
            title="Click to toggle remaining / total time"
            onClick={() => settings.set({ showRemainingTime: !showRem })}
          >
            {showRem ? fmt(-Math.max(0, dur - cur)) : fmt(dur)}
          </button>
        </div>
      )}
    </div>
  );
}

export function Transport({ size = 1 }: { size?: number }) {
  const playing = useStore(player, (s) => s.playing);
  const repeatRem = useStore(player, (s) => s.repeatRemaining);
  const shuffle = useStore(settings, (s) => s.shuffle);
  const repeat = useStore(settings, (s) => s.repeat);
  const repeatTarget = useStore(settings, (s) => s.repeatCountTarget);
  const cycleRepeat = () => {
    const r = repeat === "off" ? "all" : repeat === "all" ? "one" : "off";
    settings.set({ repeat: r });
    engine.setLoopFlag();
  };
  return (
    <div className="transport">
      <button
        type="button"
        className={shuffle ? "control-active" : ""}
        title="Shuffle"
        aria-label="Shuffle"
        onClick={() => setShuffle(!shuffle)}
      >
        <Icon n="shuffle" s={18 * size} />
      </button>
      <button type="button" title="Previous" aria-label="Previous" onClick={() => engine.prev()}>
        <Icon n="prev" s={21 * size} fill />
      </button>
      <button
        type="button"
        className="transport-play"
        title={playing ? "Pause" : "Play"}
        aria-label={playing ? "Pause" : "Play"}
        onClick={() => engine.toggle()}
      >
        <Icon n={playing ? "pause" : "play"} s={19 * size} fill />
      </button>
      <button type="button" title="Next" aria-label="Next" onClick={() => engine.next()}>
        <Icon n="next" s={21 * size} fill />
      </button>
      <button
        type="button"
        className={`relative ${repeat !== "off" || repeatTarget > 1 ? "control-active" : ""}`}
        title={`Repeat: ${repeat}${repeatTarget > 1 ? ` (${repeatRem + 1}×)` : ""}`}
        aria-label={`Repeat: ${repeat}`}
        onClick={cycleRepeat}
      >
        <Icon n="repeat" s={18 * size} />
        {(repeat === "one" || repeatTarget > 1) && (
          <span className="-top-1 -right-1.5 absolute font-mono text-[8px] font-black">
            {repeat === "one" ? "1" : `${repeatRem + 1}×`}
          </span>
        )}
      </button>
    </div>
  );
}

export function PlayerBar({
  onOpenSettings,
  onNavigate,
}: {
  onOpenSettings: () => void;
  onNavigate: (v: "room" | "library" | "lyrics" | "sound" | "player") => void;
}) {
  const track = useCurrent();
  const position = useStore(clock, (s) => s.t);
  const duration = useStore(player, (s) => s.duration) || track?.duration || 20;
  const loopA = useStore(player, (s) => s.abA);
  const loopB = useStore(player, (s) => s.abB);
  const sleepUntil = useStore(player, (s) => s.sleepAt);
  const sleepEot = useStore(player, (s) => s.sleepEndOfTrack);
  const sleepTracks = useStore(player, (s) => s.sleepTracksRemaining);
  const volume = useStore(settings, (s) => s.volume);
  const muted = useStore(settings, (s) => s.muted);
  const likedIds = useStore(settings, (s) => s.likedIds);
  const simple = useStore(settings, (s) => s.uiMode === "simple");
  const showRem = useStore(settings, (s) => s.showRemainingTime);
  const playing = useStore(player, (s) => s.playing);

  const isLiked = track ? likedIds.includes(track.id) : false;
  const volPct = Math.round(volume * 100);
  const progressPct = duration ? Math.min(100, (position / duration) * 100) : 0;

  if (!track) return null;

  return (
    <footer className={"player-bar" + (playing ? " is-playing" : "")}>
      <div className="player-song">
        <button
          type="button"
          className="flex min-w-0 flex-1 items-center gap-3 text-left"
          onClick={() => onNavigate("player")}
          title="Open now playing"
        >
          <Cover src={track?.cover} size={49} className="player-cover" alt={track?.title || "Cover"} />
          <div className="player-song-text">
            <b>{track?.title || "No track selected"}</b>
            <small>{track?.artist || "Add music to get started"}</small>
          </div>
        </button>
        {track && (
          <button
            type="button"
            className={`player-heart ${isLiked ? "liked" : ""}`}
            onClick={() => toggleLikeTrack(track.id)}
            aria-label="Like current song"
            title={isLiked ? "Remove from Liked songs" : "Save to Liked songs"}
          >
            <Icon n="heart" s={18} fill={isLiked} />
          </button>
        )}
      </div>

      <div className="player-center">
        <Transport />
        <div className="progress-row">
          <span className="tabular-nums">{fmt(position)}</span>
          <input
            type="range"
            min={0}
            max={duration || 1}
            step={0.05}
            value={Math.min(position, duration || 1)}
            onChange={(e) => engine.seek(Number(e.target.value))}
            aria-label="Track progress"
            style={{ "--fill": `${progressPct}%` } as CSSProperties}
          />
          <span
            className="cursor-pointer tabular-nums hover:text-fg"
            title="Click to toggle remaining / total time"
            onClick={() => settings.set({ showRemainingTime: !showRem })}
          >
            {showRem ? fmt(-Math.max(0, duration - position)) : fmt(duration)}
          </span>
        </div>
      </div>

      <div className="player-tools">
        <button
          type="button"
          className={loopA !== null ? "control-active" : ""}
          title={loopA === null ? "Set A point" : loopB === null ? "Set B point" : "Clear A-B loop"}
          aria-label="A-B Loop"
          onClick={() => {
            if (loopA === null) {
              player.set({ abA: position });
              toast(`Loop A set at ${fmt(position)}`);
            } else if (loopB === null) {
              if (position <= loopA) toast("Set B after point A");
              else {
                player.set({ abB: position });
                toast("A-B loop is active");
              }
            } else {
              player.set({ abA: null, abB: null });
              toast("A-B loop cleared");
            }
          }}
        >
          <span className="ab-label">{loopA === null ? "A-B" : loopB === null ? "A→?" : "A•B"}</span>
        </button>
        <button
          type="button"
          className={sleepUntil || sleepEot || sleepTracks > 0 ? "control-active" : ""}
          title="Sleep timer & Preferences"
          aria-label="Sleep timer"
          onClick={onOpenSettings}
        >
          <Icon n="timer" s={18} />
        </button>
        <button
          type="button"
          title={muted ? "Unmute" : "Mute"}
          aria-label={muted ? "Unmute" : "Mute"}
          onClick={() => settings.set({ muted: !muted })}
        >
          <Icon n={muted ? "volume-x" : "volume"} s={19} />
        </button>
        <input
          className="volume-slider"
          type="range"
          min={0}
          max={100}
          value={muted ? 0 : volPct}
          onChange={(e) => settings.set({ volume: Number(e.target.value) / 100, muted: false })}
          aria-label="Volume"
          style={{ "--fill": `${muted ? 0 : volPct}%` } as CSSProperties}
        />
        <button type="button" title="Queue" aria-label="Open queue" onClick={openQueue}>
          <Icon n="list-music" s={19} />
        </button>
        {!simple && (
          <button type="button" title="Sound lab" aria-label="Open Sound lab" onClick={() => onNavigate("sound")}>
            <Icon n="sliders" s={19} />
          </button>
        )}
      </div>
    </footer>
  );
}
