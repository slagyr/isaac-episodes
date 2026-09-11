(ns isaac.recall.query
  "Hybrid retrieval over a crew's packed index."
  (:require
    [clojure.string :as str]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.recall.embedding :as embedding]
    [isaac.recall.index :as index]
    [isaac.recall.score :as score]
    [isaac.tool.memory :as memory])
  (:import
    (java.time Instant Period ZoneOffset)))

(defn- parse-instant [ts]
  (cond
    (instance? Instant ts) ts
    (string? ts)
    (try
      (let [normalized (if (re-find #"[zZ]|[+-]\d{2}:?\d{2}$" ts) ts (str ts "Z"))]
        (Instant/parse normalized))
      (catch Exception _
        (try
          (-> (java.time.LocalDateTime/parse
                (subs ts 0 (min (count ts) 19))
                (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))
              (.toInstant java.time.ZoneOffset/UTC))
          (catch Exception _
            nil))))
    :else nil))

(defn- now-instant [now]
  (or (parse-instant now)
      (when-let [n memory/*now*]
        (if (instance? Instant n) n (parse-instant (str n))))
      (Instant/now)))

(defn- age-days
  "Calendar age in 30-day months so a two-month gap is exactly 60 days
   (the recency checkpoint: 0.5^(60/30) = 0.25)."
  [ended-at now]
  (let [end (parse-instant ended-at)
        n   (now-instant now)]
    (if (and end n)
      (let [a (.toLocalDate (.atOffset end ZoneOffset/UTC))
            b (.toLocalDate (.atOffset n ZoneOffset/UTC))
            p (Period/between a b)]
        (double (+ (* 30 (.getYears p) 12)
                   (* 30 (.getMonths p))
                   (.getDays p))))
      0.0)))

(defn- configured-model [cfg]
  (or (get-in cfg [:embedding :model]) ""))

(defn- group-stale [rows model]
  (let [stale (remove #(= model (:model %)) rows)
        by-model (frequencies (map :model stale))]
    by-model))

(defn- scene-lookup [fs* root crew]
  (into {}
        (for [ep (store/list-episodes fs* root crew)
              scene (store/list-scenes fs* root crew (:id ep))]
          [(:id scene) (assoc scene :episode-id (:id ep))])))

(defn- rows-by-scene [rows model]
  (->> rows
       (filter #(= model (:model %)))
       (group-by :scene-id)))

(defn heap-delta
  "Used-heap growth across a read, floored at 0: a GC during the read makes
   the raw after-before delta negative, which is noise, not a measurement."
  [before after]
  (max 0 (- after before)))

(defn query
  "Rank indexed scenes for `query-text`.

   opts:
     :now        Instant or ISO string (defaults to memory/*now* / clock)
     :weights    CLI flag overrides {:text :gist :lex :recency}
     :half-life  days (default 30)
     :top        max hits (default all)
     :floor-cos  cosine match-floor override (nil = resolve from config / default 0.47)

   Returns {:hits [...] :model ... :warning ...} or {:error ... :message ...}."
  [fs* root crew query-text cfg {:keys [now weights half-life top] :as opts}]
  (let [path (index/index-path root crew)]
    (if-not (fs/exists? fs* path)
      {:error   :no-index
       :message (str "no index for crew " crew " — run isaac episodes index")}
      (let [heap-before (let [rt (Runtime/getRuntime)]
                          (- (.totalMemory rt) (.freeMemory rt)))
            t-index (System/nanoTime)
            rows  (index/read-index fs* root crew)
            index-ms (quot (- (System/nanoTime) t-index) 1000000)
            heap-index (heap-delta heap-before
                                   (let [rt (Runtime/getRuntime)]
                                     (- (.totalMemory rt) (.freeMemory rt))))
            model (configured-model cfg)
            matching (filter #(= model (:model %)) rows)]
        (if (and (seq rows) (empty? matching))
          {:error   :no-rows
           :message (str "no rows for model " model " — run isaac episodes index")}
          (let [t-embed (System/nanoTime)
                embed   (embedding/embed-texts cfg [query-text])
                qvec    (when-let [raw (first (:vectors embed))]
                          (score/normalize-vector raw))
                embed-ms (quot (- (System/nanoTime) t-embed) 1000000)
                w       (score/resolve-weights cfg (or weights {}))
                floor*  (score/resolve-floor cfg (select-keys opts [:floor-cos]))
                hl      (or half-life (get-in cfg [:recall :half-life]) 30.0)
                t-scenes (System/nanoTime)
                scenes  (scene-lookup fs* root crew)
                scenes-ms (quot (- (System/nanoTime) t-scenes) 1000000)
                t-score (System/nanoTime)
                grouped (rows-by-scene rows model)
                stale   (group-stale rows model)
                stale-warning (when (seq stale)
                                (let [parts (map (fn [[m n]] (str n " stale rows (" m ")")) stale)]
                                  (str (str/join ", " parts) " — run isaac episodes index --rebuild")))
                q-terms (score/tokenize query-text)
                tokensets (into {}
                                (map (fn [[sid s]]
                                       [sid (score/token-set (str (:gist s) " " (:text s)))]))
                                scenes)
                df      (score/document-frequency (vals tokensets) q-terms)
                n-scenes (count scenes)
                hits
                (for [[scene-id scene] scenes
                      :let [kind-rows (get grouped scene-id)
                            by-kind (into {} (map (juxt :kind identity) kind-rows))
                            text-row (get by-kind :text)
                            gist-row (get by-kind :gist)
                            text-cos (if (and qvec (:vector text-row))
                                       (score/cosine qvec (:vector text-row))
                                       0.0)
                            gist-cos (if (and qvec (:vector gist-row))
                                       (score/cosine qvec (:vector gist-row))
                                       0.0)
                            lex-hay  (or (get tokensets scene-id)
                                         (str (:gist scene) " " (:text scene)))
                            lex      (score/lexical query-text lex-hay {:df df :n n-scenes})
                            terms    (score/matched-terms query-text lex-hay)
                            rec      (score/recency (age-days (:ended-at scene) now) hl)
                            blended  (score/blend {:text text-cos :gist gist-cos
                                                   :lex lex :rec rec}
                                                  w)]
                      :when (or (seq kind-rows) (pos? (double lex)))]
                  {:scene-id   scene-id
                   :episode-id (or (:episode-id scene) (:episode-id text-row) (:episode-id gist-row))
                   :score      blended
                   :text       text-cos
                   :gist       gist-cos
                   :lex        lex
                   :rec        rec
                   :terms      terms
                   :gist-text  (:gist scene)})
                ranked (->> hits
                            (sort-by (juxt (comp - :score) :scene-id))
                            vec)
                top-hit (first ranked)
                best-cos (when (seq ranked)
                           (apply max (map (fn [h] (max (double (or (:text h) 0.0))
                                                        (double (or (:gist h) 0.0))))
                                           ranked)))
                floor-warning (when (and top-hit
                                         (not (score/match? {:best-cos (or best-cos 0.0)
                                                             :lex (:lex top-hit)}
                                                            floor*)))
                                (format "weak matches — nothing stands out (best cos %.2f)" (or best-cos 0.0)))
                warning (str/join "\n" (remove str/blank? [stale-warning floor-warning]))
                warning (when-not (str/blank? warning) warning)
                scene-count (count ranked)
                ranked (if top (vec (take (long top) ranked)) ranked)
                score-ms (quot (- (System/nanoTime) t-score) 1000000)
                vec-bytes (or (try (fs/size fs* (index/vectors-path root crew))
                                   (catch Exception _ nil))
                              0)
                meta-bytes (or (try (fs/size fs* path)
                                    (catch Exception _ nil))
                               0)]
            (cond-> {:hits ranked :model model :scene-count scene-count
                     :timings {:index-ms index-ms
                               :scenes-ms scenes-ms
                               :embed-ms embed-ms
                               :score-ms score-ms}
                     :index-stats {:rows (count rows)
                                   :file-bytes (+ vec-bytes meta-bytes)
                                   :heap-bytes heap-index}}
              warning (assoc :warning warning))))))))
