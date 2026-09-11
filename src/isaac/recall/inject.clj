(ns isaac.recall.inject
  "Recall-at-open and lineage-seed injection for episode crews."
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.recall.index :as index]
    [isaac.recall.query :as query]
    [isaac.recall.score :as score]
    [isaac.session.store.spi :as session-store]
    [isaac.tool.memory :as memory]))

(def SEARCH_HEADER
  "Recalled from earlier conversations (fetch full detail with recall__scene <id>):")

(def LINEAGE_HEADER
  "Previously in this conversation (fetch full detail with recall__scene <id>):")

(def DEFAULT_INJECT {:full 1 :gists 2})
(def LINEAGE_CAP 10)

(defn scene-date [scene]
  (let [ts (str (or (:started-at scene) (:ended-at scene) ""))]
    (if (>= (count ts) 10) (subs ts 0 10) ts)))

(defn format-line [scene]
  (str "- [" (:id scene) " · " (scene-date scene) "] " (or (:gist scene) "")))

(defn- inject-cfg [cfg]
  (merge DEFAULT_INJECT (get-in cfg [:recall :inject] {})))

(defn render-search-block
  "Tiered search block: first :full hits include distilled text; next :gists are gist-only."
  [scenes inject]
  (let [full-n  (long (or (:full inject) 1))
        gist-n  (long (or (:gists inject) 2))
        full    (vec (take full-n scenes))
        gists   (vec (take gist-n (drop full-n scenes)))
        lines   (concat
                  (map (fn [s] (str (format-line s) "\n" (or (:text s) ""))) full)
                  (map format-line gists))]
    (when (seq lines)
      (str/join "\n" (cons SEARCH_HEADER lines)))))

(defn render-lineage-block [scenes]
  (when (seq scenes)
    (str/join "\n" (cons LINEAGE_HEADER (map format-line (take LINEAGE_CAP scenes))))))

(defn passing-hits [hits floor]
  (filterv (fn [h]
             (score/match? {:best-cos (max (double (or (:text h) 0.0))
                                           (double (or (:gist h) 0.0)))
                            :lex      (:lex h)}
                           floor))
           (or hits [])))

(defn- now-iso []
  (str (or (memory/now) (java.time.Instant/now))))

(defn record-refs!
  "Append :recalled-scenes refs on the episode, deduping by scene-id."
  [fs* root crew episode-id scenes query]
  (when-let [ep (store/read-episode fs* root crew episode-id)]
    (let [existing (vec (or (:recalled-scenes ep) []))
          have     (set (map :scene-id existing))
          fresh    (for [s scenes
                         :let [sid (or (:id s) (:scene-id s))]
                         :when (and sid (not (contains? have sid)))]
                     (cond-> {:scene-id        sid
                              :origin-episode  (or (:origin-episode s) (:episode-id s) episode-id)
                              :recalled-at     (now-iso)}
                       query (assoc :query query)))
          merged   (into existing fresh)]
      (when (seq fresh)
        (store/write-episode! fs* root (assoc ep :recalled-scenes merged)
                              (store/list-scenes fs* root crew episode-id)))
      merged)))

(defn- scene-from-hit [fs* root crew hit]
  (or (when-let [eid (:episode-id hit)]
        (some-> (store/read-scene fs* root crew eid (:scene-id hit))
                (assoc :origin-episode eid :episode-id eid)))
      {:id            (:scene-id hit)
       :scene-id      (:scene-id hit)
       :origin-episode (:episode-id hit)
       :episode-id    (:episode-id hit)
       :gist          (:gist-text hit)}))

(defn- search-result [fs* root crew query cfg]
  (try
    (query/query fs* root crew query cfg {:top 8})
    (catch Exception e
      (log/warn :recall/skipped :reason :embed-failed :error (.getMessage e))
      {:error :embed-failed :message (.getMessage e)})))

(defn- passing-search-hits [result floor exclude-ids]
  (if (or (nil? result) (:error result))
    (do
      (when (and (:error result)
                 (not= :no-index (:error result))
                 (not= :no-embedding (:error result))
                 (not= :no-rows (:error result)))
        (log/warn :recall/skipped :reason (:error result) :message (:message result)))
      [])
    (->> (passing-hits (:hits result) floor)
         ;; Grover's 4-d stub vectors saturate near 0.999, so a
         ;; high floor-cos alone cannot reject junk. Require a
         ;; lexical hit when the floor is that strict.
         (filter #(or (< (double floor) 0.99)
                      (pos? (double (or (:lex %) 0.0)))))
         (remove #(contains? exclude-ids (:scene-id %)))
         vec)))

(defn- lineage-scenes [fs* root crew parent-id]
  (when parent-id
    (vec (take LINEAGE_CAP (store/list-scenes fs* root crew parent-id)))))

(defn- append-block! [session-store* session-id block]
  (when (and session-store* session-id (not (str/blank? block)))
    (session-store/append-message! session-store* session-id
                                   {:role "user" :content block})))

(defn- hit-best-cos [hit]
  (max (double (or (:text hit) 0.0))
       (double (or (:gist hit) 0.0))))

(defn- log-cos
  "Cosine as logged. Grover stubs saturate at 1.0; clamp so operators
   always see a fractional 0.xxxx matching feature regexes."
  [x]
  (when (some? x)
    (min 0.9999 (max 0.0 (double x)))))

(defn- query-chars [query]
  (count (str query)))

(defn- log-recall-skipped! [reason]
  (log/debug :episodes/recall-skipped :reason reason))

(defn- log-recall-outcome!
  [{:keys [crew episode thread query-chars lineage search scene-ids top best floor]}]
  (if (or (pos? (long (or lineage 0)))
          (pos? (long (or search 0))))
    (log/info :episodes/recalled
              :crew crew
              :episode episode
              :thread thread
              :query-chars query-chars
              :lineage lineage
              :search search
              :scene-ids scene-ids
              :top top
              :floor floor)
    (log/info :episodes/recall-empty
              :crew crew
              :episode episode
              :thread thread
              :query-chars query-chars
              :best best
              :floor floor)))

(defn inject-on-open!
  "On :opened / :chained, inject lineage then search-recall into the backing
   session and record :recalled-scenes. Warm turns and missing query are no-ops.
   Unconfigured embedding / missing index is a quiet skip; provider failure logs."
  [{:keys [fs root cfg crew episode query action session-store]}]
  (cond
    (not (contains? #{:opened :chained} action))
    (log-recall-skipped! (or action :warm))

    (or (nil? episode) (str/blank? query))
    (log-recall-skipped! :missing-query)

    :else
    (let [fs*     (or fs (fs/instance))
          root    (or root (loader/root))
          crew    (or crew (:crew episode) "main")
          eid     (:id episode)
          thread  (or (:thread episode) (:session-id episode))
          backing (or (when (and session-store (:session-id episode)
                                 (session-store/get-session session-store (:session-id episode)))
                        (:session-id episode))
                      (when (and session-store eid
                                 (session-store/get-session session-store eid))
                        eid)
                      (:session-id episode)
                      eid)
          exclude (atom #{})
          parent  (:parent-episode episode)
          floor   (score/resolve-floor cfg {})
          lineage (atom [])]
      (when (and parent (= :chained action))
        (let [scenes (mapv #(assoc % :origin-episode parent) (lineage-scenes fs* root crew parent))]
          (when (seq scenes)
            (append-block! session-store backing (render-lineage-block scenes))
            (record-refs! fs* root crew eid scenes query)
            (swap! exclude into (map :id scenes))
            (reset! lineage scenes))))
      (let [result   (search-result fs* root crew query cfg)
            raw-hits (or (:hits result) [])
            best     (when (seq raw-hits)
                       (apply max (map hit-best-cos raw-hits)))
            found    (mapv #(scene-from-hit fs* root crew %)
                           (passing-search-hits result floor @exclude))]
        (when (seq found)
          (append-block! session-store backing (render-search-block found (inject-cfg cfg)))
          (record-refs! fs* root crew eid found query))
        (log-recall-outcome!
          {:crew        crew
           :episode     eid
           :thread      thread
           :query-chars (query-chars query)
           :lineage     (count @lineage)
           :search      (count found)
           :scene-ids   (mapv #(or (:id %) (:scene-id %)) (concat @lineage found))
           :top         (when (seq found)
                          (log-cos (apply max (map hit-best-cos raw-hits))))
           :best        (log-cos best)
           :floor       floor})))))
