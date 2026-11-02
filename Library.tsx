import { useEffect, useMemo, useRef, useState } from "react";
import { Capacitor } from "@capacitor/core";
import { useStore, library, player, settings, lyricsStore, fmt, toggleLikeTrack, toast, type SortKey, type Track, type LibraryFilter } from "./store";
import { IN_PLACE_FOLDERS, addAudioFiles, importFolderFromDevice, openFolderHandle, savedFolder } from "./library";
import { enqueueMany, playFromList, playIndices, playNext } from "./queue";
import { askConfirm, askText, openPlaylistPicker, openTrackMenu } from "./sheets";
import { useBackHandler } from "./useBack";
import { extractArtists } from "./tags";
import {
  createPlaylist,
  deletePlaylist,
  importM3UPlaylist,
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
];

const FILTERS: { id: LibraryFilter; label: string }[] = [
  { id: "all", label: "All" },
  { id: "liked", label: "Liked" },
  { id: "mostPlayed", label: "Most played" },
  { id: "rediscover", label: "Rediscover" },
  { id: "withLyrics", label: "Lyrics" },
];

export function sortIndices(indices: number[], tracks: Track[], key: SortKey): number[] {
  const out = [...indices];
  const by = (f: (t: Track) => string) => (a: number, b: number) => collator.compare(f(tracks[a]), f(tracks[b])) || a - b;
  switch (key) {
    case "artist": return out.sort(by((t) => `${t.artist}\u0000${t.album}\u0000${t.title}`));
    case "album": return out.sort(by((t) => `${t.album}\u0000${t.title}`));
    case "recent": return out.sort((a, b) => (tracks[b].addedAt ?? 0) - (tracks[a].addedAt ?? 0) || b - a);
    case "played": return out.sort((a, b) => (tracks[b].playCount ?? 0) - (tracks[a].playCount ?? 0) || collator.compare(tracks[a].title, tracks[b].title));
    case "duration": return out.sort((a, b) => (tracks[b].duration ?? 0) - (tracks[a].duration ?? 0));
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

/** Virtualised song list that scrolls with the page (no nested scroll box). `list` = library indices in display order. */
export function TrackList({
  list,
  tracks,
  playlistId,
  empty,
  onOpenPlayer,
}: {
  list: number[];
  tracks: Track[];
  playlistId?: string;
  empty: string;
  onOpenPlayer?: () => void;
}) {
  const curId = useStore(player, (s) => s.trackId);
  const playing = useStore(player, (s) => s.playing);
  const likedIds = useStore(settings, (s) => s.likedIds);
  const lyrMap = useStore(lyricsStore, (s) => s.map);
  const [span, setSpan] = useState<[number, number]>([0, 24]);
  const box = useRef<HTMLDivElement>(null);
  const count = list.length;

  useEffect(() => {
    const el = box.current;
    if (!el) return;
    let sp: HTMLElement | null = el.parentElement;
    while (sp && !/(auto|scroll)/.test(getComputedStyle(sp).overflowY)) sp = sp.parentElement;
    const target: HTMLElement | Window = sp ?? window;
    let raf = 0;
    const measure = () => {
      raf = 0;
      const viewTop = sp ? sp.getBoundingClientRect().top : 0;
      const viewH = sp ? sp.clientHeight : window.innerHeight;
      const above = viewTop - el.getBoundingClientRect().top; // px of the list scrolled above the viewport
      const a = Math.max(0, Math.floor(above / ROW) - 6);
      const b = Math.min(count, Math.ceil((above + viewH) / ROW) + 6);
      setSpan((p) => (p[0] === a && p[1] === b ? p : [a, b]));
    };
    const queue = () => {
      if (!raf) raf = requestAnimationFrame(measure);
    };
    measure();
    target.addEventListener("scroll", queue, { passive: true });
    window.addEventListener("resize", queue);
    const ro = new ResizeObserver(queue);
    if (sp) ro.observe(sp);
    return () => {
      if (raf) cancelAnimationFrame(raf);
      target.removeEventListener("scroll", queue);
      window.removeEventListener("resize", queue);
      ro.disconnect();
    };
  }, [count]);

  if (count === 0) return <div className="workspace-empty">{empty}</div>;

  return (
    <div ref={box} className="library-list relative" style={{ height: count * ROW }}>
      {list.slice(span[0], span[1]).map((ti, k) => {
        const t = tracks[ti];
        if (!t) return null;
        const isCurrent = t.id === curId;
        const isLiked = likedIds.includes(t.id);
        const metaParts = [t.artist];
        if (t.album && t.album !== t.artist) metaParts.push(t.album);
        if (t.sidecar || t.embeddedLyrics || lyrMap[t.id]?.lines.length) metaParts.push("LRC");
        if (t.rgTrack != null) metaParts.push("RG");
        if (t.playCount) metaParts.push(`${t.playCount}×`);

        return (
          <div
            key={t.id}
            className={`library-row absolute left-0 right-0 ${isCurrent ? "current" : ""}`}
            style={{ top: (span[0] + k) * ROW, height: ROW }}
          >
            <button
              type="button"
              className="library-row-main"
              onClick={() => (isCurrent && onOpenPlayer ? onOpenPlayer() : playFromList(list, ti))}
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
  );
}

export default function Library({
  dirRef,
  fileRef,
  onOpenPlayer,
}: {
  dirRef: React.RefObject<HTMLInputElement | null>;
  fileRef: React.RefObject<HTMLInputElement | null>;
  onOpenPlayer?: () => void;
}) {
  const tracks = useStore(library, (s) => s.tracks);
  const loading = useStore(library, (s) => s.loading);
  const filter = useStore(library, (s) => s.filter);
  const likedIds = useStore(settings, (s) => s.likedIds);
  const sort = useStore(settings, (s) => s.librarySort);
  const playlists = useStore(userdata, (u) => u.playlists);
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
    return sortIndices(kept, tracks, effectiveSort);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tracks, group, q, filter, likedIds, sort, lyrMap]);

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
        createPlaylist(name);
        toast(`Created “${name.trim()}”`);
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
        {group ? `${group.sub ? group.sub + " · " : ""}${subtitle}` : subtitle}
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
          <button type="button" className="outline-button" disabled={!list.length} onClick={() => playIndices(list, 0, true)}>
            <Icon n="shuffle" s={16} /> Shuffle
          </button>
          <button type="button" className="outline-button" disabled={!list.length} onClick={() => list.forEach((i) => playNext(i, true))}>
            <Icon n="next" s={16} /> Play next
          </button>
          <button type="button" className="outline-button" disabled={!list.length} onClick={() => enqueueMany(list)}>
            <Icon n="list-music" s={16} /> Add to queue
          </button>
          <button
            type="button"
            className="outline-button"
            disabled={!list.length}
            onClick={() => openPlaylistPicker(list.map((i) => tracks[i]?.id).filter((id): id is string => !!id))}
          >
            <Icon n="plus" s={16} /> Add to playlist
          </button>
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
          <TrackList
            onOpenPlayer={onOpenPlayer}
            list={list}
            tracks={tracks}
            playlistId={group?.playlistId}
            empty={
              filter === "liked"
                ? "No liked songs yet."
                : filter === "mostPlayed"
                  ? "No play counts recorded yet. Listen to a song for 30s to count a play."
                  : filter === "withLyrics"
                    ? "No tracks with lyrics yet."
                    : group
                      ? "Nothing here yet."
                      : "No songs yet. Add music to your library."
            }
          />
        </>
      ) : (
        <div className="group-grid">
          {shownGroups.length === 0 && (
            <div className="workspace-empty">
              {mode === "playlists" ? "No playlists yet. Make one, then add songs from the ⋯ menu." : "Nothing to show yet."}
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
