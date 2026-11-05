import { useEffect, useMemo, useRef, useState } from "react";
import { Capacitor } from "@capacitor/core";
import { useStore, library, player, settings, lyricsStore, fmt, toggleLikeTrack, toast, type SortKey, type Track, type LibraryFilter } from "./store";
import { IN_PLACE_FOLDERS, addAudioFiles, importFolderFromDevice, openFolderHandle, savedFolder } from "./library";
import { enqueueMany, playFromList, playIndices, playNext, playShuffled } from "./queue";
import { engine } from "./engine";
import { askConfirm, askText, openPlaylistPicker, openSongPicker, openTrackMenu } from "./sheets";
import { useBackHandler } from "./useBack";
import { extractArtists } from "./tags";
import {
  createPlaylist,
  deletePlaylist,
  importM3UPlaylist,
  movePlaylistTrack,
  playlistIndices,
  playlistToM3U,
  renamePlaylist,
  userdata,
  type Playlist,
} from "./userdata";
import { Icon, Cover } from "./ui";

const ROW = 66;
type Mode = "songs" | "albums" | "artists" | "folders" | "playlists";

interface Group {
  kind: "album" | "artist" | "folder" | "playlist";
  key: string;
  title: string;
  sub: string;
  cover?: string;
  indices: number[];
  newest: number;
  plays: number;
  playlistId?: string;
}

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: "base" });
const SORTS: { id: SortKey; label: string }[] = [
  { id: "title", label: "Title" },
  { id: "artist", label: "Artist" },
  { id: "album", label: "Album" },
  { id: "recent", label: "Recently added" },
  { id: "played", label: "Most played" },
  { id: "duration", label: "Longest" },
  { id: "manual", label: "Manual (drag to reorder)" },
];

const FILTERS: { id: LibraryFilter; label: string }[] = [
  { id: "all", label: "All" },
  { id: "liked", label: "Liked" },
  { id: "mostPlayed", label: "Most played" },
  { id: "rediscover", label: "Rediscover" },
  { id: "withLyrics", label: "Lyrics" },
];

export function sortIndices(indices: number[], tracks: Track[], key: SortKey, libraryOrder: string[] = []): number[] {
  const out = [...indices];
  const by = (f: (t: Track) => string) => (a: number, b: number) => collator.compare(f(tracks[a]), f(tracks[b])) || a - b;
  switch (key) {
    case "artist": return out.sort(by((t) => `${t.artist}\u0000${t.album}\u0000${t.title}`));
    case "album": return out.sort(by((t) => `${t.album}\u0000${t.title}`));
    case "recent": return out.sort((a, b) => (tracks[b].addedAt ?? 0) - (tracks[a].addedAt ?? 0) || b - a);
    case "played": return out.sort((a, b) => (tracks[b].playCount ?? 0) - (tracks[a].playCount ?? 0) || collator.compare(tracks[a].title, tracks[b].title));
    case "duration": return out.sort((a, b) => (tracks[b].duration ?? 0) - (tracks[a].duration ?? 0));
    case "manual": {
      const rank = new Map(libraryOrder.map((id, i) => [id, i]));
      return out.sort((a, b) => {
        const ai = rank.get(tracks[a]?.id);
        const bi = rank.get(tracks[b]?.id);
        if (ai === undefined && bi === undefined) return a - b;
        if (ai === undefined) return 1;
        if (bi === undefined) return -1;
        return ai - bi;
      });
    }
    default: return out.sort(by((t) => t.title));
  }
}

const folderOf = (t: Track) => (t.storage === "tree" ? t.path.split("/").slice(0, -1).join("/") : "");

/** Lightweight typo-tolerant search: matches all tokens or within 1 edit distance on words. */
function fuzzyMatch(haystack: string, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  const h = haystack.toLowerCase();
  if (h.includes(q)) return true;
  const qTokens = q.split(/\s+/).filter(Boolean);
  const hWords = h.split(/[\s\-_.,()/]+/).filter(Boolean);
  return qTokens.every((tok) => {
    if (h.includes(tok)) return true;
    if (tok.length < 4) return false;
    return hWords.some((w) => {
      if (Math.abs(w.length - tok.length) > 1) return false;
      let edits = 0, i = 0, j = 0;
      while (i < w.length && j < tok.length) {
        if (w[i] === tok[j]) { i++; j++; }
        else {
          if (++edits > 1) return false;
          if (w.length > tok.length) i++;
          else if (tok.length > w.length) j++;
          else { i++; j++; }
        }
      }
      return edits + (w.length - i) + (tok.length - j) <= 1;
    });
  });
}

function buildGroups(mode: Mode, tracks: Track[], playlists: Playlist[], sort: SortKey): Group[] {
  const map = new Map<string, Group>();
  const add = (kind: Group["kind"], key: string, title: string, sub: string, i: number) => {
    const t = tracks[i];
    let g = map.get(key);
    if (!g) map.set(key, (g = { kind, key, title, sub, cover: undefined, indices: [], newest: 0, plays: 0 }));
    if (!g.indices.includes(i)) g.indices.push(i);
    g.cover ??= t.thumb ?? t.cover;
    g.newest = Math.max(g.newest, t.addedAt ?? 0);
    g.plays += t.playCount ?? 0;
  };
  if (mode === "playlists") {
    return playlists.map((p) => {
      const indices = playlistIndices(p, tracks);
      return {
        kind: "playlist" as const, key: p.id, title: p.name, sub: `${indices.length} track${indices.length === 1 ? "" : "s"}`,
        cover: indices.length ? tracks[indices[0]].thumb ?? tracks[indices[0]].cover : undefined,
        indices, newest: p.updatedAt, plays: 0, playlistId: p.id,
      };
    });
  }
  tracks.forEach((t, i) => {
    if (mode === "albums") add("album", `${t.album}`, t.album, t.artist, i);
    else if (mode === "artists") {
      for (const art of extractArtists(t.artist, t.title)) {
        add("artist", art.toLowerCase(), art, "", i);
      }
    } else {
      const dir = folderOf(t);
      add("folder", dir || "\u0000copied", dir ? dir.split("/").pop()! : "Imported files", dir || "Copied into Sonora", i);
    }
  });
  const groups = [...map.values()];
  for (const g of groups) {
    if (g.kind === "album") {
      const artists = new Set(g.indices.map((i) => tracks[i].artist));
      g.sub = artists.size > 1 ? "Various artists" : [...artists][0] ?? "";
    } else if (g.kind === "artist") {
      g.sub = `${g.indices.length} track${g.indices.length === 1 ? "" : "s"}`;
    }
  }
  if (sort === "recent") return groups.sort((a, b) => b.newest - a.newest);
  if (sort === "played") return groups.sort((a, b) => b.plays - a.plays || collator.compare(a.title, b.title));
  return groups.sort((a, b) => collator.compare(a.title, b.title));
}

export function Pickers({
  dirRef,
  fileRef,
}: {
  dirRef: React.RefObject<HTMLInputElement | null>;
  fileRef: React.RefObject<HTMLInputElement | null>;
}) {
  const [saved, setSaved] = useState<FileSystemDirectoryHandle | undefined>();
  useEffect(() => {
    savedFolder().then(setSaved);
  }, []);

  const native = Capacitor.isNativePlatform();
  const openFolder = () => (window.showDirectoryPicker ? openFolderHandle() : dirRef.current?.click());

  return (
    <div className="flex flex-wrap items-center gap-2">
      <button type="button" className="outline-button" onClick={() => addAudioFiles(fileRef.current)}>
        <Icon n="plus" s={17} /> Add music
      </button>
      {(!native || IN_PLACE_FOLDERS) && (
      <button type="button" className="outline-button" onClick={native ? importFolderFromDevice : openFolder}>
        <Icon n="folder-open" s={17} /> {native ? "Add folder" : "Open folder"}
      </button>
      )}
      {saved && !native && (
        <button
          type="button"
          className="outline-button"
          onClick={() => openFolderHandle(saved)}
          title={`Reopen ${saved.name}`}
        >
          <Icon n="loop" s={16} /> Reopen “{saved.name}”
        </button>
      )}
    </div>
  );
}

const HOLD_MS = 380;
const MOVE_SLOP = 10;

interface DragState {
  from: number;
  to: number;
  y: number;
}

/**
 * Virtualised song list. `list` = library indices in display order.
 * With `onReorder`, press and hold a song to pick it up, then drag it to a new position.
 */
export function TrackList({
  list,
  tracks,
  playlistId,
  empty,
  tall = false,
  onReorder,
}: {
  list: number[];
  tracks: Track[];
  playlistId?: string;
  empty: string;
  tall?: boolean;
  onReorder?: (from: number, to: number) => void;
}) {
  const curId = useStore(player, (s) => s.trackId);
  const playing = useStore(player, (s) => s.playing);
  const likedIds = useStore(settings, (s) => s.likedIds);
  const lyrMap = useStore(lyricsStore, (s) => s.map);
  const [scroll, setScroll] = useState(0);
  const [h, setH] = useState(520);
  const [drag, setDragState] = useState<DragState | null>(null);
  const box = useRef<HTMLDivElement>(null);
  const dragRef = useRef<DragState | null>(null);
  const hold = useRef<{ timer: number; pos: number; id: number; el: HTMLElement; x0: number; y0: number; y: number } | null>(null);
  const swallowClick = useRef(false);
  const listLen = useRef(list.length);
  listLen.current = list.length;

  const setDrag = (d: DragState | null) => {
    dragRef.current = d;
    setDragState(d);
  };

  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setH(el.clientHeight || 520));
    ro.observe(el);
    // A held song must not scroll the list while it is being dragged.
    const block = (e: TouchEvent) => {
      if (dragRef.current && e.cancelable) e.preventDefault();
    };
    el.addEventListener("touchmove", block, { passive: false });
    return () => {
      ro.disconnect();
      el.removeEventListener("touchmove", block);
    };
  }, []);

  const targetFor = (clientY: number) => {
    const el = box.current;
    if (!el) return 0;
    const r = el.getBoundingClientRect();
    return Math.max(0, Math.min(listLen.current - 1, Math.floor((clientY - r.top + el.scrollTop) / ROW)));
  };

  const cancelHold = () => {
    if (hold.current) window.clearTimeout(hold.current.timer);
    hold.current = null;
  };

  const onRowDown = (e: React.PointerEvent<HTMLDivElement>, pos: number) => {
    if (!onReorder || (e.pointerType === "mouse" && e.button !== 0)) return;
    swallowClick.current = false;
    cancelHold();
    const h0 = { timer: 0, pos, id: e.pointerId, el: e.currentTarget, x0: e.clientX, y0: e.clientY, y: e.clientY };
    h0.timer = window.setTimeout(() => {
      const hd = hold.current;
      if (!hd) return;
      try {
        hd.el.setPointerCapture(hd.id);
      } catch {
        /* the pointer is already gone */
      }
      swallowClick.current = true;
      navigator.vibrate?.(18);
      setDrag({ from: hd.pos, to: hd.pos, y: hd.y });
    }, HOLD_MS);
    hold.current = h0;
  };

  const onRowMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const hd = hold.current;
    if (!hd) return;
    const d = dragRef.current;
    if (!d) {
      // Moved before the hold finished: the user is scrolling, not reordering.
      if (Math.hypot(e.clientX - hd.x0, e.clientY - hd.y0) > MOVE_SLOP) cancelHold();
      else hd.y = e.clientY;
      return;
    }
    hd.y = e.clientY;
    setDrag({ from: d.from, to: targetFor(e.clientY), y: e.clientY });
  };

  const onRowUp = () => {
    const d = dragRef.current;
    cancelHold();
    if (!d) return;
    setDrag(null);
    if (d.from !== d.to) onReorder?.(d.from, d.to);
  };

  const onRowCancel = () => {
    cancelHold();
    if (dragRef.current) setDrag(null);
  };

  // Hold a song near the top or bottom edge to scroll while dragging.
  const dragging = drag !== null;
  useEffect(() => {
    if (!dragging) return;
    const el = box.current;
    if (!el) return;
    const prev = el.style.scrollBehavior;
    el.style.scrollBehavior = "auto";
    let raf = 0;
    const tick = () => {
      const d = dragRef.current;
      const hd = hold.current;
      if (d && hd) {
        const r = el.getBoundingClientRect();
        const edge = 56;
        const dy = hd.y < r.top + edge ? hd.y - (r.top + edge) : hd.y > r.bottom - edge ? hd.y - (r.bottom - edge) : 0;
        if (dy) {
          const before = el.scrollTop;
          el.scrollTop = before + Math.max(-18, Math.min(18, dy / 4));
          if (el.scrollTop !== before) setDrag({ from: d.from, to: targetFor(hd.y), y: hd.y });
        }
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => {
      cancelAnimationFrame(raf);
      el.style.scrollBehavior = prev;
    };
  }, [dragging]);

  const start = Math.max(0, Math.floor(scroll / ROW) - 6);
  const end = Math.min(list.length, Math.ceil((scroll + h) / ROW) + 6);
  const positions: number[] = [];
  for (let p = start; p < end; p++) positions.push(p);
  if (drag && (drag.from < start || drag.from >= end)) positions.push(drag.from);

  // While dragging, the other rows slide to open a gap where the song would land.
  const shownAt = (p: number) => {
    if (!drag || p === drag.from) return p;
    if (drag.from < drag.to && p > drag.from && p <= drag.to) return p - 1;
    if (drag.from > drag.to && p >= drag.to && p < drag.from) return p + 1;
    return p;
  };
  const boxEl = box.current;
  const dragTop = drag && boxEl
    ? Math.max(0, Math.min((list.length - 1) * ROW, drag.y - boxEl.getBoundingClientRect().top + boxEl.scrollTop - ROW / 2))
    : 0;

  return (
    <div
      ref={box}
      className={`library-list relative ${tall ? "max-h-[62vh]" : "max-h-[540px]"} overflow-y-auto`}
      onScroll={(e) => setScroll(e.currentTarget.scrollTop)}
    >
      {list.length === 0 ? (
        <div className="workspace-empty flex flex-col items-center gap-3 py-6 text-center">
          <p>{empty}</p>
          {playlistId && (
            <button
              type="button"
              className="accent-button"
              onClick={() => openSongPicker(playlistId)}
            >
              <Icon n="plus" s={16} /> Add songs to playlist
            </button>
          )}
        </div>
      ) : (
        <div style={{ height: list.length * ROW }} className="relative">
          {positions.map((p) => {
            const ti = list[p];
            const t = tracks[ti];
            if (!t) return null;
            const isCurrent = t.id === curId;
            const isLiked = likedIds.includes(t.id);
            const isDragged = drag?.from === p;
            const metaParts = [t.artist];
            if (t.album && t.album !== t.artist) metaParts.push(t.album);
            if (t.sidecar || t.embeddedLyrics || lyrMap[t.id]?.lines.length) metaParts.push("LRC");
            if (t.rgTrack != null) metaParts.push("RG");
            if (t.playCount) metaParts.push(`${t.playCount}×`);

            return (
              <div
                key={t.id}
                className={`library-row absolute left-0 right-0 ${isCurrent ? "current" : ""} ${onReorder ? "reorderable" : ""} ${isDragged ? "dragging" : ""}`}
                style={{
                  top: isDragged ? dragTop : shownAt(p) * ROW,
                  height: ROW,
                  zIndex: isDragged ? 5 : undefined,
                  transition: drag && !isDragged ? "top 140ms ease" : undefined,
                }}
                onPointerDown={onReorder ? (e) => onRowDown(e, p) : undefined}
                onPointerMove={onReorder ? onRowMove : undefined}
                onPointerUp={onReorder ? onRowUp : undefined}
                onPointerCancel={onReorder ? onRowCancel : undefined}
                onContextMenu={onReorder ? (e) => e.preventDefault() : undefined}
              >
                <button
                  type="button"
                  className="library-row-main"
                  onClick={() => {
                    if (swallowClick.current) {
                      swallowClick.current = false;
                      return;
                    }
                    // Tapping the song that is already playing pauses/resumes it instead of restarting the queue.
                    if (isCurrent) engine.toggle();
                    else playFromList(list, ti);
                  }}
                >
                  <Cover src={t.thumb ?? t.cover} size={42} alt={t.title} />
                  <span className="queue-name">
                    <b>{t.title}</b>
                    <small>{metaParts.join(" · ")}</small>
                  </span>
                </button>

                <button
                  type="button"
                  className={`row-heart ${isLiked ? "liked" : ""}`}
                  onClick={() => toggleLikeTrack(t.id)}
                  aria-label={`Like ${t.title}`}
                  title={isLiked ? "Unlike song" : "Like song"}
                >
                  <Icon n="heart" s={17} fill={isLiked} />
                </button>

                <button
                  type="button"
                  className="row-heart"
                  onClick={() => openTrackMenu(ti, playlistId)}
                  aria-label={`More options for ${t.title}`}
                >
                  <Icon n="more" s={18} />
                </button>

                <span className="library-duration tabular-nums">
                  {isCurrent && playing ? (
                    <Icon n="audio-lines" s={16} className="ml-auto text-accent" />
                  ) : t.duration ? (
                    fmt(t.duration)
                  ) : (
                    "--:--"
                  )}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default function Library({
  dirRef,
  fileRef,
}: {
  dirRef: React.RefObject<HTMLInputElement | null>;
  fileRef: React.RefObject<HTMLInputElement | null>;
}) {
  const tracks = useStore(library, (s) => s.tracks);
  const loading = useStore(library, (s) => s.loading);
  const filter = useStore(library, (s) => s.filter);
  const likedIds = useStore(settings, (s) => s.likedIds);
  const sort = useStore(settings, (s) => s.librarySort);
  const playlists = useStore(userdata, (u) => u.playlists);
  const libraryOrder = useStore(userdata, (u) => u.libraryOrder);
  const lyrMap = useStore(lyricsStore, (s) => s.map);

  const [mode, setMode] = useState<Mode>("songs");
  const [q, setQ] = useState("");
  const [open, setOpen] = useState<{ kind: Group["kind"]; key: string } | null>(null);
  const m3uRef = useRef<HTMLInputElement>(null);
  useBackHandler(open !== null, () => setOpen(null));

  const groups = useMemo(
    () => (mode === "songs" ? [] : buildGroups(mode, tracks, playlists, sort)),
    [mode, tracks, playlists, sort],
  );
  const group = open ? groups.find((g) => g.key === open.key) : undefined;

  const visible = (i: number) => {
    const t = tracks[i];
    if (filter === "liked" && !likedIds.includes(t.id)) return false;
    if (filter === "mostPlayed" && (t.playCount ?? 0) < 1) return false;
    if (filter === "rediscover" && (t.playCount ?? 0) > 0 && Date.now() - (t.lastPlayedAt ?? 0) < 7 * 86400000) return false;
    if (filter === "withLyrics" && !t.sidecar && !t.embeddedLyrics && !lyrMap[t.id]?.lines.length) return false;
    if (!q.trim()) return true;
    const lyrText = lyrMap[t.id]?.lines.map((l) => l.text).join(" ") || t.embeddedLyrics || "";
    return fuzzyMatch(`${t.title} ${t.artist} ${t.album} ${t.fileName} ${lyrText}`, q);
  };

  const list = useMemo(() => {
    const base = group ? group.indices : tracks.map((_, i) => i);
    const kept = base.filter(visible);
    if (group?.kind === "playlist") return kept;
    const effectiveSort: SortKey =
      filter === "mostPlayed" ? "played" : group?.kind === "album" ? "album" : sort;
    return sortIndices(kept, tracks, effectiveSort, libraryOrder);
  }, [tracks, group, q, filter, likedIds, sort, lyrMap, libraryOrder]);

  // Playlist order is stored in the playlist; the all-songs order is saved separately by track id.
  const canReorderPlaylist = group?.kind === "playlist" && filter === "all" && !q.trim();
  const canReorderLibrary = !group && mode === "songs" && filter === "all" && !q.trim();
  const canReorder = canReorderPlaylist || canReorderLibrary;
  const reorderPlaylist = (from: number, to: number) => {
    const pl = playlists.find((x) => x.id === group?.playlistId);
    if (!pl) return;
    const a = pl.trackIds.indexOf(tracks[list[from]]?.id);
    const b = pl.trackIds.indexOf(tracks[list[to]]?.id);
    if (a >= 0 && b >= 0) movePlaylistTrack(pl.id, a, b);
  };

  const reorderLibrary = (from: number, to: number) => {
    const next = [...list];
    if (from < 0 || to < 0 || from >= next.length || to >= next.length || from === to) return;
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved);
    userdata.set({ libraryOrder: next.map((i) => tracks[i]?.id).filter((id): id is string => !!id) });
    settings.set({ librarySort: "manual" });
    toast("Manual song order saved");
  };

  const shownGroups = useMemo(() => {
    const s = q.trim();
    return s ? groups.filter((g) => fuzzyMatch(`${g.title} ${g.sub}`, s)) : groups;
  }, [groups, q]);

  const subtitle = group
    ? `${list.length} track${list.length === 1 ? "" : "s"}`
    : mode === "songs"
      ? `${tracks.length} ${tracks.length === 1 ? "track" : "tracks"}`
      : `${shownGroups.length} ${mode}`;

  const newPlaylist = () =>
    askText(
      "New playlist",
      "",
      (name) => {
        if (!name.trim()) return;
        const id = createPlaylist(name);
        toast(`Created “${name.trim()}”`);
        openSongPicker(id);
      },
      "Create",
    );

  const exportM3U = (playlistId: string) => {
    const p = playlists.find((x) => x.id === playlistId);
    if (!p) return;
    const content = playlistToM3U(p, tracks);
    const url = URL.createObjectURL(new Blob([content], { type: "audio/x-mpegurl" }));
    const a = Object.assign(document.createElement("a"), { href: url, download: `${p.name}.m3u8` });
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 2000);
    toast(`Exported "${p.name}.m3u8"`);
  };

  const MODES: [Mode, string][] = [["songs", "Songs"], ["albums", "Albums"], ["artists", "Artists"], ["folders", "Folders"], ["playlists", "Playlists"]];

  return (
    <div className="main-content inner-content">
      <input
        ref={m3uRef}
        type="file"
        accept=".m3u,.m3u8,audio/x-mpegurl"
        hidden
        onChange={async (e) => {
          const f = e.target.files?.[0];
          e.target.value = "";
          if (!f) return;
          const text = await f.text();
          const res = importM3UPlaylist(text, f.name, tracks);
          toast(`Imported "${res.name}" (${res.matched} matched)`);
        }}
      />

      <div className="section-kicker">
        <span /> LIBRARY
      </div>
      <h1>{group ? group.title : "Library"}</h1>
      <p className="subtitle">
        {group ? `${group.sub && group.kind !== "playlist" ? group.sub + " · " : ""}${subtitle}` : subtitle}
        {loading ? " · Loading…" : ""}
      </p>

      {group ? (
        <div className="group-actions">
          <button type="button" className="outline-button" onClick={() => setOpen(null)}>
            <Icon n="right" s={16} className="flip" /> Back
          </button>
          <button type="button" className="accent-button" disabled={!list.length} onClick={() => playIndices(list, 0, false)}>
            <Icon n="play" s={16} /> Play
          </button>
          <button type="button" className="outline-button" disabled={!list.length} onClick={() => playShuffled(list)}>
            <Icon n="shuffle" s={16} /> Shuffle
          </button>
          <button type="button" className="outline-button" disabled={!list.length} onClick={() => list.forEach((i) => playNext(i, true))}>
            <Icon n="next" s={16} /> Play next
          </button>
          <button type="button" className="outline-button" disabled={!list.length} onClick={() => enqueueMany(list)}>
            <Icon n="list-music" s={16} /> Add to queue
          </button>
          {group.playlistId ? (
            <>
              <button
                type="button"
                className="accent-button"
                aria-label="Add songs to playlist"
                onClick={() => openSongPicker(group.playlistId!)}
              >
                <Icon n="plus" s={16} /> Add songs
              </button>
              {list.length > 0 && (
                <button
                  type="button"
                  className="outline-button"
                  aria-label="Copy songs to another playlist"
                  onClick={() => openPlaylistPicker(list.map((i) => tracks[i]?.id).filter((id): id is string => !!id))}
                >
                  <Icon n="plus" s={16} /> Copy to another playlist
                </button>
              )}
            </>
          ) : (
            <button
              type="button"
              className="outline-button"
              disabled={!list.length}
              aria-label="Add to playlist"
              onClick={() => openPlaylistPicker(list.map((i) => tracks[i]?.id).filter((id): id is string => !!id))}
            >
              <Icon n="plus" s={16} /> Add to playlist
            </button>
          )}
          {group.playlistId && (
            <>
              <button
                type="button"
                className="outline-button"
                onClick={() => exportM3U(group.playlistId!)}
              >
                <Icon n="download" s={15} /> Export M3U
              </button>
              <button
                type="button"
                className="outline-button"
                onClick={() =>
                  askText("Rename playlist", group.title, (n) => {
                    if (n.trim()) renamePlaylist(group.playlistId!, n);
                  }, "Rename")
                }
              >
                Rename
              </button>
              <button
                type="button"
                className="outline-button"
                onClick={() =>
                  askConfirm("Delete playlist", `“${group.title}” is deleted. The songs stay in your library.`, () => {
                    deletePlaylist(group.playlistId!);
                    setOpen(null);
                  })
                }
              >
                Delete
              </button>
            </>
          )}
        </div>
      ) : (
        <div className="tabs" role="tablist" aria-label="Browse library">
          {MODES.map(([id, label]) => (
            <button key={id} type="button" role="tab" aria-selected={mode === id} className={mode === id ? "selected" : ""} onClick={() => setMode(id)}>
              {label}
            </button>
          ))}
        </div>
      )}

      {mode === "songs" && !group && tracks.length > 0 && (
        <div className="group-actions">
          <button type="button" className="accent-button" disabled={!list.length} onClick={() => playIndices(list, 0, false)}>
            <Icon n="play" s={16} /> Play
          </button>
          <button type="button" className="outline-button" disabled={!list.length} onClick={() => playShuffled(list)}>
            <Icon n="shuffle" s={16} /> Shuffle
          </button>
        </div>
      )}

      <div className="library-tools">
        <div className="search-box">
          <Icon n="search" s={18} />
          <input
            placeholder={mode === "songs" || group ? "Search title, artist, album, or lyrics..." : `Search ${mode}...`}
            aria-label="Search your music"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
          {q && (
            <button type="button" className="bg-transparent p-1 text-muted hover:text-fg" aria-label="Clear search" onClick={() => setQ("")}>
              <Icon n="x" s={14} />
            </button>
          )}
        </div>
        {(mode === "songs" || group) && (
          <div className="segmented overflow-x-auto">
            {FILTERS.map((f) => (
              <button
                key={f.id}
                type="button"
                className={filter === f.id ? "selected" : ""}
                onClick={() => library.set({ filter: f.id })}
              >
                {f.id === "liked" ? `Liked (${likedIds.length})` : f.label}
              </button>
            ))}
          </div>
        )}
        {group?.kind !== "playlist" && (
          <select
            className="sort-select"
            aria-label="Sort by"
            value={sort}
            onChange={(e) => settings.set({ librarySort: e.target.value as SortKey })}
          >
            {SORTS.map((s) => (
              <option key={s.id} value={s.id}>
                {s.label}
              </option>
            ))}
          </select>
        )}
        {mode === "songs" && !group && <Pickers dirRef={dirRef} fileRef={fileRef} />}
        {mode === "playlists" && !group && (
          <div className="flex items-center gap-2">
            <button type="button" className="outline-button" onClick={newPlaylist}>
              <Icon n="plus" s={17} /> New playlist
            </button>
            <button type="button" className="outline-button" onClick={() => m3uRef.current?.click()} title="Import M3U playlist">
              <Icon n="upload" s={16} /> Import M3U
            </button>
          </div>
        )}
      </div>

      {mode === "songs" || group ? (
        <>
          <div className="library-heading">
            <span>SONG</span>
            <span>DURATION</span>
          </div>
          {canReorder && list.length > 1 && (
            <p className="library-footnote">
              Press and hold a song, then drag to reorder {canReorderPlaylist ? "this playlist" : "your library"}.
            </p>
          )}
          <TrackList
            list={list}
            tracks={tracks}
            playlistId={group?.playlistId}
            onReorder={
              canReorderPlaylist ? reorderPlaylist : canReorderLibrary ? reorderLibrary : undefined
            }
            empty={
              filter === "liked"
                ? "No liked songs yet."
                : filter === "mostPlayed"
                  ? "No play counts recorded yet. Listen to a song for 30s to count a play."
                  : filter === "withLyrics"
                    ? "No tracks with lyrics yet."
                    : group
                      ? group.playlistId
                        ? "This playlist is empty. Tap “Add songs” above to fill it."
                        : "Nothing here yet."
                      : "No songs yet. Add music to your library."
            }
          />
        </>
      ) : (
        <div className="group-grid">
          {shownGroups.length === 0 && (
            <div className="workspace-empty">
              {mode === "playlists" ? "No playlists yet. Make one, then add songs with “Add songs”, or from any song’s ⋯ menu." : "Nothing to show yet."}
            </div>
          )}
          {shownGroups.map((g) => (
            <button key={g.key} type="button" className="group-card" onClick={() => setOpen({ kind: g.kind, key: g.key })}>
              <Cover src={g.cover} size={56} alt={g.title} />
              <span className="queue-name">
                <b>{g.title}</b>
                <small>{[g.sub, g.kind !== "artist" && g.kind !== "playlist" ? `${g.indices.length} tracks` : ""].filter(Boolean).join(" · ")}</small>
              </span>
            </button>
          ))}
        </div>
      )}

      <p className="library-footnote">
        Reads ID3v2 &amp; FLAC headers (embedded cover art, ReplayGain, and lyrics), separates featured artists, and automatically pairs sidecar{" "}
        <code>.lrc / .srt / .vtt</code> files from your folder.
      </p>
    </div>
  );
}
