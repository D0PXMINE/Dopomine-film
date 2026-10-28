import { useEffect, useState } from "react";
import type { Movie, Section } from "../../shared/types";
import { Halftone } from "../halftone/Halftone";
import { api } from "../lib/api";
import { navigate, useHistory } from "../lib/state";
import { createStore, useStore } from "../lib/store";
import { ContinueCard, MovieCard, MovieCardSkeleton, Shelf } from "../components/Movies";

type Mode = "films" | "series" | "anime";
const MODES: { key: Mode; label: string; caps: string; title: string; text: string; seed: number }[] = [
  { key: "films", label: "Фильмы", caps: "фильмы", title: "Популярное сейчас", text: "Каталог Кинопоиска с рейтингами КП и IMDb. Свой плеер без рекламы.", seed: 7 },
  { key: "series", label: "Сериалы", caps: "сериалы", title: "Сериалы недели", text: "Все сезоны и серии, озвучки на выбор, пропуск заставок.", seed: 19 },
  { key: "anime", label: "Аниме", caps: "аниме", title: "Аниме", text: "Озвучки AniLibria и AnimeVost, все сезоны, до 1080p. Без рекламы.", seed: 31 },
];

/** Kept between visits, like the tab you left the home screen on. */
const modeStore = createStore<Mode>("films");
const shelfCache = new Map<string, Movie[]>();

function useSection(section: Section) {
  const [items, setItems] = useState<Movie[] | null>(shelfCache.get(section.key) ?? null);
  useEffect(() => {
    if (shelfCache.has(section.key)) return;
    let alive = true;
    void api.catalog.section(section.key, 24).then(list => {
      shelfCache.set(section.key, list);
      if (alive) setItems(list);
    });
    return () => {
      alive = false;
    };
  }, [section.key]);
  return items;
}

function SectionShelf({ section, large }: { section: Section; large?: boolean }) {
  const items = useSection(section);
  return (
    <Shelf title={section.title} onAll={() => navigate({ kind: "collection", key: section.key, title: section.title })} empty={items != null && items.length == 0}>
      {items == null
        ? Array.from({ length: 8 }, (_, i) => <MovieCardSkeleton key={i} size={large ? "lg" : "md"} />)
        : items.map(m => <MovieCard key={m.id} movie={m} size={large ? "lg" : "md"} watch={large} />)}
    </Shelf>
  );
}

export default function Home() {
  const mode = useStore(modeStore);
  const history = useHistory();
  const [sections, setSections] = useState<Section[]>([]);
  const info = MODES.find(m => m.key == mode)!;

  useEffect(() => {
    let alive = true;
    void api.catalog.sections(mode).then(s => alive && setSections(s));
    return () => {
      alive = false;
    };
  }, [mode]);

  return (
    <div className="home">
      <div className="hero">
        <Halftone className="hero-art" variant="banner" cell={6} seed={info.seed} />
        <div className="titles">
          <div className="caps" style={{ marginBottom: 10 }}>{info.caps} · каталог кинопоиска</div>
          <h1 className="page-title">{info.title}</h1>
          <p className="page-subtitle">{info.text}</p>
          <div className="segmented hero-tabs" role="tablist">
            {MODES.map(m => (
              <button key={m.key} type="button" role="tab" aria-selected={m.key == mode} aria-pressed={m.key == mode} onClick={() => modeStore.set(m.key)}>
                {m.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {sections[0] && <SectionShelf key={sections[0].key} section={sections[0]} large />}

      {history.length > 0 && (
        <Shelf title="Продолжить просмотр" onAll={() => navigate({ kind: "history" })}>
          {history.slice(0, 15).map(h => <ContinueCard key={h.movie.id} item={h} />)}
        </Shelf>
      )}

      {sections.slice(1).map(s => <SectionShelf key={s.key} section={s} />)}
    </div>
  );
}
