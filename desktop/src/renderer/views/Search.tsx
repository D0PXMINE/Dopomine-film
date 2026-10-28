import { useEffect, useState } from "react";
import type { Movie } from "../../shared/types";
import { api } from "../lib/api";
import { kpIdFrom } from "../lib/format";
import { openDetails } from "../lib/state";
import { Icon } from "../components/icons";
import { Empty, MovieCard } from "../components/Movies";
import { Spinner } from "../components/primitives";

export default function Search({ query }: { query: string }) {
  const [items, setItems] = useState<Movie[] | null>(null);
  const [online, setOnline] = useState<Movie[] | null>(null);
  const [searchingOnline, setSearchingOnline] = useState(false);
  const id = kpIdFrom(query);

  useEffect(() => {
    setOnline(null);
    if (!query.trim()) {
      setItems([]);
      return;
    }
    let alive = true;
    const t = setTimeout(() => void api.catalog.search(query, 1).then(r => alive && setItems(r.items)), 150);
    return () => {
      alive = false;
      clearTimeout(t);
    };
  }, [query]);

  const findOnline = async () => {
    setSearchingOnline(true);
    try {
      setOnline(await api.catalog.searchOnline(query, (items ?? []).map(m => m.id)));
    } finally {
      setSearchingOnline(false);
    }
  };

  if (!query.trim()) {
    return <Empty title="Что посмотрим?" hint="Название по-русски или в оригинале, ссылка на Кинопоиск или его ID." seed={23} />;
  }

  return (
    <div className="search">
      {id != null && (
        <button type="button" className="direct-id" onClick={() => openDetails(id)}>
          <Icon name="link" size={16} />
          <span>Открыть фильм с Кинопоиска <span className="mono">#{id}</span></span>
          <Icon name="chevron-right" size={16} />
        </button>
      )}
      <div className="page-head">
        <div className="titles">
          <div className="caps">Поиск</div>
          <h1 className="page-title">«{query.trim()}»</h1>
        </div>
      </div>
      {items == null ? <Spinner size={18} /> : items.length == 0 ? (
        <div className="muted mono" style={{ padding: "8px 0 16px" }}>В офлайн-каталоге не нашлось.</div>
      ) : (
        <div className="movie-grid">{items.map(m => <MovieCard key={m.id} movie={m} />)}</div>
      )}
      <div className="online-block">
        {online == null ? (
          <button type="button" className="btn" disabled={searchingOnline} onClick={findOnline}>
            {searchingOnline ? <Spinner size={14} /> : <Icon name="search" size={14} />}
            Искать в интернете
          </button>
        ) : online.length == 0 ? (
          <div className="muted mono">В интернете тоже ничего — проверьте название.</div>
        ) : (
          <>
            <div className="caps" style={{ margin: "8px 0 12px" }}>Найдено в Wikidata</div>
            <div className="movie-grid">{online.map(m => <MovieCard key={m.id} movie={m} />)}</div>
          </>
        )}
      </div>
    </div>
  );
}
