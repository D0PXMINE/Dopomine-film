import { useEffect, useState } from "react";
import type { CatalogInfo, ThemeMode } from "../../shared/types";
import { api } from "../lib/api";
import { setSetting, useSettings } from "../lib/state";
import { toast } from "../lib/toast";
import { Icon } from "../components/icons";
import { Mark } from "../components/Logo";
import { Segmented, Spinner, Switch } from "../components/primitives";

function Row({ title, desc, children }: { title: string; desc?: string; children: React.ReactNode }) {
  return (
    <div className="setting-row">
      <div className="text">
        <div className="title">{title}</div>
        {desc && <div className="desc">{desc}</div>}
      </div>
      {children}
    </div>
  );
}

export default function SettingsView() {
  const s = useSettings();
  const [host, setHost] = useState(s?.playerHost ?? "");
  const [info, setInfo] = useState<CatalogInfo | null>(null);
  const [version, setVersion] = useState("");
  const [checking, setChecking] = useState(false);

  useEffect(() => {
    void api.catalog.info().then(setInfo);
    void api.app.version().then(setVersion);
  }, []);
  useEffect(() => setHost(s?.playerHost ?? ""), [s?.playerHost]);
  if (!s) return null;

  const check = async () => {
    setChecking(true);
    const r = await api.catalog.checkUpdate();
    setChecking(false);
    if (r.kind == "staged") toast.success("Каталог обновлён", `Версия ${r.version} подключится при следующем запуске`);
    else if (r.kind == "upToDate") toast.info("Каталог свежий");
    else toast.error("Не удалось обновить", r.message);
  };

  return (
    <div className="settings-page">
      <div className="page-head">
        <div className="titles">
          <h1 className="page-title">Настройки</h1>
        </div>
      </div>

      <div className="settings-section">
        <div className="caps">Оформление</div>
        <Row title="Тема">
          <Segmented<ThemeMode> value={s.theme} onChange={theme => void setSetting({ theme })} label="Тема"
            options={[{ value: "system", label: "Система" }, { value: "light", label: "Светлая" }, { value: "dark", label: "Тёмная" }]} />
        </Row>
        <Row title="Анимация точек" desc="Живые узоры на баннерах и пустых экранах">
          <Switch checked={s.animations} onChange={animations => void setSetting({ animations })} label="Анимация точек" />
        </Row>
      </div>

      <div className="settings-section">
        <div className="caps">Плеер</div>
        <Row title="Пропускать заставки" desc="Заставки и пересказы — сразу, на титрах — следующая серия. Кнопка «Пропустить» есть всегда">
          <Switch checked={s.autoSkip} onChange={autoSkip => void setSetting({ autoSkip })} label="Пропускать заставки" />
        </Row>
        <Row title="Следующая серия" desc="Включать следующую серию автоматически">
          <Switch checked={s.autoNext} onChange={autoNext => void setSetting({ autoNext })} label="Следующая серия" />
        </Row>
        <Row title="Продолжать с места остановки" desc="Запоминать позицию для каждого фильма и серии">
          <Switch checked={s.autoResume} onChange={autoResume => void setSetting({ autoResume })} label="Продолжать с места остановки" />
        </Row>
        <Row title="Блокировать рекламу" desc="Фильтр рекламы, всплывающих окон и переходов на чужие сайты в плеере сайта">
          <Switch checked={s.adBlock} onChange={adBlock => void setSetting({ adBlock })} label="Блокировать рекламу" />
        </Row>
        <Row title="Адрес плеера" desc="Сайт из скрипта; если он переедет на другое зеркало, укажите новый адрес">
          <label className="field" style={{ width: 280 }}>
            <input value={host} spellCheck={false} onChange={e => setHost(e.target.value)} onBlur={() => void setSetting({ playerHost: host })} onKeyDown={e => e.key == "Enter" && void setSetting({ playerHost: host })} />
          </label>
        </Row>
      </div>

      <div className="settings-section">
        <div className="caps">Каталог</div>
        <Row title={info ? `${info.count.toLocaleString("ru")} фильмов и сериалов` : "Каталог"} desc={info?.version ? `Снимок от ${info.version.slice(6, 8)}.${info.version.slice(4, 6)}.${info.version.slice(0, 4)} · Wikidata и рейтинги Кинопоиска` : undefined}>
          <button type="button" className="btn" disabled={checking} onClick={check}>
            {checking ? <Spinner size={14} /> : <Icon name="refresh-cw" size={14} />} Проверить обновление
          </button>
        </Row>
        <Row title="История просмотров" desc="Позиции и список «Продолжить»">
          <button type="button" className="btn" onClick={() => void api.library.clearHistory().then(() => toast.info("История очищена"))}>
            <Icon name="trash" size={14} /> Очистить
          </button>
        </Row>
      </div>

      <div className="about">
        <Mark size={28} />
        <div>
          <div className="title">dopamine {version}</div>
          <div className="mono muted small">
            Created by <button type="button" className="link" onClick={() => void api.app.openExternal("https://t.me/d0paminx")}>dopamine</button>
          </div>
        </div>
      </div>
    </div>
  );
}
