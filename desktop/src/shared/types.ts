// Types shared by the main process (data, players) and the window (UI).

export type ContentType = "FILM" | "SERIES" | "MINI" | "SHOW";

export const isSeries = (t: ContentType) => t != "FILM";

/** A title from the catalog. `id` is the Kinopoisk film ID, which is also what the player needs. */
export interface Movie {
  id: number;
  title: string;
  originalTitle?: string | null;
  year?: number | null;
  endYear?: number | null;
  type: ContentType;
  animated: boolean;
  genres: string[];
  countries: string[];
  ratingKp?: number | null;
  votesKp: number;
  ratingImdb?: number | null;
  votesImdb: number;
  duration?: number | null;
  imdbId?: string | null;
  seasons?: number | null;
  episodes?: number | null;
  wiki?: string | null;
  /** MyAnimeList ID (anime only): lets the anime players find the exact release. */
  malId?: number | null;
}

export const ANIME = "Аниме";
export const isAnime = (m: Movie) => m.malId != null || m.genres.includes(ANIME);
export const posterUrl = (id: number) => `https://st.kp.yandex.net/images/film_iphone/iphone360_${id}.jpg`;
export const posterLargeUrl = (id: number) => `https://st.kp.yandex.net/images/film_big/${id}.jpg`;

export function stubMovie(id: number, title = `Кинопоиск #${id}`): Movie {
  return { id, title, type: "FILM", animated: false, genres: [], countries: [], votesKp: 0, votesImdb: 0 };
}

export interface MovieDetails {
  movie: Movie;
  description?: string | null;
  /** Where the description comes from: "Википедии", "Shikimori" (shown as "по материалам …"). */
  descriptionSource?: string | null;
  directors: string[];
  cast: string[];
}

export interface Page {
  items: Movie[];
  page: number;
  hasMore: boolean;
}

export type Kind = "FILMS" | "SERIES" | "ANIME" | "MINI" | "SHOWS" | "ALL";

/**
 * What search keeps: films, shows (TV shows too) or anime. An anime film is both a film and
 * anime, as in the catalog's tabs.
 */
export type SearchKind = "ALL" | "FILMS" | "SERIES" | "ANIME";
export const SEARCH_KINDS: { value: SearchKind; label: string }[] = [
  { value: "ALL", label: "Все" },
  { value: "FILMS", label: "Фильмы" },
  { value: "SERIES", label: "Сериалы" },
  { value: "ANIME", label: "Аниме" },
];

export function matchesKind(m: Movie, kind: SearchKind): boolean {
  return kind == "ALL" || (kind == "FILMS" ? !isSeries(m.type) : kind == "SERIES" ? isSeries(m.type) : isAnime(m));
}
export type Sort = "TREND" | "POPULAR" | "RATING" | "NEW";

export interface CatalogQuery {
  kind: Kind;
  sort: Sort;
  genre?: string | null;
  animated?: boolean | null;
  country?: string | null;
  yearFrom?: number | null;
  yearTo?: number | null;
  minRating?: number | null;
  minVotes?: number;
}

export interface Section {
  key: string;
  title: string;
  query: CatalogQuery;
}

// ---- players -----------------------------------------------------------------------------------

export type StreamFormat = "HLS" | "DASH" | "PROGRESSIVE";

/** One quality of a source that publishes a separate file per quality (AniLiberty, AnimeVost). */
export interface Variant {
  label: string;
  url: string;
}

export interface Subtitle {
  url: string;
  label: string;
  language?: string | null;
}

export type SegmentKind = "INTRO" | "RECAP" | "CREDITS";

/** A part of a video worth skipping. `endMs` null means "until the end". */
export interface Segment {
  kind: SegmentKind;
  startMs: number;
  endMs: number | null;
  source: string;
}

/** A direct video stream, plus what it needs to be fetched. */
export interface Stream {
  url: string;
  format: StreamFormat;
  headers: Record<string, string>;
  /** Voice-over names for the audio renditions, in playlist order (Collaps puts them in the page). */
  audioNames: string[];
  subtitles: Subtitle[];
  variants: Variant[];
  /** Intro / credits marks the source itself publishes (AniLiberty does). */
  segments: Segment[];
  /** The same video in another form, played if this one fails (Collaps: HLS up to 720p behind DASH up to 1080p). */
  fallback?: Stream;
}

export interface Episode {
  season: number;
  number: number;
  title?: string | null;
  stream: Stream;
}

export interface Season {
  number: number;
  episodes: Episode[];
  malId?: number | null;
}

export type Playable =
  | { kind: "single"; sourceName: string; stream: Stream }
  | { kind: "series"; sourceName: string; seasons: Season[]; startSeason?: number | null };

/** A player (balancer) the player site offers for a title. */
export interface SourceInfo {
  name: string;
  iframeUrl: string;
  voices: string[];
}

/** One way to play the title, as listed in the "Источник" menu. */
export interface SourceChoice {
  key: string;
  title: string;
  subtitle: string;
}

export const SOURCE = {
  ANILIBERTY: "aniliberty",
  ANIMEVOST: "animevost",
  COLLAPS: "collaps",
  SITE: "site",
  SITE_PLAYER: "site_player",
} as const;

/** A player page shown over the player area, while the app may still catch its stream. */
export interface SitePage {
  /** One player's page; null for the site itself. */
  embedUrl: string | null;
  name: string;
  /** The app still waits for the page's stream (the viewer may press play there). */
  catching: boolean;
}

/** What the main process found for a title: the choices and, if it could, something to play. */
export interface ResolveResult {
  choices: SourceChoice[];
  /** Direct sources in the order they are tried: the fallback chain after a playback error. */
  order: string[];
  choice: string | null;
  playable: Playable | null;
  /** Nothing to play directly: this page is shown instead (catching goes on if it says so). */
  page?: SitePage | null;
  /** The season and episode the page showed when its stream was caught. */
  hint?: EpisodeHint | null;
  /** The movie with the catalog's IDs filled in (favourites and history keep only a few fields). */
  movie: Movie;
  error?: string | null;
}

/** What an anime is looked up by when the catalog has no MyAnimeList ID for it. */
export interface AnimeTitle {
  title: string;
  original?: string | null;
  year?: number | null;
  series: boolean;
}

/** What is playing, in the terms the intro databases use. */
export interface SkipRequest {
  imdbId?: string | null;
  /** MyAnimeList ID of exactly this season (AniLiberty publishes one per release). */
  malId?: number | null;
  season?: number | null;
  episode?: number | null;
  durationMs: number;
  /** A show: its episode may be unknown (a stream caught from a player page). Default: season given. */
  series?: boolean;
  /** MyAnimeList ID of the show's first season (or of the film), for anime. */
  baseMalId?: number | null;
  /** What to look the anime up by when no MyAnimeList ID is known. */
  anime?: AnimeTitle | null;
  /** The season counted from 0, and the source's episode counts per season (when it numbers them 1, 2, 3…). */
  seasonIndex?: number;
  seasonSizes?: number[];
}

/** The season and episode a player page shows (either may be unknown). */
export interface EpisodeHint {
  season: number | null;
  episode: number | null;
}

// ---- library & settings ------------------------------------------------------------------------

export interface HistoryItem {
  movie: Movie;
  watchedAt: number;
  positionSec: number;
  durationSec: number;
  lastSeason?: number | null;
  lastEpisode?: number | null;
}

export type ThemeMode = "system" | "light" | "dark";

export interface Settings {
  theme: ThemeMode;
  animations: boolean;
  playerHost: string;
  adBlock: boolean;
  autoResume: boolean;
  autoNext: boolean;
  /** Skip intros and recaps on their own, and go to the next episode at the credits. */
  autoSkip: boolean;
  volume: number;
  muted: boolean;
  catalogCheckedAt: number;
}

export interface CatalogInfo {
  version: string | null;
  count: number;
}

export type UpdateResult = { kind: "upToDate" } | { kind: "staged"; version: string } | { kind: "failed"; message: string };

/** The API the preload script gives the window (`window.dpm`). */
export interface DpmApi {
  catalog: {
    page(query: CatalogQuery, page: number, size?: number): Promise<Page>;
    section(key: string, size?: number): Promise<Movie[]>;
    sections(mode: "films" | "series" | "anime"): Promise<Section[]>;
    search(text: string, page: number, kind?: SearchKind): Promise<Page>;
    searchOnline(text: string, exclude: number[], kind?: SearchKind): Promise<Movie[]>;
    movie(id: number): Promise<Movie>;
    details(id: number): Promise<MovieDetails>;
    similar(movie: Movie): Promise<Movie[]>;
    info(): Promise<CatalogInfo>;
    checkUpdate(): Promise<UpdateResult>;
  };
  library: {
    favorites(): Promise<Movie[]>;
    isFavorite(id: number): Promise<boolean>;
    toggleFavorite(movie: Movie): Promise<boolean>;
    history(): Promise<HistoryItem[]>;
    removeHistory(id: number): Promise<void>;
    clearHistory(): Promise<void>;
    markOpened(movie: Movie): Promise<void>;
    saveProgress(movieId: number, item: string, positionMs: number, durationMs: number, season?: number | null, episode?: number | null): Promise<void>;
    position(movieId: number, item: string): Promise<number | null>;
    lastEpisode(movieId: number): Promise<[number, number] | null>;
    /** The voice-over picked last time for a title. */
    voice(movieId: number): Promise<string | null>;
    setVoice(movieId: number, name: string): Promise<void>;
    onChange(listener: () => void): () => void;
  };
  settings: {
    get(): Promise<Settings>;
    set(patch: Partial<Settings>): Promise<Settings>;
    introMark(movieId: number): Promise<[number, number] | null>;
    setIntroMark(movieId: number, mark: [number, number] | null): Promise<void>;
    onChange(listener: (s: Settings) => void): () => void;
  };
  player: {
    resolve(movie: Movie): Promise<ResolveResult>;
    choose(movie: Movie, key: string): Promise<ResolveResult>;
    /** Lets the stream's requests go out with the headers its site expects. */
    prepare(stream: Stream): Promise<Stream>;
    segments(request: SkipRequest): Promise<Segment[]>;
    log(): Promise<string[]>;
    onLog(listener: (line: string) => void): () => void;
    /** The source menu with the site's full list of players (it may arrive after playback starts). */
    choices(movie: Movie): Promise<SourceChoice[]>;
    /** A player page (or the site) over the player area; with page.catching its stream is caught. */
    showSite(movie: Movie, bounds: { x: number; y: number; width: number; height: number }, page?: SitePage | null): Promise<void>;
    /** A stream caught from the shown page, to be played by the app's own player. */
    onCaught(listener: (stream: Stream, name: string, hint: EpisodeHint | null) => void): () => void;
    hideSite(): Promise<void>;
    siteBounds(bounds: { x: number; y: number; width: number; height: number }): Promise<void>;
    siteSeek(seconds: number): Promise<void>;
    onSiteTime(listener: (t: number, d: number) => void): () => void;
    /** The season and episode the shown page displays, whenever they change. */
    onSiteEpisode(listener: (hint: EpisodeHint) => void): () => void;
  };
  app: {
    version(): Promise<string>;
    openExternal(url: string): Promise<void>;
    setFullScreen(on: boolean): Promise<void>;
    isFullScreen(): Promise<boolean>;
    onFullScreen(listener: (on: boolean) => void): () => void;
    onOpenMovie(listener: (id: number) => void): () => void;
  };
}
