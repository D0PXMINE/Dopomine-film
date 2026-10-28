import { useCallback, useEffect, useRef, useState } from "react";
import type { Movie, Section } from "../../shared/types";
import { api } from "../lib/api";
import { MovieGrid } from "../components/Movies";

/** "Все" of a home shelf: the same query, as a full grid. */
export default function Collection({ sectionKey, title }: { sectionKey: string; title: string }) {
  const [section, setSection] = useState<Section | null>(null);
  const [items, setItems] = useState<Movie[]>([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(false);
  const busy = useRef(false);

  useEffect(() => {
    void Promise.all([api.catalog.sections("films"), api.catalog.sections("series"), api.catalog.sections("anime")])
      .then(all => setSection(all.flat().find(s => s.key == sectionKey) ?? null));
  }, [sectionKey]);

  const more = useCallback(() => {
    if (!section || busy.current) return;
    busy.current = true;
    setLoading(true);
    void api.catalog.page(section.query, page + 1, 30).then(r => {
      setItems(prev => [...prev, ...r.items.filter(m => !prev.some(p => p.id == m.id))]);
      setPage(page + 1);
      setHasMore(r.hasMore);
      setLoading(false);
      busy.current = false;
    });
  }, [section, page]);

  useEffect(() => {
    if (section && page == 0) more();
  }, [section, page, more]);

  return (
    <div>
      <div className="page-head">
        <div className="titles">
          <div className="caps">Подборка</div>
          <h1 className="page-title">{title}</h1>
        </div>
      </div>
      <MovieGrid items={items} hasMore={hasMore} loading={loading} onMore={more} />
    </div>
  );
}
