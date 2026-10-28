import type { View } from "../lib/state";
import { navigate, play, useFavorites, useHistory, useView } from "../lib/state";
import { Icon, IconName } from "./icons";
import Logo from "./Logo";
import { Poster } from "./Movies";

const same = (a: View, b: View) => a.kind == b.kind;

export default function Sidebar() {
  const view = useView();
  const favorites = useFavorites();
  const history = useHistory();

  const item = (target: View, icon: IconName, label: string, count?: number) => (
    <button type="button" className={`nav-item ${same(view, target) ? "active" : ""}`} aria-current={same(view, target) ? "page" : undefined} onClick={() => navigate(target)}>
      <Icon name={icon} size={16} />
      <span className="name">{label}</span>
      {count != null && count > 0 && <span className="count">{count}</span>}
    </button>
  );

  return (
    <aside className="sidebar">
      <div className="sidebar-logo">
        <Logo size={22} mark={30} gap={12} />
      </div>

      <nav className="sidebar-scroll scroll">
        <div className="nav-section">
          <div className="nav-section-head"><span className="caps">Смотреть</span></div>
          {item({ kind: "home" }, "house", "Главная")}
          {item({ kind: "catalog" }, "layout-grid", "Каталог")}
          {item({ kind: "search", query: "" }, "search", "Поиск")}
        </div>

        <div className="nav-section">
          <div className="nav-section-head"><span className="caps">Моё</span></div>
          {item({ kind: "favorites" }, "bookmark", "Избранное", favorites.length)}
          {item({ kind: "history" }, "history", "История", history.length)}
        </div>

        {history.length > 0 && (
          <div className="nav-section">
            <div className="nav-section-head"><span className="caps">Продолжить</span></div>
            {history.slice(0, 6).map(h => (
              <button key={h.movie.id} type="button" className="nav-item kit" title={h.movie.title} onClick={() => play(h.movie)}>
                <Poster id={h.movie.id} className="kit-thumb" />
                <span className="name">{h.movie.title}</span>
                {h.durationSec > 0 && <span className="count">{Math.round((100 * h.positionSec) / h.durationSec)}%</span>}
              </button>
            ))}
          </div>
        )}
      </nav>

      <div className="sidebar-foot">
        {item({ kind: "settings" }, "settings-2", "Настройки")}
      </div>
    </aside>
  );
}
