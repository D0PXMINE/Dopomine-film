import { api } from "../lib/api";
import { clock, meta } from "../lib/format";
import { navigate, openDetails, play, useFavorites, useHistory } from "../lib/state";
import { Icon } from "../components/icons";
import { Empty, MovieCard, Poster } from "../components/Movies";

export function Favorites() {
  const items = useFavorites();
  return (
    <div>
      <div className="page-head">
        <div className="titles">
          <div className="caps">Моё · {items.length}</div>
          <h1 className="page-title">Избранное</h1>
        </div>
      </div>
      {items.length == 0
        ? <Empty title="Пока пусто" hint="Добавляйте фильмы кнопкой «В избранное» на их странице." seed={41}><button className="btn" onClick={() => navigate({ kind: "home" })}>На главную</button></Empty>
        : <div className="movie-grid">{items.map(m => <MovieCard key={m.id} movie={m} />)}</div>}
    </div>
  );
}

export function History() {
  const items = useHistory();
  return (
    <div>
      <div className="page-head">
        <div className="titles">
          <div className="caps">Моё · {items.length}</div>
          <h1 className="page-title">История</h1>
        </div>
        {items.length > 0 && (
          <div className="page-actions">
            <button type="button" className="btn sm" onClick={() => void api.library.clearHistory()}>
              <Icon name="trash" size={14} /> Очистить
            </button>
          </div>
        )}
      </div>
      {items.length == 0 ? <Empty title="Вы ещё ничего не смотрели" hint="Здесь появятся фильмы и сериалы с местом, где вы остановились." seed={43} /> : (
        <div className="list history-list">
          {items.map(h => {
            const progress = h.durationSec > 0 ? Math.min(1, h.positionSec / h.durationSec) : 0;
            return (
              <div key={h.movie.id} className="row history-row" role="button" tabIndex={0} onClick={() => openDetails(h.movie.id)}>
                <Poster id={h.movie.id} className="row-thumb" />
                <div className="text">
                  <div className="title truncate">{h.movie.title}</div>
                  <div className="mono muted truncate">
                    {[meta(h.movie, 1), h.lastSeason != null && h.lastEpisode != null ? `${h.lastSeason} сезон · ${h.lastEpisode} серия` : null, h.positionSec > 0 ? `${clock(h.positionSec)} из ${clock(h.durationSec)}` : null].filter(Boolean).join(" · ")}
                  </div>
                  <div className="progress"><span style={{ width: `${progress * 100}%` }} /></div>
                </div>
                <button type="button" className="btn primary sm" onClick={e => { e.stopPropagation(); play(h.movie); }}>
                  <Icon name="play" size={13} filled /> Продолжить
                </button>
                <button type="button" className="icon-btn sm" title="Убрать из истории" onClick={e => { e.stopPropagation(); void api.library.removeHistory(h.movie.id); }}>
                  <Icon name="x" size={14} />
                </button>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
