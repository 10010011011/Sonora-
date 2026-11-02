import { useState } from "react";
import { useStore, settings, library, player, toast, toggleLikeTrack } from "./store";
import { engine } from "./engine";
import { useCurrent, SeekBar, Transport } from "./Player";
import { LyricsScroller } from "./LyricsScroller";
import { openBookmarks, openQueue, openTrackMenu } from "./sheets";
import { Cover, Icon } from "./ui";

export default function NowPlaying({
  onNavigate,
  onClose,
}: {
  onNavigate: (v: "room" | "library" | "lyrics" | "sound") => void;
  onClose: () => void;
}) {
  const track = useCurrent();
  const playing = useStore(player, (s) => s.playing);
  const likedIds = useStore(settings, state => state.likedIds);
  const simple = useStore(settings, state => state.uiMode === "simple");
  const trackIdx = useStore(library, (l) => (track ? l.tracks.findIndex((t) => t.id === track.id) : -1));
  const liked = track ? likedIds.includes(track.id) : false;
  const [surface, setSurface] = useState<"cover" | "lyrics">("cover");
  const [holdSpeed, setHoldSpeed] = useState(false);

  const startHoldSpeed = () => {
    if (!playing) return;
    setHoldSpeed(true);
    engine.els.forEach((el) => {
      el.playbackRate = 1.5;
    });
    toast("1.5× speed (release to restore)");
  };

  const endHoldSpeed = () => {
    if (!holdSpeed) return;
    setHoldSpeed(false);
    engine.apply(settings.get());
  };

  return (
    <div className="main-content now-playing-screen">
      <div className="now-playing-toolbar">
        <button type="button" onClick={onClose} aria-label="Close player">
          <Icon n="down" s={21} />
        </button>
        <span>Now playing</span>
        <button type="button" onClick={() => trackIdx >= 0 && openTrackMenu(trackIdx)} aria-label="More options" disabled={trackIdx < 0}>
          <Icon n="more" s={21} />
        </button>
      </div>

      {track ? (
        <>
          <div className="now-playing-switch segmented" role="tablist" aria-label="Now playing display">
            <button
              type="button"
              role="tab"
              aria-selected={surface === "cover"}
              className={surface === "cover" ? "selected" : ""}
              onClick={() => setSurface("cover")}
            >
              Cover
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={surface === "lyrics"}
              className={surface === "lyrics" ? "selected" : ""}
              onClick={() => setSurface("lyrics")}
            >
              Lyrics
            </button>
          </div>

          <div className={"now-playing-body" + (surface === "lyrics" ? " lyrics-mode" : "")}>
            {surface === "cover" ? (
              <div
                className="relative cursor-pointer select-none"
                title="Double-tap to like · Hold for 1.5× speed"
                onDoubleClick={() => {
                  const nowLiked = toggleLikeTrack(track.id);
                  toast(nowLiked ? "Added to Liked songs" : "Removed from Liked songs");
                }}
                onPointerDown={startHoldSpeed}
                onPointerUp={endHoldSpeed}
                onPointerLeave={endHoldSpeed}
                onPointerCancel={endHoldSpeed}
              >
                <Cover
                  src={track.cover}
                  size={264}
                  className={`now-playing-cover transition-transform duration-300 ${playing ? "scale-100" : "scale-[0.97] opacity-95"}`}
                  alt={track.title}
                />
              </div>
            ) : (
              <LyricsScroller className="now-lyrics-view" onOpenStudio={() => onNavigate("lyrics")} />
            )}

            <div className="now-playing-track">
              <div className="now-playing-meta">
                <div>
                  <h1>{track.title}</h1>
                  <p>{track.artist}</p>
                </div>
                <button
                  type="button"
                  className={"like-button" + (liked ? " liked" : "")}
                  onClick={() => toggleLikeTrack(track.id)}
                  aria-label={liked ? "Unlike track" : "Like track"}
                >
                  <Icon n="heart" s={21} fill={liked} />
                </button>
              </div>
              <SeekBar />
              <Transport size={1.15} />
              <div className="now-playing-links">
                <button type="button" onClick={() => onNavigate("lyrics")}>
                  <Icon n="lyrics" s={17} /> Lyrics studio
                </button>
                <button type="button" onClick={openBookmarks}>
                  <Icon n="clock" s={17} /> Bookmarks
                </button>
                <button type="button" onClick={openQueue}>
                  <Icon n="list-music" s={17} /> Queue
                </button>
                {!simple && (
                  <button type="button" onClick={() => onNavigate("sound")}>
                    <Icon n="sliders" s={17} /> Sound
                  </button>
                )}
              </div>
            </div>
          </div>
        </>
      ) : (
        <div className="empty-library">
          <div className="empty-library-icon"><Icon n="music" s={27} /></div>
          <div>
            <h3>No track selected</h3>
            <p>Choose a song from your library.</p>
          </div>
          <button type="button" className="accent-button" onClick={() => onNavigate("library")}>
            Library
          </button>
        </div>
      )}
    </div>
  );
}
