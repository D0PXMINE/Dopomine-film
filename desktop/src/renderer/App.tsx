import { useEffect } from "react";
import { api } from "./lib/api";
import { navStore, openDetails, play, usePlaying, useSettings, useView } from "./lib/state";
import { setMotionEnabled, MotionPaused } from "./halftone/motion";
import MainHeader from "./components/MainHeader";
import Sidebar from "./components/Sidebar";
import Toaster from "./components/Toaster";
import Home from "./views/Home";
import Catalog from "./views/Catalog";
import Search from "./views/Search";
import Details from "./views/Details";
import Collection from "./views/Collection";
import { Favorites, History } from "./views/Library";
import SettingsView from "./views/Settings";
import Player from "./player/Player";

export default function App() {
  const view = useView();
  const playing = usePlaying();
  const settings = useSettings();

  useEffect(() => setMotionEnabled(settings?.animations ?? true), [settings?.animations]);

  // tests open the player directly by Kinopoisk ID
  useEffect(() => {
    (window as unknown as { __dpmTestPlay: (id: number) => void }).__dpmTestPlay = id => void api.catalog.movie(id).then(play);
  }, []);

  // Kinopoisk links opened with the app
  useEffect(() => api.app.onOpenMovie(id => {
    navStore.set(s => ({ ...s, playing: null }));
    openDetails(id);
  }), []);

  return (
    <div className="app dpm">
      <Sidebar />
      <main className="main card">
        <MainHeader />
        <div className="main-body">
          <MotionPaused.Provider value={playing != null}>
            <div className="main-scroll scroll" key={JSON.stringify(view)}>
              {view.kind == "home" && <Home />}
              {view.kind == "catalog" && <Catalog />}
              {view.kind == "search" && <Search query={view.query} />}
              {view.kind == "details" && <Details id={view.id} />}
              {view.kind == "collection" && <Collection sectionKey={view.key} title={view.title} />}
              {view.kind == "favorites" && <Favorites />}
              {view.kind == "history" && <History />}
              {view.kind == "settings" && <SettingsView />}
            </div>
          </MotionPaused.Provider>
        </div>
      </main>
      {playing && <Player key={playing.id} movie={playing} />}
      <Toaster />
    </div>
  );
}
