import { useMemo } from "react";
import { useStore, settings, library, player, toggleLikeTrack } from "./store";
import { engine } from "./engine";
import { useCurrent } from "./Player";
import { playIndices } from "./queue";
import { Icon, Cover, Chip } from "./ui";
import { TrackList, sortIndices } from "./Library";

export default function Home({
  onNavigate,
}: {
  onNavigate: (v: "room" | "library" | "lyrics" | "sound" | "player") => void;
}) {
  const track = useCurrent();
  const playing = useStore(player, (s) => s.playing);
  const tracks = useStore(library, (s) => s.tracks);
  const sort = useStore(settings, (s) => s.librarySort);
  const likedIds = useStore(settings, (s) => s.likedIds);

  const list = useMemo(() => sortIndices(tracks.map((_, i) => i), tracks, sort), [tracks, sort]);
  const open = () => onNavigate("player");

  const startSmartMix = (mode: "all" | "mostPlayed" | "rediscover" | "liked") => {
    if (!tracks.length) return;
    let indices = tracks.map((_, i) => i);
    if (mode === "liked") {
      indices = indices.filter((i) => likedIds.includes(tracks[i].id));
    } else if (mode === "mostPlayed") {
      indices = [...indices]
        .sort((a, b) => (tracks[b].playCount ?? 0) - (tracks[a].playCount ?? 0))
        .slice(0, Math.min(25, tracks.length));
    } else if (mode === "rediscover") {
      indices = [...indices]
        .sort((a, b) => (tracks[a].lastPlayedAt ?? 0) - (tracks[b].lastPlayedAt ?? 0))
        .slice(0, Math.min(25, tracks.length));
    }
    if (!indices.length) indices = tracks.map((_, i) => i);
    playIndices(indices, 0, mode !== "mostPlayed");
  };

  return (
    <div className="main-content room-content android-home">
      <section className="home-now-playing">
        {track ? (
          <div className="feature-track tappable" data-theme-card="true" onClick={open}>
            <Cover src={track.cover} size={112} className="home-cover" alt="" />
            <div className="feature-info">
              <div className="now-label">{playing ? "PLAYING" : "PAUSED"}</div>
              <div className="track-title">
                <button type="button" className="feature-title-btn">{track.title}</button>
              </div>
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
            <Icon n="right" s={20} className="feature-chevron" />
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
        <>
          <div className="home-chips">
            <Chip onClick={() => startSmartMix("all")}>
              <span className="inline-flex items-center gap-1.5"><Icon n="shuffle" s={13} /> Shuffle all</span>
            </Chip>
            <Chip onClick={() => startSmartMix("mostPlayed")}>
              <span className="inline-flex items-center gap-1.5"><Icon n="sparkles" s={13} /> Most played</span>
            </Chip>
            <Chip onClick={() => startSmartMix("rediscover")}>
              <span className="inline-flex items-center gap-1.5"><Icon n="clock" s={13} /> Rediscover</span>
            </Chip>
            {likedIds.length > 0 && (
              <Chip onClick={() => startSmartMix("liked")}>
                <span className="inline-flex items-center gap-1.5"><Icon n="heart" s={13} fill /> Liked ({likedIds.length})</span>
              </Chip>
            )}
          </div>

          <section className="queue-section home-music-list">
            <div className="section-head">
              <h2>All songs</h2>
              <span className="home-count">{tracks.length}</span>
            </div>
            <TrackList list={list} tracks={tracks} empty="Your imported songs will appear here." onOpenPlayer={open} />
          </section>
        </>
      )}
    </div>
  );
}
