import { useCallback, useEffect, useRef, useState } from "react";
import type { CatalogQuery, Kind, Movie, Sort } from "../../shared/types";
import { api } from "../lib/api";
import { createStore, useStore } from "../lib/store";
import { Icon } from "../components/icons";
import { MovieGrid } from "../components/Movies";
import { Menu, MenuItem, Popover, usePopover } from "../components/primitives";

const KINDS: { key: Kind; label: string }[] = [
  { key: "FILMS", label: "Фильмы" }, { key: "SERIES", label: "Сериалы" }, { key: "ANIME", label: "Аниме" },
  { key: "MINI", label: "Мини-сериалы" }, { key: "SHOWS", label: "ТВ-шоу" },
];
const SORTS: { key: Sort; label: string }[] = [
  { key: "TREND", label: "Популярные сейчас" }, { key: "POPULAR", label: "Самые известные" },
  { key: "RATING", label: "По рейтингу" }, { key: "NEW", label: "Новые" },
];
const GENRES = ["Боевик", "Комедия", "Драма", "Фантастика", "Ужасы", "Триллер", "Криминал", "Детектив", "Приключения", "Фэнтези",
  "Мелодрама", "Мультфильм", "Аниме", "Семейный", "Детский", "Военный", "История", "Биография", "Документальный", "Вестерн", "Мюзикл", "Спорт", "Супергерои"];

function years() {
  const y = new Date().getFullYear();
  return [
    { label: "Все годы", from: null, to: null },
    { label: `${y - 1}–${y}`, from: y - 1, to: y },
    { label: `2020–${y - 2}`, from: 2020, to: y - 2 },
    { label: "2010-е", from: 2010, to: 2019 },
    { label: "2000-е", from: 2000, to: 2009 },
    { label: "1990-е", from: 1990, to: 1999 },
    { label: "1980-е", from: 1980, to: 1989 },
    { label: "До 1980", from: 1900, to: 1979 },
  ];
}
const RATINGS = [null, 6, 7, 7.5, 8];

const queryStore = createStore<CatalogQuery>({ kind: "FILMS", sort: "TREND", minVotes: 300 });

function Picker<T>({ icon, label, options, value, onPick }: { icon: Parameters<typeof Icon>[0]["name"]; label: string; options: { label: string; value: T }[]; value: T; onPick: (v: T) => void }) {
  const pop = usePopover();
  return (
    <>
      <button type="button" ref={pop.anchor} className="btn trigger" onClick={pop.toggle}>
        <Icon name={icon} size={14} />
        {label}
        <Icon name="chevron-down" size={14} />
      </button>
      <Popover anchor={pop.anchor} open={pop.open} onClose={pop.close} placement="bottom-start">
        <Menu label={label}>
          {options.map(o => (
            <MenuItem key={o.label} checked={JSON.stringify(o.value) == JSON.stringify(value)} onSelect={() => { onPick(o.value); pop.close(); }}>{o.label}</MenuItem>
          ))}
        </Menu>
      </Popover>
    </>
  );
}

export default function Catalog() {
  const q = useStore(queryStore);
  const [items, setItems] = useState<Movie[]>([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(false);
  const token = useRef(0);
  const update = (patch: Partial<CatalogQuery>) => queryStore.set(prev => ({ ...prev, ...patch }));

  const load = useCallback((next: number, reset: boolean) => {
    const t = ++token.current;
    setLoading(true);
    void api.catalog.page(q, next, 30).then(r => {
      if (t != token.current) return;
      setItems(prev => (reset ? r.items : [...prev, ...r.items.filter(m => !prev.some(p => p.id == m.id))]));
      setPage(next);
      setHasMore(r.hasMore);
      setLoading(false);
    });
  }, [q]);

  useEffect(() => {
    setItems([]);
    setHasMore(true);
    load(1, true);
  }, [load]);

  const yearOptions = years();
  const year = yearOptions.find(y => y.from == (q.yearFrom ?? null) && y.to == (q.yearTo ?? null)) ?? yearOptions[0];

  return (
    <div className="catalog">
      <div className="page-head">
        <div className="titles">
          <h1 className="page-title">Каталог</h1>
          <p className="page-subtitle">Все фильмы, сериалы и аниме офлайн-каталога, с фильтрами.</p>
        </div>
      </div>
      <div className="filter-row">
        <div className="segmented">
          {KINDS.map(k => <button key={k.key} type="button" aria-pressed={q.kind == k.key} onClick={() => update({ kind: k.key })}>{k.label}</button>)}
        </div>
        <Picker icon="arrow-up-down" label={SORTS.find(s => s.key == q.sort)!.label} value={q.sort} options={SORTS.map(s => ({ label: s.label, value: s.key }))} onPick={sort => update({ sort })} />
        <Picker icon="clock" label={year.label} value={[year.from, year.to]} options={yearOptions.map(y => ({ label: y.label, value: [y.from, y.to] }))} onPick={([from, to]) => update({ yearFrom: from, yearTo: to })} />
        <Picker icon="star" label={q.minRating ? `от ${q.minRating}` : "Любой рейтинг"} value={q.minRating ?? null} options={RATINGS.map(r => ({ label: r ? `Рейтинг от ${r}` : "Любой рейтинг", value: r }))} onPick={minRating => update({ minRating })} />
      </div>
      <div className="chips-row">
        <button type="button" className={`chip ${!q.genre ? "on" : ""}`} onClick={() => update({ genre: null })}>все жанры</button>
        {GENRES.map(g => <button key={g} type="button" className={`chip ${q.genre == g ? "on" : ""}`} onClick={() => update({ genre: q.genre == g ? null : g })}>{g.toLowerCase()}</button>)}
      </div>
      <MovieGrid items={items} hasMore={hasMore} loading={loading} onMore={() => load(page + 1, false)} />
      {!loading && items.length == 0 && <div className="muted mono" style={{ padding: 24 }}>Ничего не нашлось — попробуйте другие фильтры.</div>}
    </div>
  );
}
