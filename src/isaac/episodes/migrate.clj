(ns isaac.episodes.migrate
  "Materialize a session transcript as a closed episode (scenes + gists)."
  (:require
    [isaac.config.resolve :as resolve]
    [isaac.episodes.distill :as distill]
    [isaac.episodes.ids :as ids]
    [isaac.episodes.segment :as segment]
    [isaac.episodes.store :as store]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.provider :as llm-provider]
    [isaac.session.store.spi :as session-store]))

(defn- first-message-timestamp [transcript]
  (->> transcript
       (filter #(= "message" (:type %)))
       first
       :timestamp))

(defn- last-message-timestamp [transcript]
  (->> transcript
       (filter #(= "message" (:type %)))
       last
       :timestamp))

(defn- scene-index-by-start
  "Map start-id -> scene for resume checks."
  [scenes]
  (into {} (map (fn [s] [(:start-id s) s]) scenes)))

(defn- span-already-sealed?
  "A span is sealed when every message id in the span is covered by existing
   scenes whose start-ids are present in the sealed set for this span's first
   message — practical check: first message id of span is a sealed scene start
   and the sealed scene chain covers the span end."
  [sealed-scenes span-messages]
  (let [by-start (scene-index-by-start sealed-scenes)
        first-id (:id (first span-messages))
        last-id  (:id (last span-messages))]
    (boolean
      (when-let [s (get by-start first-id)]
        ;; Walk sealed scenes from this start until we cover last-id.
        (loop [current s
               guard 0]
          (cond
            (> guard 10000) false
            (= last-id (:end-id current)) true
            :else
            (let [end-id (:end-id current)
                  ids (mapv :id span-messages)
                  idx (.indexOf ids end-id)
                  next-id (when (and (>= idx 0) (< (inc idx) (count ids)))
                            (nth ids (inc idx)))]
              (if-let [nxt (and next-id (get by-start next-id))]
                (recur nxt (inc guard))
                false))))))))

(defn- normalize-flagged
  "Coerce legacy int span indices and map records into
   {:span <1-based> :raw ...} maps."
  [items]
  (mapv (fn [item]
          (cond
            (map? item)
            (let [span (or (:span item)
                           (when-let [i (:index item)] (inc (long i)))
                           item)]
              (cond-> {:span (long span)}
                (:raw item) (assoc :raw (:raw item))))

            (number? item)
            ;; Legacy 0-based indices stored on partial episodes from rxr4.
            {:span (inc (long item))}

            :else
            {:span (long item)}))
        (or items [])))

(defn- flagged-span-numbers
  "Set of 1-based span numbers currently flagged."
  [episode]
  (->> (normalize-flagged (:flagged-spans episode))
       (map :span)
       set))

(defn- resolve-gist-model
  "Resolve {:provider :model} for gisting from config."
  [cfg]
  (let [cfg (or cfg {})
        gist-ref (get-in cfg [:episodes :gist-model])
        model-id (or (when gist-ref
                       (if (keyword? gist-ref) (name gist-ref) (str gist-ref)))
                     (get-in cfg [:defaults :model]))
        ctx (when model-id
              (try
                (resolve/resolve-crew-context cfg "main" {:model-override model-id})
                (catch Exception _
                  (resolve/resolve-crew-context cfg "main"))))
        ctx (or ctx (resolve/resolve-crew-context cfg "main"))]
    {:provider (:provider ctx)
     :model    (or (:model ctx) model-id "gist")
     :model-id model-id}))

(defn- provider-with-root
  "Rebuild a provider with :root merged into its config. OAuth token
   resolution (auth.json) requires it — same augmentation the drive
   turn path applies before dispatch."
  [provider root]
  (if (and provider root)
    (llm-provider/make-provider (api/display-name provider)
                                (merge (api/config provider) {:root root}))
    provider))

(defn- print-err! [msg]
  (binding [*out* *err*]
    (println msg)))

(defn migrate-session!
  "Run migration. opts:
     :fs :root :session :transcript :provider :model
     :force? :size-cap
   Returns {:exit 0|1 :status :closed|:partial|:already-migrated|:resumed|:error
            :episode ... :message ...}"
  [{:keys [fs root session transcript provider model force? size-cap episode-id]
    :or {force? false}}]
  (let [crew (or (:crew session) "main")
        session-id (or (:id session) (:key session))
        existing (or (when episode-id (store/read-episode fs root crew episode-id))
                     (store/find-by-migrated-from fs root crew session-id))
        sealed (if (and existing (not force?))
                 (store/list-scenes fs root crew (:id existing))
                 [])
        fully-closed? (and existing
                           (= :closed (:status existing))
                           (not force?)
                           (empty? (flagged-span-numbers existing)))]
    (cond
      fully-closed?
      {:exit 0 :status :already-migrated :episode existing
       :message "already migrated"}

      (empty? (filter #(= "message" (:type %)) transcript))
      {:exit 1 :status :error :message "session has no messages"}

      :else
      (let [t-run (System/nanoTime)
            tokens* (atom {:in 0 :out 0})
            spans (segment/compaction-spans transcript size-cap)
            episode-id (or (:id existing)
                           (ids/timestamped-id (first-message-timestamp transcript)))
            ;; Map 1-based span number -> flagged record
            flagged* (atom (if force?
                             {}
                             (into {}
                                   (map (fn [f] [(:span f) f]))
                                   (normalize-flagged (:flagged-spans existing)))))
            scenes-acc (atom (if force? [] (vec sealed)))
            any-work? (atom false)
            resumed? (atom (boolean (and existing (not force?))))
            abort* (atom nil)]
        (println (str "migrating " session-id " -> episode " episode-id
                      " (crew " crew ", " (count spans) " span" (when (not= 1 (count spans)) "s")
                      ", " (count (filter #(= "message" (:type %)) transcript)) " messages)"))
        (doseq [span spans
                :while (nil? @abort*)]
          (let [raw-msgs (:messages span)
                distilled (mapv distill/distill-entry raw-msgs)
                idx (:index span)                 ; 0-based
                span-n (inc idx)                  ; 1-based (user-facing)
                skip? (and (not force?)
                           (not (contains? @flagged* span-n))
                           (span-already-sealed? @scenes-acc raw-msgs))]
            (if skip?
              (println (str "  span " span-n "/" (count spans) ": already sealed"))
              (do
                (reset! any-work? true)
                (let [t0 (System/nanoTime)
                      result (segment/segment-span! provider model distilled
                                                    (:preceding-summary span))
                      secs (/ (long (/ (- (System/nanoTime) t0) 100000000)) 10.0)
                      usage (or (:usage result) {:in 0 :out 0})
                      _ (swap! tokens* (fn [t] {:in  (+ (:in t) (:in usage 0))
                                                :out (+ (:out t) (:out usage 0))}))]
                  (cond
                    (:ok result)
                    (let [sealed-scenes (segment/seal-scenes distilled (:ok result)
                                                             (if (:preceding-summary span)
                                                               :compaction
                                                               :migrate)
                                                             {:used-ids (set (map :id @scenes-acc))})
                          span-ids (set (map :id raw-msgs))
                          kept (remove #(contains? span-ids (:start-id %)) @scenes-acc)]
                      (println (str "  span " span-n "/" (count spans) ": "
                                    (count distilled) " messages -> "
                                    (count sealed-scenes) " scene" (when (not= 1 (count sealed-scenes)) "s")
                                    " (" secs "s, " (:in usage) " in, " (:out usage) " out)"))
                      (reset! scenes-acc (vec (concat kept sealed-scenes)))
                      (swap! flagged* dissoc span-n))

                  (= :provider-error (:error result))
                  (reset! abort* {:exit 1
                                  :status :error
                                  :message (:message result)})

                  :else
                  (let [raw (or (:raw result) "")]
                    (swap! flagged* assoc span-n {:span span-n :raw raw})
                    (print-err! (str "span " span-n " flagged: unparseable segmentation output")))))))))

        (if-let [abort @abort*]
          abort
          (let [msg-ids (mapv :id (filter #(= "message" (:type %)) transcript))
                rank (fn [s]
                       (let [i (.indexOf msg-ids (:start-id s))]
                         (if (neg? i) Integer/MAX_VALUE i)))
                scenes (vec (sort-by (juxt rank :id) @scenes-acc))
                flagged-list (->> (vals @flagged*)
                                  (sort-by :span)
                                  vec)
                status (if (seq flagged-list) :partial :closed)
                episode {:id            episode-id
                         :crew          crew
                         :status        status
                         :migrated-from session-id
                         :scene-ids     (mapv :id scenes)
                         :started-at    (first-message-timestamp transcript)
                         :ended-at      (last-message-timestamp transcript)
                         :flagged-spans flagged-list}
                _ (store/write-episode! fs root episode scenes {:replace-scenes? true})
                status-kw (cond
                            (= :partial status) :partial
                            (and @resumed? @any-work?) :resumed
                            :else :closed)
                counts (str (count spans) " span" (when (not= 1 (count spans)) "s")
                            ", " (count scenes) " scene" (when (not= 1 (count scenes)) "s"))
                total-secs (/ (long (/ (- (System/nanoTime) t-run) 100000000)) 10.0)
                totals (str "(" total-secs "s, " (:in @tokens*) " in, " (:out @tokens*) " out)")
                msg (case status-kw
                      :partial (str "partial migration; flagged spans: "
                                    (pr-str (mapv :span flagged-list)) " " totals)
                      :resumed (str "resumed: " counts " " totals)
                      :closed  (str "migrated: " counts " " totals)
                      "migrated")]
            {:exit (if (= :partial status) 1 0)
             :status status-kw
             :episode episode
             :message msg
             :scenes scenes}))))))

(defn migrate-session-id!
  "High-level: look up session + transcript from the registered store, resolve
   gist model from cfg, run migrate-session!."
  [{:keys [fs root cfg session-id force? session-store]
    :or {force? false}}]
  (let [ss (or session-store (session-store/registered-store))
        session (when ss (session-store/get-session ss session-id))]
    (if-not session
      {:exit 1 :status :error :message (str "unknown session: " session-id)}
      (let [transcript (session-store/chronicle-transcript ss session-id)
            {:keys [provider model]} (resolve-gist-model cfg)
            provider (provider-with-root provider root)]
        (if-not provider
          {:exit 1 :status :error :message "no gist model/provider resolved — set :episodes {:gist-model ...} or :defaults :model"}
          (migrate-session!
            {:fs fs :root root :session session :transcript transcript
             :provider provider :model model :force? force?}))))))
