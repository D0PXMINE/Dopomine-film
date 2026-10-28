import { ReactNode, useEffect, useRef, useState } from "react";
import type { HistoryItem, Movie } from "../../shared/types";
import { posterLargeUrl, posterUrl } from "../../shared/types";
import { meta, rating } from "../lib/format";
import { openDetails, play } from "../lib/state";
import { Halftone } from "../halftone/Halftone";
import { Icon } from "./icons";

/** Kinopoisk poster with a halftone placeholder while it loads (or when there is none). */
export function Poster({ id, title, className, large }: { id: number; title?: string; className?: string; large?: boolean }) {
  const [state, setState] = useState<"loading" | "ok" | "none">("loading");
  // the big poster is missing for some titles: fall back to the regular one
  const [src, setSrc] = useState(large ? posterLargeUrl(id) : posterUrl(id));
  const failed = () => (src != posterUrl(id) ? setSrc(posterUrl(id)) : setState("none"));
  return (
    <div className={`poster ${className ?? ""}`}>
      {state != "ok" && <Halftone className="poster-art" variant="glyph" cell={large ? 6 : 5} seed={id % 997} />}
      <img
        src={src}
        alt={title ?? ""}
        loading="lazy"
        draggable={false}
        style={{ opacity: state == "ok" ? 1 : 0 }}
        onLoad={e => ((e.target as HTMLImageElement).naturalWidth > 10 ? setState("ok") : failed())}
        onError={failed}
      />
    </div>
  );
}

export function RatingTag({ value }: { value?: number | null }) {
  if (value == null) return null;
  return <span className="rating-tag">{rating(value)}</span>;
}

export function MovieCard({ movie, size = "md", watch }: { movie: Movie; size?: "md" | "lg"; watch?: boolean }) {
  return (
    <div className={`movie-card ${size}`} role="button" tabIndex={0} onClick={() => openDetails(movie.id)} onKeyDown={e => e.key == "Enter" && openDetails(movie.id)}>
      <div className="movie-card-cover">
        <Poster id={movie.id} title={movie.title} />
        <RatingTag value={movie.ratingKp ?? movie.ratingImdb} />
        <button type="button" className="cover-play" aria-label="Смотреть" onClick={e => { e.stopPropagation(); play(movie); }}>
          <Icon name="play" size={18} filled />
        </button>
      </div>
      <div className="movie-card-title" title={movie.title}>{movie.title}</div>
      <div className="movie-card-meta mono">{meta(movie) || " "}</div>
      {watch && (
        <button type="button" className="btn primary sm block" onClick={e => { e.stopPropagation(); play(movie); }}>
          <Icon name="play" size={13} filled /> Смотреть
        </button>
      )}
    </div>
  );
}

export function MovieCardSkeleton({ size = "md" }: { size?: "md" | "lg" }) {
  return (
    <div className={`movie-card ${size}`}>
      <div className="movie-card-cover skeleton" />
      <div className="skeleton line" />
      <div className="skeleton line short" />
    </div>
  );
}

export function ContinueCard({ item }: { item: HistoryItem }) {
  const progress = item.durationSec > 0 ? Math.min(1, item.positionSec / item.durationSec) : 0;
  const sub = item.lastSeason != null && item.lastEpisode != null ? `${item.lastSeason} сезон · ${item.lastEpisode} серия` : meta(item.movie, 1);
  return (
    <div className="continue-card" role="button" tabIndex={0} onClick={() => play(item.movie)}>
      <Poster id={item.movie.id} title={item.movie.title} className="thumb" />
      <div className="text">
        <div className="title truncate">{item.movie.title}</div>
        <div className="mono muted truncate">{sub || " "}</div>
        <div className="progress"><span style={{ width: `${progress * 100}%` }} /></div>
      </div>
      <span className="play-btn sm"><Icon name="play" size={14} filled /></span>
    </div>
  );
}

/** A titled horizontal row with arrows (desktop has no swipe). */
export function Shelf({ title, onAll, children, loading, empty }: { title: string; onAll?: () => void; children: ReactNode; loading?: boolean; empty?: boolean }) {
  const row = useRef<HTMLDivElement | null>(null);
  const [edges, setEdges] = useState({ start: true, end: false });
  const update = () => {
    const el = row.current;
    if (!el) return;
    setEdges({ start: el.scrollLeft < 8, end: el.scrollLeft + el.clientWidth >= el.scrollWidth - 8 });
  };
  useEffect(update, [children, loading]);
  const scroll = (dir: number) => row.current?.scrollBy({ left: dir * row.current.clientWidth * 0.85, behavior: "smooth" });
  if (empty) return null;
  return (
    <section className="shelf">
      <div className="shelf-head">
        <h2 className="shelf-title">{title}</h2>
        <div className="shelf-actions">
          {onAll && <button type="button" className="link mono" onClick={onAll}>Все</button>}
          <button type="button" className="icon-btn sm" aria-label="Назад" disabled={edges.start} onClick={() => scroll(-1)}><Icon name="chevron-left" size={16} /></button>
          <button type="button" className="icon-btn sm" aria-label="Вперёд" disabled={edges.end} onClick={() => scroll(1)}><Icon name="chevron-right" size={16} /></button>
        </div>
      </div>
      <div className="shelf-row" ref={row} onScroll={update}>{children}</div>
    </section>
  );
}

/** Poster grid that asks for the next page when its end scrolls into view. */
export function MovieGrid({ items, hasMore, loading, onMore }: { items: Movie[]; hasMore: boolean; loading: boolean; onMore: () => void }) {
  const sentinel = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = sentinel.current;
    if (!el || !hasMore) return;
    const io = new IntersectionObserver(entries => entries.some(e => e.isIntersecting) && !loading && onMore(), { rootMargin: "600px" });
    io.observe(el);
    return () => io.disconnect();
  }, [hasMore, loading, onMore, items.length]);
  return (
    <>
      <div className="movie-grid">
        {items.map(m => <MovieCard key={m.id} movie={m} />)}
        {loading && Array.from({ length: items.length ? 6 : 18 }, (_, i) => <MovieCardSkeleton key={`s${i}`} />)}
      </div>
      <div ref={sentinel} style={{ height: 1 }} />
    </>
  );
}

export function Empty({ title, hint, seed = 11, children }: { title: string; hint?: string; seed?: number; children?: ReactNode }) {
  return (
    <div className="empty">
      <Halftone className="empty-art" variant="glyph" cell={5} seed={seed} />
      <div className="title">{title}</div>
      {hint && <div className="hint">{hint}</div>}
      {children}
    </div>
  );
}
