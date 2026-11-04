import { useMemo, type KeyboardEvent } from "react";
import { useStore, settings, library, player, toggleLikeTrack } from "./store";
import { engine } from "./engine";
import { useCurrent } from "./Player";
import { playIndices, playShuffled } from "./queue";
import { Icon, Cover, Chip } from "./ui";
import { TrackList, sortIndices } from "./Library";
import { userdata } from "./userdata";

export default function Home({
  onNavigate,
}: {
  onNavigate: (v: "room" | "library" | "lyrics" | "sound" | "player") => void;
}) {
  const track = useCurrent();
  const playing = useStore(player, (s) => s.playing);
  const tracks = useStore(library, (s) => s.tracks);
  const likedIds = useStore(settings, (s) => s.likedIds);
  const sort = useStore(settings, (s) => s.librarySort);
  const libraryOrder = useStore(userdata, (u) => u.libraryOrder);

  // Every song, in the chosen sort order; saved manual order follows the tracks by id.
  const list = useMemo(
    () => sortIndices(tracks.map((_, i) => i), tracks, sort, libraryOrder),
    [tracks, sort, libraryOrder],
  );

  const reorderAllSongs = (from: number, to: number) => {
    const next = [...list];
    if (from < 0 || to < 0 || from >= next.length || to >= next.length || from === to) return;
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved);
    userdata.set({ libraryOrder: next.map((i) => tracks[i]?.id).filter((id): id is string => !!id) });
    settings.set({ librarySort: "manual" });
  };

  const startMix = (mode: "mostPlayed" | "rediscover" | "liked") => {
    if (!tracks.length) return;
    const all = tracks.map((_, i) => i);
    let picked = all;
    if (mode === "liked") picked = all.filter((i) => likedIds.includes(tracks[i].id));
    else if (mode === "mostPlayed")
      picked = [...all].sort((a, b) => (tracks[b].playCount ?? 0) - (tracks[a].playCount ?? 0)).slice(0, 25);
    else picked = [...all].sort((a, b) => (tracks[a].lastPlayedAt ?? 0) - (tracks[b].lastPlayedAt ?? 0)).slice(0, 25);
    if (!picked.length) picked = all;
    if (mode === "mostPlayed") playIndices(picked, 0, false);
    else playShuffled(picked);
  };

  const openPlayer = () => onNavigate("player");
  const onCardKey = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.target !== e.currentTarget) return; // keys pressed on the buttons inside keep their own meaning
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      openPlayer();
    }
  };

  return (
    <div className="main-content room-content android-home">
      <div className="home-heading">
        <h1>Home</h1>
      </div>

      <section className="home-now-playing">
        {track ? (
          <div
            className={`feature-track${playing ? " is-playing" : ""}`}
            data-theme-card="true"
            role="button"
            tabIndex={0}
            aria-label={`Open player: ${track.title}`}
            onClick={openPlayer}
            onKeyDown={onCardKey}
          >
            <Cover src={track.cover} size={112} className="home-cover" alt={track.title} />
            <div className="feature-info">
              <div className="now-label">{playing ? "PLAYING" : "PAUSED"}</div>
              <div className="track-title">{track.title}</div>
              <div className="track-artist">{track.artist}</div>
              <div className="feature-buttons">
                <button
                  type="button"
                  className="round-play"
                  onClick={(e) => {
                    e.stopPropagation();
                    engine.toggle();
                  }}
                  aria-label={playing ? "Pause track" : "Play track"}
                >
                  <Icon n={playing ? "pause" : "play"} s={21} fill />
                </button>
                <button
                  type="button"
                  className={`like-button ${likedIds.includes(track.id) ? "liked" : ""}`}
                  title={likedIds.includes(track.id) ? "Unlike track" : "Like track"}
                  aria-label="Like track"
                  onClick={(e) => {
                    e.stopPropagation();
                    toggleLikeTrack(track.id);
                  }}
                >
                  <Icon n="heart" s={19} fill={likedIds.includes(track.id)} />
                </button>
              </div>
            </div>
          </div>
        ) : (
          <div className="empty-library">
            <div className="empty-library-icon"><Icon n="music" s={27} /></div>
            <div>
              <h3>No songs yet</h3>
              <p>Add music to start listening. Files are copied into Sonora, and playback keeps going with the screen off.</p>
            </div>
            <button type="button" className="accent-button" onClick={() => onNavigate("library")}>
              <Icon n="plus" s={16} /> Add music
            </button>
          </div>
        )}
      </section>

      {tracks.length > 0 && (
        <div className="mb-5 flex flex-wrap items-center gap-2">
          <Chip onClick={() => playShuffled(list)}>
            <span className="inline-flex items-center gap-1.5"><Icon n="shuffle" s={13} /> Shuffle all</span>
          </Chip>
          <Chip onClick={() => startMix("mostPlayed")}>
            <span className="inline-flex items-center gap-1.5"><Icon n="sparkles" s={13} /> Most played</span>
          </Chip>
          <Chip onClick={() => startMix("rediscover")}>
            <span className="inline-flex items-center gap-1.5"><Icon n="clock" s={13} /> Rediscover</span>
          </Chip>
          {likedIds.length > 0 && (
            <Chip onClick={() => startMix("liked")}>
              <span className="inline-flex items-center gap-1.5"><Icon n="heart" s={13} fill /> Liked mix ({likedIds.length})</span>
            </Chip>
          )}
        </div>
      )}

      {tracks.length > 0 && (
        <section className="queue-section home-music-list">
          <div className="section-head">
            <h2>All songs</h2>
            <span className="text-sm text-muted tabular-nums">{tracks.length}</span>
          </div>
          <p className="library-footnote">Press and hold a song, then drag it to set your manual order.</p>
          <TrackList
            list={list}
            tracks={tracks}
            tall
            onReorder={reorderAllSongs}
            empty="Your imported songs will appear here."
          />
        </section>
      )}
    </div>
  );
}
