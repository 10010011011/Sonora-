import { useEffect, useRef, useState, useCallback } from "react";
import { useStore, clock, settings, toast, fmt, type Lyrics } from "./store";
import { saveLyrics } from "./library";
import { parseLyrics, toLRC, activeIndex, bakeOffset, shiftFrom, stamp, parseCueTime } from "./lyrics";
import { engine } from "./engine";
import { useCurrent } from "./Player";
import { useLyrics, Calibrate } from "./SharedAudioViews";
import { useBackHandler } from "./useBack";
import { Icon, Chip } from "./ui";

function downloadLrc(name: string, text: string) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([text], { type: "text/plain" }));
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

export default function LyricsPage() {
  const track = useCurrent();
  const lyr = useLyrics(track);
  const position = useStore(clock, (s) => s.t);
  const latency = useStore(settings, (s) => s.tapLatency);
  const speed = useStore(settings, (s) => s.speed);

  const [mode, setMode] = useState<"edit" | "tap" | "preview">("preview");
  const [draft, setDraft] = useState("");
  const [shiftAmt, setShiftAmt] = useState("1.7");
  const [fromCurrent, setFromCurrent] = useState(false);
  const [tapNext, setTapNext] = useState(0);
  const [calibOpen, setCalibOpen] = useState(false);
  useBackHandler(calibOpen, () => setCalibOpen(false));

  const fileInputRef = useRef<HTMLInputElement>(null);
  const workspaceScrollRef = useRef<HTMLDivElement>(null);

  const lines = lyr?.lines ?? [];
  const off = lyr?.offset ?? 0;

  useEffect(() => {
    if (lyr) {
      setDraft(toLRC(lyr));
      const firstUnstamped = lyr.lines.findIndex((x) => x.t == null);
      setTapNext(firstUnstamped >= 0 ? firstUnstamped : 0);
    } else {
      setDraft("");
      setTapNext(0);
    }
  }, [track?.id, lyr?.lines.length]); // eslint-disable-line

  const openEditor = () => {
    setDraft(lyr ? toLRC(lyr) : "");
    setMode("edit");
  };

  const applyDraft = () => {
    if (!track) return;
    const parsed = parseLyrics(draft, "edited");
    if (!parsed.lines.length) {
      toast("Paste some lyrics first");
      return;
    }
    saveLyrics(track.id, parsed);
    setTapNext(0);
    setMode(parsed.lines.some((l) => l.t === null) ? "tap" : "preview");
    toast(`${parsed.lines.length} lyric lines ready`);
  };

  const importFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file && track) {
      const text = await file.text();
      setDraft(text);
      const parsed = parseLyrics(text, file.name);
      saveLyrics(track.id, parsed);

      setTapNext(0);
      setMode(parsed.lines.some((l) => l.t === null) ? "tap" : "preview");
      toast(`${parsed.lines.length} lyric lines imported`);
    }
    e.target.value = "";
  };

  const stampNowTime = useCallback(() => {
    const rawTime = engine.el.src ? engine.time : position;
    return Math.round(Math.max(0, rawTime - (latency / 1000) * (speed || 1) - off) * 100) / 100;
  }, [latency, speed, off, position]);

  const stampLineAt = useCallback(
    (index: number) => {
      if (!track || !lyr || index < 0 || index >= lyr.lines.length) return;
      const stamped = stampNowTime();
      const updated: Lyrics = {
        ...lyr,
        lines: lyr.lines.map((line, j) => {
          if (j !== index) return line;
          const previous = line.t ?? line.words?.[0]?.t ?? null;
          const delta = previous == null ? 0 : stamped - previous;
          return {
            ...line,
            t: stamped,
            words: line.words?.map(word => ({ ...word, t: Math.max(0, word.t + delta) })),
          };
        }),
      };
      saveLyrics(track.id, updated);
      const nextIdx = Math.min(lyr.lines.length, index + 1);
      setTapNext(nextIdx);
      setTimeout(() => {
        workspaceScrollRef.current
          ?.querySelector<HTMLElement>(`[data-line="${nextIdx}"]`)
          ?.scrollIntoView({ behavior: "smooth", block: "center" });
      }, 60);
    },
    [track, lyr, stampNowTime]
  );

  const undoTap = useCallback(() => {
    if (!track || !lyr || tapNext <= 0) return;
    const prevIdx = tapNext - 1;
    saveLyrics(track.id, {
      ...lyr,
      lines: lyr.lines.map((line, j) => (j === prevIdx ? { ...line, t: null, words: undefined } : line)),
    });
    setTapNext(prevIdx);
  }, [track, lyr, tapNext]);

  useEffect(() => {
    if (mode !== "tap" || calibOpen) return;
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement).tagName;
      if (tag === "INPUT" || tag === "TEXTAREA") return;
      if (e.code === "Space" || e.code === "Enter") {
        e.preventDefault();
        stampLineAt(tapNext);
      } else if (e.code === "Backspace") {
        e.preventDefault();
        undoTap();
      }
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [mode, calibOpen, tapNext, stampLineAt, undoTap]);

  const nudgeOffset = (d: number) => {
    if (!track || !lyr) return;
    saveLyrics(track.id, { ...lyr, offset: Math.round((off + d) * 100) / 100 });
  };

  const shiftTimestamps = (sign: 1 | -1) => {
    if (!track || !lyr) return;
    const n = parseFloat(shiftAmt);
    if (!Number.isFinite(n)) return;
    const fromIdx = fromCurrent ? Math.max(0, activeIndex(lyr, position)) : 0;
    saveLyrics(track.id, shiftFrom(lyr, fromIdx, sign * n));
    toast(
      `Shifted ${fromCurrent ? `from line ${fromIdx + 1}` : "all timed lines"} ${sign > 0 ? "+" : "-"}${n}s`
    );
  };

  if (!track) {
    return (
      <div className="main-content inner-content">
        <p className="subtitle">Select or import a song to open Lyrics studio.</p>
      </div>
    );
  }

  return (
    <div className="main-content inner-content">
      <input
        ref={fileInputRef}
        type="file"
        accept=".lrc,.txt,.srt,.vtt"
        hidden
        onChange={importFile}
      />

      <div className="section-kicker">
        <span /> WORDS IN SYNC
      </div>
      <h1>
        Lyrics <em>studio.</em>
      </h1>
      <p className="subtitle">Bring your words to life, one line at a time.</p>

      <div className="studio-intro">
        <span className="studio-icon">
          <Icon n="mic" s={27} />
        </span>
        <div>
          <span>EDITING LYRICS FOR</span>
          <h2>{track.title}</h2>
          <p>
            {track.artist} {lyr?.source ? `· ${lyr.source}` : ""}
          </p>
        </div>
        <button type="button" className="outline-button" onClick={() => fileInputRef.current?.click()}>
          <Icon n="upload" s={17} /> Import lyrics
        </button>
      </div>

      <div className="studio-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={mode === "edit"}
          className={mode === "edit" ? "active" : ""}
          onClick={openEditor}
        >
          Text editor
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={mode === "tap"}
          className={mode === "tap" ? "active" : ""}
          onClick={() => setMode("tap")}
        >
          Tap to sync
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={mode === "preview"}
          className={mode === "preview" ? "active" : ""}
          onClick={() => setMode("preview")}
        >
          Preview
        </button>
      </div>

      {mode === "edit" ? (
        <div className="editor-area">
          <div className="editor-head">
            <span>PASTE LYRICS OR LRC TIMESTAMPS</span>
            <span>Supports .lrc, .srt, .vtt, .txt</span>
          </div>
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            spellCheck={false}
            placeholder={
              "Paste your lyrics here...\n\nOne line at a time. You can add timestamps later with Tap to sync.\n\nOr paste LRC like:\n[00:12.50] Your first line"
            }
          />
          <div className="editor-actions">
            <span>Text is applied when you click Use these lyrics.</span>
            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                className="outline-button"
                onClick={() =>
                  setDraft(
                    draft
                      .split("\n")
                      .map((l) => l.replace(/^(\s*\[\d+:\d+(?:[.:]\d+)?\])+/, ""))
                      .join("\n")
                  )
                }
              >
                Strip times
              </button>
              <button type="button" className="accent-button" onClick={applyDraft}>
                Use these lyrics <Icon n="right" s={17} />
              </button>
            </div>
          </div>
        </div>
      ) : (
        <div className="workspace">
          <div className="workspace-head">
            <div>
              <b>{mode === "tap" ? "Tap when each line begins" : "Fine-tune your timing"}</b>
              <span>
                {mode === "tap"
                  ? `Play your song and tap each line in rhythm (subtracts ${latency}ms reaction delay).`
                  : "Edit a timestamp or lyric directly. Changes save automatically."}
              </span>
            </div>
            <div className="flex items-center gap-2">
              {mode === "tap" && (
                <Chip onClick={() => setCalibOpen(true)} title="Calibrate tap reaction delay">
                  Calibrate ({latency}ms)
                </Chip>
              )}
              <span>{lines.length} LINES</span>
            </div>
          </div>

          <div className="workspace-list" ref={workspaceScrollRef}>
            {lines.length ? (
              lines.map((l, i) => (
                <div
                  className={`workspace-row ${mode === "tap" && i === tapNext ? "next-tap" : ""}`}
                  data-line={i}
                  key={i}
                >
                  {mode === "tap" ? (
                    <button
                      type="button"
                      className={`tap-check ${l.t !== null ? "stamped" : ""}`}
                      onClick={() => stampLineAt(i)}
                      aria-label={`Stamp line ${i + 1}`}
                    >
                      <Icon n="check" s={15} />
                    </button>
                  ) : (
                    <span className="workspace-index tabular-nums">{String(i + 1).padStart(2, "0")}</span>
                  )}

                  <input
                    className="workspace-text"
                    value={l.text}
                    aria-label={`Lyric line ${i + 1}`}
                    onChange={(e) =>
                      lyr &&
                      saveLyrics(track.id, {
                        ...lyr,
                        lines: lines.map((line, j) => (i === j ? { ...line, text: e.target.value, words: undefined } : line)),
                      })
                    }
                  />

                  <input
                    className="workspace-stamp tabular-nums"
                    key={`${i}-${l.t}`}
                    defaultValue={l.t === null ? "" : stamp(l.t)}
                    aria-label={`Time for line ${i + 1}`}
                    placeholder="00:00.00"
                    onBlur={(e) => {
                      if (!lyr) return;
                      const n = parseCueTime(e.target.value);
                      if (n !== null || !e.target.value.trim()) {
                        saveLyrics(track.id, {
                          ...lyr,
                          lines: lines.map((line, j) => {
                            if (j !== i) return line;
                            const previous = line.t ?? line.words?.[0]?.t ?? null;
                            const delta = n == null || previous == null ? 0 : n - previous;
                            return {
                              ...line,
                              t: n,
                              words: n == null
                                ? undefined
                                : line.words?.map(word => ({ ...word, t: Math.max(0, word.t + delta) })),
                            };
                          }),
                        });
                      } else {
                        e.target.value = l.t === null ? "" : stamp(l.t);
                      }
                    }}
                  />

                  {mode === "preview" && (
                    <button
                      type="button"
                      className="seek-line"
                      title="Jump to line"
                      aria-label={`Jump to line ${i + 1}`}
                      onClick={() => {
                        if (l.t !== null) engine.seek(l.t + off);
                      }}
                    >
                      <Icon n="play" s={14} fill />
                    </button>
                  )}
                </div>
              ))
            ) : (
              <div className="workspace-empty">
                No lyrics yet. Paste text in Text editor or import a lyrics file to begin.
              </div>
            )}
          </div>

          {mode === "tap" && lines.length > 0 && (
            <div className="flex items-center gap-2 border-t border-line px-5 py-3">
              <button
                type="button"
                className="outline-button"
                onClick={undoTap}
                disabled={tapNext <= 0}
                title="Undo previous stamp (Backspace)"
              >
                Undo
              </button>
              <button
                type="button"
                className="accent-button flex-1"
                disabled={tapNext >= lines.length}
                onPointerDown={(e) => {
                  e.preventDefault();
                  stampLineAt(tapNext);
                }}
              >
                {tapNext >= lines.length
                  ? "All lines synced"
                  : `TAP LINE ${tapNext + 1} (Space / Enter)`}
              </button>
            </div>
          )}

          <div className="workspace-bottom">
            <span>
              <Icon n="clock" s={15} /> Current position <b className="tabular-nums">{fmt(position)}</b>
            </span>
            {mode === "tap" && (
              <span className="tabular-nums">
                Next: {Math.min(tapNext + 1, lines.length)} / {lines.length}
              </span>
            )}
          </div>
        </div>
      )}

      <div className="timing-tools">
        <div>
          <b>Shift all timestamps</b>
          <span>Fix lyrics that are early or late in one move.</span>
        </div>
        <button type="button" aria-label="Shift earlier" onClick={() => shiftTimestamps(-1)}>
          <Icon n="minus" s={16} />
        </button>
        <input
          type="number"
          min={0}
          step={0.1}
          value={shiftAmt}
          onChange={(e) => setShiftAmt(e.target.value)}
          aria-label="Shift seconds"
        />
        <span>sec</span>
        <button type="button" aria-label="Shift later" onClick={() => shiftTimestamps(1)}>
          <Icon n="plus" s={16} />
        </button>
        <label className="ml-2 flex items-center gap-1.5 text-[11px] text-muted">
          <input
            type="checkbox"
            checked={fromCurrent}
            onChange={(e) => setFromCurrent(e.target.checked)}
            className="accent-[var(--accent)]"
          />
          From current line
        </label>
        <button
          type="button"
          className="export-button ml-auto"
          onClick={() =>
            downloadLrc(
              `${track.artist} - ${track.title}.lrc`,
              toLRC(lyr ?? parseLyrics(draft), { ti: track.title, ar: track.artist })
            )
          }
        >
          <Icon n="download" s={16} /> Export LRC
        </button>
      </div>

      {lyr && lines.some((l) => l.t != null) && (
        <div className="timing-tools mt-3">
          <div>
            <b>Live preview offset</b>
            <span>Nudge sync on the fly or bake permanently into timestamps.</span>
          </div>
          {[-0.5, -0.1].map((d) => (
            <Chip key={d} onClick={() => nudgeOffset(d)}>
              {d}s
            </Chip>
          ))}
          <span className="w-16 text-center font-mono text-xs font-bold text-accent tabular-nums">
            {off >= 0 ? "+" : ""}
            {off.toFixed(2)}s
          </span>
          {[0.1, 0.5].map((d) => (
            <Chip key={d} onClick={() => nudgeOffset(d)}>
              +{d}s
            </Chip>
          ))}
          {off !== 0 && (
            <>
              <Chip
                onClick={() => {
                  saveLyrics(track.id, bakeOffset(lyr));
                  toast("Offset baked into timestamps");
                }}
              >
                Bake
              </Chip>
              <Chip onClick={() => saveLyrics(track.id, { ...lyr, offset: 0 })}>Reset</Chip>
            </>
          )}
        </div>
      )}

      {calibOpen && <Calibrate onClose={() => setCalibOpen(false)} />}
    </div>
  );
}
