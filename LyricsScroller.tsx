import { useEffect, useRef, type ChangeEvent } from "react";
import { clock, lyricsStore, player, useStore, toast, type LyricLine } from "./store";
import { loadLyricsFor, saveLyrics } from "./library";
import { activeIndex, activeWordIndex, estimateWordTimings, parseLyrics } from "./lyrics";
import { engine } from "./engine";
import { useCurrent } from "./Player";
import { Icon } from "./ui";

export function LyricText({
  line,
  time,
  offset = 0,
  active = false,
}: {
  line: LyricLine;
  time: number;
  offset?: number;
  active?: boolean;
}) {
  if (!line.words?.length) return <span className="lyric-text">{line.text || "♪"}</span>;
  const activeWord = active ? activeWordIndex(line, time, offset) : -1;

  return (
    <span className="lyric-words">
      {line.words.map((word, index) => (
        <span
          key={index}
          className={
            "lyric-word"
            + (active && index === activeWord ? " is-current" : "")
            + (active && activeWord >= 0 && index < activeWord ? " is-spoken" : "")
          }
        >
          {word.text}
        </span>
      ))}
    </span>
  );
}

/**
 * Shared lyric reader for the desktop side panel and the full-screen player.
 * Exact enhanced-LRC/WebVTT word times are used when available. Ordinary
 * line-timed LRC gets an explicitly approximate word-by-word reading effect.
 */
export function LyricsScroller({
  className = "lyrics-body",
  onOpenStudio,
}: {
  className?: string;
  onOpenStudio?: () => void;
}) {
  const track = useCurrent();
  const lyrics = useStore(lyricsStore, state => (track ? state.map[track.id] : undefined));
  const time = useStore(clock, state => state.t);
  const duration = useStore(player, state => state.duration);
  const active = activeIndex(lyrics, time);
  const containerRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const userScrolledAt = useRef(0);

  useEffect(() => {
    if (track) void loadLyricsFor(track);
  }, [
    track?.id,
    track?.sidecar?.name,
    track?.sidecar?.size,
    track?.sidecar?.lastModified,
    track?.embeddedLyrics,
  ]);

  const importLyricsFile = async (event: ChangeEvent<HTMLInputElement>) => {
    const input = event.currentTarget;
    const file = input.files?.[0];
    if (!file || !track) return;
    try {
      const parsed = parseLyrics(await file.text(), file.name);
      if (!parsed.lines.length) {
        toast("No readable lyrics found in that file");
        return;
      }
      saveLyrics(track.id, parsed);
      toast(`Imported ${parsed.lines.length} lyric lines`);
    } catch {
      toast("Could not read that lyrics file");
    } finally {
      input.value = "";
    }
  };

  useEffect(() => {
    const container = containerRef.current;
    if (!container || active < 0 || Date.now() - userScrolledAt.current < 3200) return;
    const line = container.querySelector<HTMLElement>('[data-line="' + active + '"]');
    if (!line) return;
    const lineTop = line.getBoundingClientRect().top - container.getBoundingClientRect().top + container.scrollTop;
    container.scrollTo({
      top: lineTop - container.clientHeight / 2 + line.clientHeight / 2,
      behavior: "smooth",
    });
  }, [active, track?.id, lyrics?.lines.length]);

  const lines = lyrics?.lines ?? [];
  const offset = lyrics?.offset ?? 0;
  const trackDuration = Math.max(duration || 0, track?.duration || 0);

  return (
    <div
      className={className}
      ref={containerRef}
      onWheel={() => { userScrolledAt.current = Date.now(); }}
      onTouchMove={() => { userScrolledAt.current = Date.now(); }}
    >
      <input
        ref={fileInputRef}
        type="file"
        accept=".lrc,.txt,.srt,.vtt,text/plain,text/vtt"
        hidden
        onChange={importLyricsFile}
      />
      {lines.length ? (
        lines.map((line, index) => {
          // Estimation is applied only to the active line. This keeps rerenders
          // light even for large lyric files while still giving standard LRC
          // the word-following effect users expect.
          const estimatedWords = index === active && !line.words?.length
            ? estimateWordTimings(lyrics!, index, trackDuration)
            : undefined;
          const displayLine = estimatedWords?.length ? { ...line, words: estimatedWords } : line;

          return (
            <button
              type="button"
              key={index}
              data-line={index}
              aria-current={active === index ? "true" : undefined}
              aria-label={line.text}
              className={
                "lyric-line"
                + (active === index ? " active" : "")
                + (index < active ? " past" : "")
              }
              onClick={() => {
                if (line.t == null) return;
                engine.seek(line.t + offset);
                if (!player.get().playing) void engine.play();
              }}
            >
              <span className="lyric-marker" />
              <LyricText line={displayLine} time={time} offset={offset} active={active === index} />
            </button>
          );
        })
      ) : (
        <div className="no-lyrics">
          <Icon n="mic" s={28} />
          <b>{track ? "No lyrics yet" : "Nothing playing"}</b>
          <p>{track ? "Import an LRC file or paste your lyrics in Lyrics studio." : "Choose a song to see its lyrics here."}</p>
          {track && (
            <div className="no-lyrics-actions">
              <button type="button" onClick={() => fileInputRef.current?.click()}>
                Import lyrics <Icon n="upload" s={15} />
              </button>
              {onOpenStudio && (
                <button type="button" onClick={onOpenStudio}>
                  Lyrics studio <Icon n="right" s={15} />
                </button>
              )}
            </div>
          )}
        </div>
      )}
      {lines.some((line) => line.t != null) && !lines.some((line) => line.words?.length) && (
        <div className="lyrics-sync-note">
          Word highlighting is estimated from line timestamps. Enhanced LRC provides precise word timing.
        </div>
      )}
    </div>
  );
}
