#!/usr/bin/env python3
"""Builds the offline catalog shipped with the app (no API keys needed).

Sources:
  * Wikidata (CC0): every item with a Kinopoisk film ID (P2603) -> Russian / original
    titles, type, year, genres, countries, duration, IMDb ID, seasons, ru.wikipedia article.
  * rating.kinopoisk.ru: Kinopoisk and IMDb ratings with vote counts.

Output: a SQLite file (schema below) that the app copies from its assets.
Everything is cached in --work so an interrupted run resumes where it stopped and a
weekly refresh only re-asks for what is new or stale.

Usage: python3 tools/catalog/build_catalog.py --work .catalog-work --out app/src/main/assets/catalog/catalog.db
"""

import argparse
import concurrent.futures as cf
import csv
import datetime as dt
import gzip
import io
import json
import math
import os
import re
import sqlite3
import sys
import time
import urllib.parse
import urllib.request

UA = "DopomineCatalogBuilder/2.0 (https://github.com/D0PXMINE/Dopomine-film)"
WDQS = "https://query.wikidata.org/sparql"
YEAR = dt.date.today().year

# Genre bits used by the app (must match data/catalog/Genres in Kotlin).
GENRES = [
    ("Боевик", ["боевик", "action", "экшен"]),
    ("Комедия", ["комеди", "comedy", "ситком", "sitcom"]),
    ("Драма", ["драм", "drama"]),
    ("Фантастика", ["фантаст", "science fiction", "sci-fi", "киберпанк", "cyberpunk"]),
    ("Ужасы", ["ужас", "horror", "слэшер", "slasher"]),
    ("Триллер", ["триллер", "thriller"]),
    ("Криминал", ["криминал", "crime", "гангстер", "gangster", "полицейск", "police"]),
    ("Детектив", ["детектив", "mystery", "detective", "нуар", "noir"]),
    ("Приключения", ["приключен", "adventure"]),
    ("Фэнтези", ["фэнтези", "фентези", "fantasy"]),
    ("Мелодрама", ["мелодрам", "романт", "romance", "romantic"]),
    ("Мультфильм", ["мультфильм", "мультсериал", "анимац", "animated", "animation"]),
    ("Аниме", ["аниме", "anime"]),
    ("Семейный", ["семейн", "family"]),
    ("Детский", ["детск", "children"]),
    ("Военный", ["военн", "war film", "war drama", "war "]),
    ("История", ["историческ", "historical", "history", "пеплум", "costume"]),
    ("Биография", ["биограф", "biograph"]),
    ("Документальный", ["документ", "documentary", "docu"]),
    ("Вестерн", ["вестерн", "western"]),
    ("Мюзикл", ["мюзикл", "musical"]),
    ("Музыка", ["музыкальн", "music"]),
    ("Спорт", ["спорт", "sport"]),
    ("Супергерои", ["супергеро", "superhero"]),
]

ANIME_BIT = 1 << [g for g, _ in GENRES].index("Аниме")

TYPE_FILM, TYPE_SERIES, TYPE_MINI, TYPE_SHOW = 0, 1, 2, 3


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def http(url, data=None, headers=None, timeout=90, tries=5):
    h = {"User-Agent": UA}
    if headers:
        h.update(headers)
    last = None
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, data=data, headers=h)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001 - retry anything transient
            last = e
            code = getattr(e, "code", None)
            if code == 404:
                raise
            time.sleep(min(60, 3 * (attempt + 1) ** 2))
    raise last


def sparql(query, fmt="json"):
    accept = "application/sparql-results+json" if fmt == "json" else "text/csv"
    body = urllib.parse.urlencode({"query": query}).encode()
    raw = http(WDQS, data=body, headers={"Accept": accept, "Content-Type": "application/x-www-form-urlencoded"}, timeout=120)
    return json.loads(raw) if fmt == "json" else raw.decode("utf-8")


def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default


def save_json(path, value):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False)
    os.replace(tmp, path)


# ---- 1. candidates ---------------------------------------------------------------------------

def fetch_candidates(work, min_sitelinks):
    log("candidates: all items with a Kinopoisk ID")
    text = sparql("SELECT ?f ?kp ?sl WHERE { ?f wdt:P2603 ?kp ; wikibase:sitelinks ?sl }", fmt="csv")
    rows = list(csv.DictReader(io.StringIO(text)))
    log(f"  {len(rows)} items")
    recent = set()
    log("candidates: recent titles (few sitelinks yet)")
    text = sparql(
        "SELECT DISTINCT ?f WHERE { ?f wdt:P2603 ?kp ; wdt:P577 ?d . FILTER(YEAR(?d) >= %d) }" % (YEAR - 2),
        fmt="csv",
    )
    for r in csv.DictReader(io.StringIO(text)):
        recent.add(r["f"].rsplit("/", 1)[-1])
    log(f"  {len(recent)} recent")
    out = {}
    for r in rows:
        q = r["f"].rsplit("/", 1)[-1]
        sl = int(r["sl"])
        if sl >= min_sitelinks or q in recent:
            if q not in out or sl > out[q]["sl"]:
                out[q] = {"kp": r["kp"], "sl": sl}
    log(f"  {len(out)} candidates")
    return out


# ---- 2. details ------------------------------------------------------------------------------

DETAILS_Q = """
SELECT ?f (SAMPLE(?kp_) AS ?kp) (SAMPLE(?ru_) AS ?ru) (SAMPLE(?en_) AS ?en) (SAMPLE(?ot_) AS ?ot)
  (SAMPLE(?imdb_) AS ?imdb) (MIN(?date_) AS ?date) (MIN(?start_) AS ?start) (MAX(?end_) AS ?end)
  (MAX(?dur_) AS ?dur) (MAX(?seasons_) AS ?seasons) (MAX(?eps_) AS ?eps) (SAMPLE(?wiki_) AS ?wiki)
  (GROUP_CONCAT(DISTINCT STRAFTER(STR(?type_), "entity/"); separator=",") AS ?types)
  (GROUP_CONCAT(DISTINCT STRAFTER(STR(?genre_), "entity/"); separator=",") AS ?genres)
  (GROUP_CONCAT(DISTINCT STRAFTER(STR(?country_), "entity/"); separator=",") AS ?countries)
WHERE {
  VALUES ?f { %s }
  ?f wdt:P2603 ?kp_ .
  OPTIONAL { ?f rdfs:label ?ru_ . FILTER(LANG(?ru_) = "ru") }
  OPTIONAL { ?f rdfs:label ?en_ . FILTER(LANG(?en_) = "en") }
  OPTIONAL { ?f wdt:P1476 ?ot_ }
  OPTIONAL { ?f wdt:P345 ?imdb_ }
  OPTIONAL { ?f wdt:P577 ?date_ }
  OPTIONAL { ?f wdt:P580 ?start_ }
  OPTIONAL { ?f wdt:P582 ?end_ }
  OPTIONAL { ?f wdt:P2047 ?dur_ }
  OPTIONAL { ?f wdt:P2437 ?seasons_ }
  OPTIONAL { ?f wdt:P1113 ?eps_ }
  OPTIONAL { ?art schema:about ?f ; schema:isPartOf <https://ru.wikipedia.org/> ; schema:name ?wiki_ }
  OPTIONAL { ?f wdt:P31 ?type_ }
  OPTIONAL { ?f wdt:P136 ?genre_ }
  OPTIONAL { ?f wdt:P495 ?country_ }
} GROUP BY ?f
"""


def val(b, k):
    v = b.get(k)
    return v["value"] if v else None


def fetch_mal_ids(work):
    """MyAnimeList IDs (P4086): they mark real anime and let the app match anime players exactly."""
    path = os.path.join(work, "mal.json")
    try:
        text = sparql("SELECT ?f ?mal WHERE { ?f wdt:P2603 [] ; wdt:P4086 ?mal }", fmt="csv")
    except Exception as e:  # noqa: BLE001 - keep the last good list
        log(f"mal ids: {e}, using the cached list")
        return load_json(path, {})
    out = {}
    for r in csv.DictReader(io.StringIO(text)):
        q = r["f"].rsplit("/", 1)[-1]
        if r["mal"].isdigit():
            out.setdefault(q, int(r["mal"]))
    log(f"mal ids: {len(out)}")
    save_json(path, out)
    return out


def fetch_details(work, candidates, batch=250, workers=4):
    path = os.path.join(work, "details.json")
    cache = load_json(path, {})
    todo = [q for q in candidates if q not in cache]
    log(f"details: {len(cache)} cached, {len(todo)} to fetch")
    chunks = [todo[i:i + batch] for i in range(0, len(todo), batch)]

    def run(chunk):
        res = sparql(DETAILS_Q % " ".join("wd:" + q for q in chunk))
        out = {}
        for b in res["results"]["bindings"]:
            q = val(b, "f").rsplit("/", 1)[-1]
            out[q] = {k: val(b, k) for k in
                      ("kp", "ru", "en", "ot", "imdb", "date", "start", "end", "dur", "seasons", "eps", "wiki",
                       "types", "genres", "countries")}
        for q in chunk:
            out.setdefault(q, None)
        return out

    done = 0
    with cf.ThreadPoolExecutor(workers) as ex:
        for out in ex.map(run, chunks):
            cache.update(out)
            done += 1
            if done % 20 == 0 or done == len(chunks):
                save_json(path, cache)
                log(f"  details {done}/{len(chunks)} batches")
    save_json(path, cache)
    return cache


def fetch_labels(work, qids):
    path = os.path.join(work, "labels.json")
    cache = load_json(path, {})
    todo = [q for q in qids if q and q not in cache]
    log(f"labels: {len(todo)} to fetch")
    for i in range(0, len(todo), 400):
        chunk = todo[i:i + 400]
        res = sparql(
            "SELECT ?x ?ru ?en WHERE { VALUES ?x { %s } "
            "OPTIONAL { ?x rdfs:label ?ru FILTER(LANG(?ru)='ru') } "
            "OPTIONAL { ?x rdfs:label ?en FILTER(LANG(?en)='en') } }" % " ".join("wd:" + q for q in chunk)
        )
        for b in res["results"]["bindings"]:
            q = val(b, "x").rsplit("/", 1)[-1]
            cache[q] = {"ru": val(b, "ru"), "en": val(b, "en")}
        for q in chunk:
            cache.setdefault(q, {"ru": None, "en": None})
    save_json(path, cache)
    return cache


# ---- 3. ratings ------------------------------------------------------------------------------

RATING_RE_KP = re.compile(r'<kp_rating num_vote="(\d+)"[^>]*>([\d.]+)<')
RATING_RE_IMDB = re.compile(r'<imdb_rating num_vote="(\d+)"[^>]*>([\d.]+)<')


def fetch_rating(kp):
    raw = http(f"https://rating.kinopoisk.ru/{kp}.xml", headers={"User-Agent": "Mozilla/5.0"}, timeout=25, tries=3)
    text = raw.decode("cp1251", "replace")
    m = RATING_RE_KP.search(text)
    n = RATING_RE_IMDB.search(text)
    return [
        float(m.group(2)) if m else 0.0, int(m.group(1)) if m else 0,
        float(n.group(2)) if n else 0.0, int(n.group(1)) if n else 0,
        int(time.time()),
    ]


def fetch_ratings(work, kps, max_age_days, workers=16):
    path = os.path.join(work, "ratings.json")
    cache = load_json(path, {})
    now = time.time()

    def stale(k):
        r = cache.get(k)
        # an empty answer is usually a hiccup (rate limit, cut-off response): ask again next time
        return r is None or now - r[4] > max_age_days * 86400 or (r[1] == 0 and r[3] == 0)

    todo = [k for k in kps if stale(k)]
    log(f"ratings: {len(kps) - len(todo)} fresh, {len(todo)} to fetch")

    def run(k):
        try:
            return k, fetch_rating(k)
        except Exception:  # noqa: BLE001 - missing ratings are fine
            return k, None

    done = 0
    with cf.ThreadPoolExecutor(workers) as ex:
        for k, r in ex.map(run, todo):
            if r:
                cache[k] = r
            done += 1
            if done % 2000 == 0:
                save_json(path, cache)
                log(f"  ratings {done}/{len(todo)}")
    save_json(path, cache)
    return cache


# ---- 4. assemble -----------------------------------------------------------------------------

def year_of(s):
    if not s:
        return None
    m = re.match(r"^-?(\d{4})", s)
    return int(m.group(1)) if m else None


def classify(type_labels):
    """Returns (type, animated, excluded) from the labels of P31 values."""
    text = " | ".join(type_labels).lower()
    if not text:
        return TYPE_FILM, False, False
    exclude = ["episode", "эпизод", "television season", "сезон телесериала", "season of", "film series", "киносерия",
               "серия фильмов", "franchise", "франшиз", "video game", "компьютерная игра", "book", "книга", "novel",
               "роман", "comic", "комикс", "literary work", "literary", "album", "альбом", "song", "песня",
               "human", "человек", "fictional character", "персонаж", "trilogy", "трилогия", "tetralogy", "duology"]
    animated = any(w in text for w in ["anim", "анимац", "мульт", "аниме", "anime", "cartoon"])
    is_series = any(w in text for w in ["series", "сериал", "serial", "anime television", "web series", "веб-сериал"])
    is_mini = any(w in text for w in ["miniseries", "мини-сериал", "limited series"])
    is_show = any(w in text for w in ["television program", "телепередача", "talk show", "ток-шоу", "reality",
                                      "реалити", "game show", "television show", "tv show", "variety show"])
    is_film = any(w in text for w in ["film", "фильм", "movie", "кино"])
    if any(w in text for w in exclude) and not (is_film or is_series or is_show):
        return TYPE_FILM, animated, True
    if is_mini:
        return TYPE_MINI, animated, False
    if is_series:
        return TYPE_SERIES, animated, False
    if is_show and not is_film:
        return TYPE_SHOW, animated, False
    return TYPE_FILM, animated, False


def genre_bits(labels):
    text = " | ".join(labels).lower()
    bits = 0
    for i, (_, keys) in enumerate(GENRES):
        if any(k in text for k in keys):
            bits |= 1 << i
    return bits


def num(v):
    """Wikidata numbers; "unknown value" comes back as a blank node URI."""
    try:
        return int(float(v)) if v else None
    except (TypeError, ValueError):
        return None


# Wikidata labels often copy the Wikipedia article name: "Рай (фильм, 2002)", "Мистер Бин (мультсериал)"
DISAMBIGUATION = re.compile(
    r"\s+\((?:(?:короткометражный |полнометражный |документальный |художественный )?"
    r"(?:фильм|телефильм|кинофильм|мультфильм|телесериал|сериал|мини-сериал|мультсериал|аниме|мюзикл|"
    r"film|tv series|miniseries|animated series)(?:[,\s][^()]*)?|(?:19|20)\d\d)\)$",
    re.IGNORECASE,
)


def clean_title(s):
    if not s:
        return s
    cleaned = DISAMBIGUATION.sub("", s.strip())
    return cleaned or s.strip()


def normalize(s):
    s = (s or "").lower().replace("ё", "е")
    return re.sub(r"[^0-9a-zа-я]+", " ", s).strip()


SCHEMA = """
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE titles (
  kp INTEGER PRIMARY KEY,
  title TEXT NOT NULL,
  original TEXT,
  year INTEGER,
  end_year INTEGER,
  type INTEGER NOT NULL,
  animated INTEGER NOT NULL,
  genres INTEGER NOT NULL,
  countries TEXT,
  kp_rating REAL,
  kp_votes INTEGER NOT NULL,
  imdb_rating REAL,
  imdb_votes INTEGER NOT NULL,
  duration INTEGER,
  imdb TEXT,
  seasons INTEGER,
  episodes INTEGER,
  wiki TEXT,
  popularity REAL NOT NULL,
  trend REAL NOT NULL,
  search TEXT NOT NULL,
  mal INTEGER
);
CREATE INDEX titles_type_popularity ON titles(type, popularity DESC);
CREATE INDEX titles_type_trend ON titles(type, trend DESC);
CREATE INDEX titles_imdb ON titles(imdb);
"""
SCHEMA_VERSION = "2"   # the app refuses catalogs older than the schema it reads

JAPAN = "Q17"


def is_anime(mal, animated, country_ids, labels_text):
    """Wikidata's genres and countries are noisy (Ratatouille lists Japan, Miraculous is tagged
    anime), so anime needs a MyAnimeList ID, Japan as the only country of an animated work, or
    an anime genre together with Japan."""
    if mal:
        return True
    if animated and country_ids and set(country_ids) == {JAPAN}:
        return True
    return ("аниме" in labels_text or "anime" in labels_text) and JAPAN in country_ids


def assemble(out_path, candidates, details, labels, ratings, min_votes, mal_ids):
    rows = []
    skipped = {"no_details": 0, "excluded": 0, "no_title": 0, "few_votes": 0, "future": 0}
    seen = set()
    for q, cand in candidates.items():
        d = details.get(q)
        if not d or not d.get("kp"):
            skipped["no_details"] += 1
            continue
        try:
            kp = int(d["kp"])
        except ValueError:
            continue
        if kp in seen:
            continue
        type_labels = []
        for t in (d.get("types") or "").split(","):
            l = labels.get(t) or {}
            type_labels += [x for x in (l.get("ru"), l.get("en")) if x]
        ttype, animated, excluded = classify(type_labels)
        if excluded:
            skipped["excluded"] += 1
            continue
        # without a Russian label the English one reads better than a native-script original (呪術廻戦)
        title = clean_title(d.get("ru") or d.get("en") or d.get("ot"))
        if not title:
            skipped["no_title"] += 1
            continue
        if title.startswith("Список "):          # "Список серий …": a Wikipedia list, not a title
            skipped["excluded"] += 1
            continue
        # P1476 may hold a working title (Friends -> "Insomnia Cafe"); the English label is safer
        original = clean_title(d.get("en") or d.get("ot"))
        if original and original.strip().lower() == title.strip().lower():
            original = None
        year = year_of(d.get("date")) or year_of(d.get("start"))
        end_year = year_of(d.get("end"))
        r = ratings.get(str(kp)) or [0.0, 0, 0.0, 0, 0]
        kp_rating, kp_votes, imdb_rating, imdb_votes = r[0], r[1], r[2], r[3]
        if year and year > YEAR + 1:
            skipped["future"] += 1
            continue
        genre_labels = []
        for g in (d.get("genres") or "").split(","):
            l = labels.get(g) or {}
            genre_labels += [x for x in (l.get("ru"), l.get("en")) if x]
        country_ids = [c for c in (d.get("countries") or "").split(",") if c]
        mal = mal_ids.get(q)
        anime = is_anime(mal, animated, country_ids, " | ".join(genre_labels + type_labels).lower())
        animated = animated or anime
        # anime has a smaller audience on Kinopoisk: keep it with fewer votes
        need = min_votes // 5 if anime else min_votes
        if kp_votes < need and imdb_votes < need * 5 and cand["sl"] < 8:
            skipped["few_votes"] += 1
            continue
        genres = genre_bits(genre_labels + (["анимация"] if animated else []))
        genres = (genres | ANIME_BIT) if anime else (genres & ~ANIME_BIT)
        countries = []
        for c in (d.get("countries") or "").split(","):
            l = labels.get(c) or {}
            name = l.get("ru") or l.get("en")
            if name and name not in countries:
                countries.append(name)
        dur = num(d.get("dur"))
        if dur is not None and (dur < 1 or dur > 1500):
            dur = None

        votes = kp_votes + imdb_votes * 0.3
        popularity = math.log10(votes + 1) + cand["sl"] / 200.0
        age = max(0, YEAR - (year or 1950))
        trend = math.log10(votes + 1) - 0.35 * math.log2(age + 1)
        seen.add(kp)
        rows.append((
            kp, title, original, year, end_year, ttype, int(animated), genres, ", ".join(countries[:3]) or None,
            round(kp_rating, 2) if kp_rating else None, kp_votes, round(imdb_rating, 1) if imdb_rating else None,
            imdb_votes, dur, d.get("imdb"), num(d.get("seasons")),
            num(d.get("eps")), d.get("wiki"), round(popularity, 4), round(trend, 4),
            " ".join(x for x in {normalize(title), normalize(original), normalize(d.get("en"))} if x),
            mal,
        ))
    log(f"assemble: {len(rows)} titles ({sum(1 for r in rows if r[7] & ANIME_BIT)} anime), skipped {skipped}")

    tmp = out_path + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.executescript(SCHEMA)
    db.executemany("INSERT INTO titles VALUES (" + ",".join("?" * 22) + ")", rows)
    db.executemany("INSERT INTO meta VALUES (?, ?)", [
        ("version", dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M")),
        ("count", str(len(rows))),
        ("schema", SCHEMA_VERSION),
        ("genres", json.dumps([g for g, _ in GENRES], ensure_ascii=False)),
    ])
    db.commit()
    db.execute("VACUUM")
    db.close()
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    os.replace(tmp, out_path)
    log(f"wrote {out_path} ({os.path.getsize(out_path) // 1024} KB)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default=".catalog-work")
    ap.add_argument("--out", default="app/src/main/assets/catalog/catalog.db")
    ap.add_argument("--min-sitelinks", type=int, default=3)
    ap.add_argument("--min-votes", type=int, default=50)
    ap.add_argument("--rating-age-days", type=int, default=21)
    ap.add_argument("--skip-ratings", action="store_true")
    args = ap.parse_args()
    os.makedirs(args.work, exist_ok=True)

    cand_path = os.path.join(args.work, "candidates.json")
    candidates = load_json(cand_path, None)
    if candidates is None or os.environ.get("REFRESH_CANDIDATES"):
        candidates = fetch_candidates(args.work, args.min_sitelinks)
        save_json(cand_path, candidates)

    details = fetch_details(args.work, list(candidates))
    qids = set()
    for d in details.values():
        if d:
            for key in ("types", "genres", "countries"):
                qids.update(x for x in (d.get(key) or "").split(",") if x)
    labels = fetch_labels(args.work, sorted(qids))

    # Rate the most visible titles first (well-linked or recent), so a partial run is still useful.
    def priority(q):
        d = details[q]
        recent = (year_of(d.get("date")) or year_of(d.get("start")) or 0) >= YEAR - 5
        return (0 if recent else 1, -candidates[q]["sl"])

    ordered = sorted((q for q, d in details.items() if d and d.get("kp") and q in candidates), key=priority)
    kps = list(dict.fromkeys(details[q]["kp"] for q in ordered))
    if args.skip_ratings:
        ratings = load_json(os.path.join(args.work, "ratings.json"), {})
    else:
        ratings = fetch_ratings(args.work, kps, args.rating_age_days)
    assemble(args.out, candidates, details, labels, ratings, args.min_votes, fetch_mal_ids(args.work))


if __name__ == "__main__":
    sys.exit(main())
