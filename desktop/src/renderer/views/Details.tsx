import { useEffect, useState } from "react";
import type { Movie } from "../../shared/types";
import { isSeries } from "../../shared/types";
import { Halftone } from "../halftone/Halftone";
import { api } from "../lib/api";
import { minutes, rating, seasons, episodes, typeLabel, votes } from "../lib/format";
import { navigate, play, useAsync, useLibrary } from "../lib/state";
import { Icon } from "../components/icons";
import { Empty, MovieCard, Poster, Shelf } from "../components/Movies";
import { Spinner } from "../components/primitives";

function RatingBlock({ label, value, count }: { label: string; value?: number | null; count?: number }) {
  return (
    <div className="stat">
      <div className="stat-value">{rating(value)}</div>
      <div className="caps">{label}{votes(count) ? ` · ${votes(count)}` : ""}</div>
    </div>
  );
}

export default function Details({ id }: { id: number }) {
  const { value: d, error, reload } = useAsync(() => api.catalog.details(id), [id]);
  const [base, setBase] = useState<Movie | null>(null);
  const favorite = useLibrary(() => api.library.isFavorite(id), false);
  const similar = useAsync(async () => (d ? api.catalog.similar(d.movie) : []), [d?.movie.id]);

  // the catalog answers instantly; the details (ratings, cast, description) come from the web
  useEffect(() => void api.catalog.movie(id).then(setBase), [id]);
  const m = d?.movie ?? base;

  if (!m) return error ? <Empty title="Не удалось загрузить" hint={error}><button className="btn primary" onClick={reload}>Повторить</button></Empty> : <div className="center-pad"><Spinner size={20} /></div>;

  const facts: [string, string | null][] = [
    ["Год", m.year ? (m.endYear && m.endYear != m.year ? `${m.year}–${m.endYear}` : String(m.year)) : null],
    ["Страна", m.countries.join(", ") || null],
    ["Жанр", m.genres.join(", ").toLowerCase() || null],
    ["Длительность", minutes(m.duration)],
    ["Сезоны", m.seasons ? seasons(m.seasons) : null],
    ["Серии", m.episodes ? episodes(m.episodes) : null],
    ["Режиссёр", d?.directors.join(", ") || null],
  ];

  return (
    <div className="details">
      <div className="details-head">
        <Halftone className="details-art" variant="banner" cell={6} seed={m.id % 89} />
        <Poster id={m.id} title={m.title} className="details-poster" large />
        <div className="details-info">
          <div className="caps">{[typeLabel(m.type), m.year, m.countries[0]].filter(Boolean).join(" · ")}</div>
          <h1 className="details-title">{m.title}</h1>
          {m.originalTitle && <div className="details-original mono">{m.originalTitle}</div>}
          <div className="stats">
            <RatingBlock label="Кинопоиск" value={m.ratingKp} count={m.votesKp} />
            <RatingBlock label="IMDb" value={m.ratingImdb} count={m.votesImdb} />
          </div>
          <div className="details-actions">
            <button type="button" className="btn primary lg" onClick={() => play(m)}>
              <Icon name="play" size={15} filled /> Смотреть
            </button>
            <button type="button" className="btn lg" onClick={() => void api.library.toggleFavorite(m)}>
              <Icon name={favorite ? "bookmark-check" : "bookmark"} size={15} /> {favorite ? "В избранном" : "В избранное"}
            </button>
            <button type="button" className="icon-btn" title="Открыть на Кинопоиске" onClick={() => void api.app.openExternal(`https://www.kinopoisk.ru/${isSeries(m.type) ? "series" : "film"}/${m.id}/`)}>
              <Icon name="external-link" size={16} />
            </button>
          </div>
          {m.genres.length > 0 && (
            <div className="chips-row">
              {m.genres.map(g => <span key={g} className="chip">{g.toLowerCase()}</span>)}
            </div>
          )}
        </div>
      </div>

      <div className="details-body">
        <section className="details-text">
          <div className="caps">Описание</div>
          {d == null ? <div className="skeleton text-block" /> : <p>{d.description ?? "Описания пока нет."}</p>}
          {d?.description && <div className="mono muted small">по материалам {d.descriptionSource ?? "Википедии"}</div>}
          {d && d.cast.length > 0 && (
            <>
              <div className="caps" style={{ marginTop: 20 }}>В ролях</div>
              <p>{d.cast.join(", ")}</p>
            </>
          )}
        </section>
        <section className="facts">
          {facts.filter(f => f[1]).map(([k, v]) => (
            <div key={k} className="fact"><span className="mono muted">{k}</span><span>{v}</span></div>
          ))}
          <div className="fact"><span className="mono muted">Кинопоиск ID</span><span className="mono">{m.id}</span></div>
        </section>
      </div>

      {similar.value && similar.value.length > 0 && (
        <Shelf title="Похожие">
          {similar.value.map(s => <MovieCard key={s.id} movie={s} />)}
        </Shelf>
      )}
      <button type="button" className="link mono small" style={{ margin: "8px 0 24px" }} onClick={() => navigate({ kind: "catalog" })}>Весь каталог →</button>
    </div>
  );
}
