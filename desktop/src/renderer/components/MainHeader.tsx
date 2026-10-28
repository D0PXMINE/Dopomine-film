import { useEffect, useRef, useState } from "react";
import { canGoBack, goBack, navigate, navStore, useView } from "../lib/state";
import { Icon } from "./icons";

/** Back button and the search field, on every screen. */
export default function MainHeader() {
  const view = useView();
  const input = useRef<HTMLInputElement | null>(null);
  const [text, setText] = useState(view.kind == "search" ? view.query : "");

  useEffect(() => {
    if (view.kind == "search") {
      setText(view.query);
      input.current?.focus();
    }
  }, [view]);

  // Ctrl+F or / jumps to search
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (navStore.get().playing) return;
      const typing = (e.target as HTMLElement)?.tagName == "INPUT";
      if (((e.ctrlKey || e.metaKey) && e.key.toLowerCase() == "f") || (e.key == "/" && !typing)) {
        e.preventDefault();
        input.current?.focus();
        input.current?.select();
      }
      if (e.key == "Backspace" && e.altKey) goBack();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const change = (value: string) => {
    setText(value);
    navigate({ kind: "search", query: value });
  };

  return (
    <div className="main-head">
      <div className="search-row">
        {canGoBack() && (
          <button type="button" className="icon-btn" aria-label="Назад" title="Назад" onClick={goBack}>
            <Icon name="arrow-left" size={18} />
          </button>
        )}
        <label className="field lg">
          <Icon name="search" size={16} />
          <input
            ref={input}
            type="text"
            value={text}
            placeholder="Фильм, сериал, аниме, ссылка или ID Кинопоиска"
            aria-label="Поиск"
            spellCheck={false}
            onChange={e => change(e.target.value)}
            onFocus={() => view.kind != "search" && text && change(text)}
            onKeyDown={e => {
              if (e.key == "Escape") {
                if (text) change("");
                else input.current?.blur();
              }
            }}
          />
          {text.length > 0 && (
            <button type="button" className="icon-btn sm" style={{ marginRight: -8 }} aria-label="Очистить" onClick={() => change("")}>
              <Icon name="x" size={14} />
            </button>
          )}
        </label>
      </div>
    </div>
  );
}
