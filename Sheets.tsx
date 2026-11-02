import { useEffect, useRef, useState, type ReactNode } from "react";
import { useStore, library, player, settings, toast, toggleLikeTrack, fmt } from "./store";
import { engine } from "./engine";
import { useBackHandler } from "./useBack";
import { useFocusTrap } from "./focusTrap";
import {
  sheets,
  closeSheets,
  openTagEditor,
  openPlaylistPicker,
  askConfirm,
  askText,
  closeDialog,
  dialog,
  type DialogState,
} from "./sheets";
import { addToQueue, clearUpNext, moveInQueue, playNext, removeFromQueue } from "./queue";
import { closeImportSheet, editTags, removeTrackFromLibrary, importErrorReport, importState, retryFailed } from "./library";
import { SonoraPlayer, isNative } from "./nativeLibrary";
import {
  addBookmark,
  addToPlaylist,
  createPlaylist,
  removeBookmark,
  movePlaylistTrack,
  removeFromPlaylist,
  renameBookmark,
  userdata,
} from "./userdata";
import { Cover, Icon } from "./ui";

export function Sheet({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useBackHandler(true, onClose);
  const panel = useRef<HTMLDivElement>(null);
  useFocusTrap(panel);
  return (
    <div className="sheet-backdrop" onClick={onClose} role="presentation">
      <div ref={panel} tabIndex={-1} className="sheet" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <div className="sheet-head">
          <h3>{title}</h3>
          <button type="button" onClick={onClose} aria-label="Close">
            <Icon n="x" s={20} />
          </button>
        </div>
        <div className="sheet-body">{children}</div>
      </div>
    </div>
  );
}

/** Rendered once in App. */
export function SheetsHost() {
  const s = useStore(sheets, (x) => x);
  const imp = useStore(importState, (x) => x);
  return (
    <>
      {s.menu && <TrackMenu idx={s.menu.idx} playlistId={s.menu.playlistId} />}
      {s.tags && <TagEditor id={s.tags} />}
      {s.picker && <PlaylistPicker ids={s.picker} />}
      {s.queue && <QueueSheet />}
      {s.bookmarks && <BookmarksSheet />}
      {imp.phase !== "idle" && <ImportSheet />}
      <DialogHost />
    </>
  );
}

/* ---------------------------------------------------------------- in-app dialogs (replace window.prompt / confirm) */

function DialogHost() {
  const d = useStore(dialog, (x) => x);
  return d.open ? <AskDialog key={d.id} d={d} /> : null;
}

function AskDialog({ d }: { d: DialogState }) {
  const [value, setValue] = useState(d.value ?? "");
  const panel = useRef<HTMLDivElement>(null);
  useBackHandler(true, closeDialog);
  useFocusTrap(panel);
  const ok = () => {
    closeDialog();
    d.onOk?.(value);
  };
  return (
    <div className="sheet-backdrop" onClick={closeDialog} role="presentation">
      <div ref={panel} tabIndex={-1} className="sheet" role="dialog" aria-modal="true" aria-label={d.title} onClick={(e) => e.stopPropagation()}>
        <div className="sheet-head">
          <h3>{d.title}</h3>
          <button type="button" onClick={closeDialog} aria-label="Close">
            <Icon n="x" s={20} />
          </button>
        </div>
        <div className="sheet-body">
          {d.message && <p className="sheet-note">{d.message}</p>}
          {d.value !== undefined && (
            <label className="field">
              <span>Name</span>
              <input autoFocus value={value} onChange={(e) => setValue(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") ok(); }} />
            </label>
          )}
          <button type="button" className="accent-button sheet-primary" onClick={ok}>
            {d.okLabel ?? "OK"}
          </button>
          <button type="button" className="outline-button sheet-primary" onClick={closeDialog}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}

/* ---------------------------------------------------------------- track menu */

function TrackMenu({ idx, playlistId }: { idx: number; playlistId?: string }) {
  const track = useStore(library, (l) => l.tracks[idx]);
  const likedIds = useStore(settings, (x) => x.likedIds);
  const curId = useStore(player, (p) => p.trackId);
  if (!track) return null;
  const playlist = playlistId ? userdata.get().playlists.find((p) => p.id === playlistId) : undefined;
  const pos = playlist ? playlist.trackIds.indexOf(track.id) : -1;
  const count = playlist ? playlist.trackIds.length : 0;
  const liked = likedIds.includes(track.id);
  const act = (fn: () => void) => () => {
    closeSheets();
    fn();
  };
  return (
    <Sheet title={track.title} onClose={closeSheets}>
      <div className="sheet-sub">{[track.artist, track.album].filter(Boolean).join(" · ")}</div>
      <button type="button" className="sheet-row" onClick={act(() => { playNext(idx, true); toast("Plays next"); })}>
        <Icon n="next" s={18} /> Play next
      </button>
      <button type="button" className="sheet-row" onClick={act(() => { addToQueue(idx); toast("Added to queue"); })}>
        <Icon n="list-music" s={18} /> Add to queue
      </button>
      <button type="button" className="sheet-row" onClick={() => openPlaylistPicker([track.id])}>
        <Icon n="plus" s={18} /> Add to playlist…
      </button>
      {playlistId && (
        <button type="button" className="sheet-row" onClick={act(() => removeFromPlaylist(playlistId, track.id))}>
          <Icon n="minus" s={18} /> Remove from this playlist
        </button>
      )}
      {playlistId && pos > 0 && (
        <button type="button" className="sheet-row" onClick={act(() => movePlaylistTrack(playlistId, pos, pos - 1))}>
          <Icon n="up" s={18} /> Move up in playlist
        </button>
      )}
      {playlistId && pos >= 0 && pos < count - 1 && (
        <button type="button" className="sheet-row" onClick={act(() => movePlaylistTrack(playlistId, pos, pos + 1))}>
          <Icon n="down" s={18} /> Move down in playlist
        </button>
      )}
      <button type="button" className="sheet-row" onClick={act(() => void toggleLikeTrack(track.id))}>
        <Icon n="heart" s={18} fill={liked} /> {liked ? "Remove from Liked songs" : "Add to Liked songs"}
      </button>
      {curId === track.id && (
        <button
          type="button"
          className="sheet-row"
          onClick={act(() => { addBookmark(track.id, engine.time); toast(`Bookmarked ${fmt(engine.time)}`); })}
        >
          <Icon n="clock" s={18} /> Bookmark this moment
        </button>
      )}
      <button type="button" className="sheet-row" onClick={() => openTagEditor(track.id)}>
        <Icon n="file" s={18} /> Edit title, artist, album…
      </button>
      {isNative() && curId !== track.id && (
        <button
          type="button"
          className="sheet-row"
          onClick={act(() =>
            askConfirm(
              "Remove from library",
              `“${track.title}” is removed from Sonora. Files in your own folders are not deleted.`,
              () => void removeTrackFromLibrary(track.id).then(() => toast("Removed from library")),
              "Remove",
            ),
          )}
        >
          <Icon n="minus" s={18} /> Remove from library
        </button>
      )}
    </Sheet>
  );
}

/* ---------------------------------------------------------------- tag editor */

function TagEditor({ id }: { id: string }) {
  const track = useStore(library, (l) => l.tracks.find((t) => t.id === id));
  const [title, setTitle] = useState(track?.title ?? "");
  const [artist, setArtist] = useState(track?.artist ?? "");
  const [album, setAlbum] = useState(track?.album ?? "");
  if (!track) return null;
  const save = async () => {
    if (!title.trim()) return toast("A title is required");
    await editTags(id, { title, artist, album });
    toast("Saved");
    closeSheets();
  };
  const field = (label: string, value: string, set: (v: string) => void) => (
    <label className="field">
      <span>{label}</span>
      <input value={value} onChange={(e) => set(e.target.value)} autoCapitalize="words" />
    </label>
  );
  return (
    <Sheet title="Edit details" onClose={closeSheets}>
      {field("Title", title, setTitle)}
      {field("Artist", artist, setArtist)}
      {field("Album", album, setAlbum)}
      <p className="sheet-note">
        Saved inside Sonora only. Your audio file is never changed, and the edit survives re-importing the file.
      </p>
      <button type="button" className="accent-button sheet-primary" onClick={save}>
        Save
      </button>
    </Sheet>
  );
}

/* ---------------------------------------------------------------- playlists */

function PlaylistPicker({ ids }: { ids: string[] }) {
  const playlists = useStore(userdata, (u) => u.playlists);
  const [name, setName] = useState("");
  const finish = (id: string) => {
    const n = addToPlaylist(id, ids);
    toast(n ? `Added ${n} track${n === 1 ? "" : "s"}` : "Already in that playlist");
    closeSheets();
  };
  return (
    <Sheet title="Add to playlist" onClose={closeSheets}>
      <div className="field-row">
        <input
          className="field-input"
          placeholder="New playlist name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          aria-label="New playlist name"
        />
        <button
          type="button"
          className="accent-button"
          disabled={!name.trim()}
          onClick={() => finish(createPlaylist(name, []))}
        >
          Create
        </button>
      </div>
      {playlists.length === 0 && <p className="sheet-note">No playlists yet. Name one above to start.</p>}
      {playlists.map((p) => (
        <button key={p.id} type="button" className="sheet-row" onClick={() => finish(p.id)}>
          <Icon n="list-music" s={18} /> {p.name} <small>{p.trackIds.length}</small>
        </button>
      ))}
    </Sheet>
  );
}

/* ---------------------------------------------------------------- queue */

const QROW = 56;

function QueueSheet() {
  const tracks = useStore(library, (l) => l.tracks);
  const queue = useStore(library, (l) => l.queue);
  const qpos = useStore(library, (l) => l.qpos);
  const shuffle = useStore(settings, (x) => x.shuffle);
  const [drag, setDrag] = useState<{ from: number; to: number } | null>(null);
  const [scroll, setScroll] = useState(0);
  const [qFilter, setQFilter] = useState("");
  const box = useRef<HTMLDivElement>(null);
  const lastY = useRef<number | null>(null);

  const jumpToCurrent = () => {
    const el = box.current;
    if (!el || qpos < 0) return;
    el.scrollTo({ top: Math.max(0, qpos * QROW - el.clientHeight * 0.25), behavior: "smooth" });
  };

  useEffect(() => {
    // Scroll current item into comfort zone when opening queue sheet
    const el = box.current;
    if (!el || qpos < 0) return;
    el.scrollTop = Math.max(0, qpos * QROW - 112);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const filteredPositions = qFilter.trim()
    ? queue
        .map((ti, pos) => ({ ti, pos, t: tracks[ti] }))
        .filter(({ t }) => t && `${t.title} ${t.artist} ${t.album}`.toLowerCase().includes(qFilter.trim().toLowerCase()))
    : null;

  const start = Math.max(0, Math.floor(scroll / QROW) - 6);
  const end = Math.min(queue.length, start + 30);

  const targetFor = (clientY: number) => {
    const el = box.current;
    if (!el) return 0;
    const y = clientY - el.getBoundingClientRect().top + el.scrollTop;
    return Math.max(0, Math.min(queue.length - 1, Math.floor(y / QROW)));
  };

  // Holding the finger near the top or bottom edge scrolls the queue while dragging.
  const dragging = drag !== null;
  useEffect(() => {
    if (!dragging) return;
    const timer = window.setInterval(() => {
      const el = box.current;
      const y = lastY.current;
      if (!el || y === null) return;
      const r = el.getBoundingClientRect();
      const before = el.scrollTop;
      if (y < r.top + 48) el.scrollTop -= 12;
      else if (y > r.bottom - 48) el.scrollTop += 12;
      if (el.scrollTop !== before) setDrag((d) => (d ? { from: d.from, to: targetFor(y) } : d));
    }, 60);
    return () => window.clearInterval(timer);
  }, [dragging]);

  return (
    <Sheet title={`Queue · ${queue.length}`} onClose={closeSheets}>
      <div className="queue-tools gap-2">
        <span>{shuffle ? "Shuffled" : "In order"}</span>
        <div className="ml-auto flex items-center gap-1.5">
          {qpos >= 0 && !filteredPositions && (
            <button type="button" className="outline-button h-8 px-2.5 text-xs" onClick={jumpToCurrent} title="Jump to playing song">
              Jump to playing
            </button>
          )}
          <button
            type="button"
            className="outline-button h-8 px-2.5 text-xs"
            onClick={() => {
              const name = `Queue · ${new Date().toLocaleDateString()}`;
              const ids = queue.map((i) => tracks[i]?.id).filter((id): id is string => Boolean(id));
              createPlaylist(name, ids);
              toast(`Saved "${name}"`);
            }}
            disabled={!queue.length}
          >
            Save as playlist
          </button>
          <button type="button" className="outline-button h-8 px-2.5 text-xs" onClick={clearUpNext} disabled={qpos >= queue.length - 1}>
            Clear up next
          </button>
        </div>
      </div>
      {queue.length > 5 && (
        <div className="mx-2 mb-2 flex h-9 items-center gap-2 rounded-md border border-line bg-surface2 px-3">
          <Icon n="search" s={15} className="text-muted" />
          <input
            value={qFilter}
            onChange={(e) => setQFilter(e.target.value)}
            placeholder="Search in queue..."
            aria-label="Search in queue"
            className="w-full bg-transparent text-xs text-fg outline-none"
          />
          {qFilter && (
            <button type="button" className="bg-transparent p-1 text-muted hover:text-fg" onClick={() => setQFilter("")}>
              <Icon n="x" s={13} />
            </button>
          )}
        </div>
      )}
      <div ref={box} className="queue-scroll" onScroll={(e) => setScroll(e.currentTarget.scrollTop)}>
        {filteredPositions ? (
          <div className="flex flex-col gap-1 py-1">
            {filteredPositions.map(({ ti, pos, t }) => {
              if (!t) return null;
              const current = pos === qpos;
              return (
                <div key={`${t.id}-${pos}`} className={`flex h-14 items-center gap-2 rounded-lg px-2 ${current ? "bg-accent/10" : ""}`}>
                  <button
                    type="button"
                    className="queue-item-main"
                    onClick={() => {
                      engine.playAt(pos);
                      setQFilter("");
                    }}
                  >
                    <Cover src={t.thumb ?? t.cover} size={36} alt={t.title} />
                    <span className="queue-name">
                      <b>{t.title}</b>
                      <small>{t.artist}</small>
                    </span>
                  </button>
                  {current ? (
                    <Icon n="audio-lines" s={16} className="text-accent" />
                  ) : (
                    <button type="button" className="row-heart" aria-label={`Remove ${t.title} from queue`} onClick={() => removeFromQueue(pos)}>
                      <Icon n="x" s={16} />
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        ) : (
          <div style={{ height: queue.length * QROW, position: "relative" }}>
            {queue.slice(start, end).map((ti, k) => {
              const pos = start + k;
              const t = tracks[ti];
              if (!t) return null;
              const current = pos === qpos;
              const target = drag && drag.to === pos && drag.from !== pos;
              return (
                <div
                  key={`${t.id}-${pos}`}
                  className={`queue-item ${current ? "current" : ""} ${target ? "drop-target" : ""} ${drag?.from === pos ? "dragging" : ""}`}
                  style={{ top: pos * QROW, height: QROW }}
                >
                  <button
                    type="button"
                    className="drag-handle"
                    aria-label={`Move ${t.title}`}
                    onPointerDown={(e) => {
                      e.currentTarget.setPointerCapture(e.pointerId);
                      lastY.current = e.clientY;
                      setDrag({ from: pos, to: pos });
                    }}
                    onPointerMove={(e) => {
                      lastY.current = e.clientY;
                      if (drag) setDrag({ from: drag.from, to: targetFor(e.clientY) });
                    }}
                    onPointerUp={() => {
                      if (drag) moveInQueue(drag.from, drag.to);
                      setDrag(null);
                    }}
                    onPointerCancel={() => setDrag(null)}
                  >
                    <Icon n="lyrics" s={18} />
                  </button>
                  <button type="button" className="queue-item-main" onClick={() => engine.playAt(pos)}>
                    <Cover src={t.thumb ?? t.cover} size={36} alt={t.title} />
                    <span className="queue-name">
                      <b>{t.title}</b>
                      <small>{t.artist}</small>
                    </span>
                  </button>
                  {current ? (
                    <Icon n="audio-lines" s={16} className="text-accent" />
                  ) : (
                    <button type="button" className="row-heart" aria-label={`Remove ${t.title} from queue`} onClick={() => removeFromQueue(pos)}>
                      <Icon n="x" s={16} />
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </Sheet>
  );
}

/* ---------------------------------------------------------------- bookmarks */

function BookmarksSheet() {
  const trackId = useStore(player, (p) => p.trackId);
  const all = useStore(userdata, (u) => u.bookmarks);
  const mine = all.filter((b) => b.trackId === trackId).sort((a, b) => a.t - b.t);
  return (
    <Sheet title="Bookmarks" onClose={closeSheets}>
      <button
        type="button"
        className="accent-button sheet-primary"
        disabled={!trackId}
        onClick={() => trackId && addBookmark(trackId, engine.time)}
      >
        <Icon n="plus" s={16} /> Bookmark {fmt(engine.time)}
      </button>
      {mine.length === 0 && <p className="sheet-note">No bookmarks on this track yet.</p>}
      {mine.map((b) => (
        <div key={b.id} className="sheet-row split">
          <button type="button" className="sheet-row-main" onClick={() => engine.seek(b.t)}>
            <Icon n="clock" s={18} /> <b className="tabular-nums">{fmt(b.t)}</b> {b.label && <span>{b.label}</span>}
          </button>
          <button
            type="button"
            className="row-heart"
            aria-label="Rename bookmark"
            onClick={() => askText("Rename bookmark", b.label ?? "", (v) => renameBookmark(b.id, v), "Save")}
          >
            <Icon n="file" s={16} />
          </button>
          <button type="button" className="row-heart" aria-label="Delete bookmark" onClick={() => removeBookmark(b.id)}>
            <Icon n="x" s={16} />
          </button>
        </div>
      ))}
    </Sheet>
  );
}

/* ---------------------------------------------------------------- import progress / errors */

function ImportSheet() {
  const st = useStore(importState, (x) => x);
  const running = st.phase === "running";
  const pct = st.total ? Math.round((st.done / st.total) * 100) : 0;
  const title =
    st.kind === "folder" ? "Adding folder" : st.kind === "rescan" ? "Scanning folders"
    : st.kind === "migration" ? "Moving your library" : st.kind === "retry" ? "Retrying" : "Importing";
  const retryable = st.failed.filter((f) => f.uri);

  const copyReport = async () => {
    try {
      await navigator.clipboard.writeText(importErrorReport(st.failed));
      toast("Error report copied");
    } catch {
      toast("Could not copy the report");
    }
  };

  return (
    <Sheet title={running ? title : "Done"} onClose={closeImportSheet}>
      {running ? (
        <>
          <div className="progress-track" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
            <div style={{ width: `${pct}%` }} />
          </div>
          <p className="sheet-note">
            {st.total ? `${Math.min(st.done + 1, st.total)} of ${st.total}` : "Working…"}
            {st.name ? ` · ${st.name}` : ""}
          </p>
          <p className="sheet-note">You can close this: it keeps going in the background.</p>
          {isNative() && st.kind !== "migration" && (
            <button type="button" className="outline-button sheet-primary" onClick={() => void SonoraPlayer.cancelImport()}>
              Cancel
            </button>
          )}
        </>
      ) : (
        <>
          <p className="sheet-note">{st.summary}</p>
          {st.failed.length > 0 && (
            <div className="failed-list">
              {st.failed.slice(0, 40).map((f, i) => (
                <div key={i} className="failed-row">
                  <b>{f.name}</b>
                  <small>{f.reason}</small>
                </div>
              ))}
              {st.failed.length > 40 && <small>…and {st.failed.length - 40} more (see the report)</small>}
            </div>
          )}
          <div className="sheet-actions">
            {retryable.length > 0 && (
              <button type="button" className="accent-button" onClick={() => retryFailed(st.failed)}>
                Retry {retryable.length}
              </button>
            )}
            {st.failed.length > 0 && (
              <button type="button" className="outline-button" onClick={copyReport}>
                Copy error report
              </button>
            )}
            <button type="button" className="outline-button" onClick={closeImportSheet}>
              Close
            </button>
          </div>
        </>
      )}
    </Sheet>
  );
}
