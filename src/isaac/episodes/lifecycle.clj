(ns isaac.episodes.lifecycle
  "Open/close live episodes and route a THREAD handle to the backing session."
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.resolve :as resolve]
    [isaac.episodes.distill :as distill]
    [isaac.episodes.ids :as ids]
    [isaac.episodes.migrate :as migrate]
    [isaac.episodes.segment :as segment]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.provider :as llm-provider]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.recall.embedding :as embedding]
    [isaac.recall.index :as recall-index]
    [isaac.recall.inject :as recall-inject]
    [isaac.recall.score :as score]
    [isaac.session.context :as session-ctx]
    [isaac.session.store.impl-common :as impl-common]
    [isaac.session.store.spi :as session-store]
    [isaac.tool.memory :as memory])
  (:import
    (java.time Duration Instant)))

(def DEFAULT_TTL_MINUTES 60)
(def DEFAULT_IDLE_MINUTES 3)

(defn- now-instant []
  (let [n (memory/now)]
    (cond
      (instance? Instant n) n
      (string? n) (Instant/parse (if (re-find #"[zZ]|[+-]\d{2}:?\d{2}$" n) n (str n "Z")))
      :else (Instant/now))))

(defn- parse-timestamp [ts]
  (cond
    (instance? Instant ts) ts
    (number? ts) (Instant/ofEpochMilli (long ts))
    (string? ts)
    (try
      (Instant/parse (if (re-find #"[zZ]|[+-]\d{2}:?\d{2}$" ts) ts (str ts "Z")))
      (catch Exception _
        nil))
    :else nil))

(defn- last-message-timestamp [transcript]
  (->> transcript
       (filter #(= "message" (:type %)))
       last
       :timestamp))

(defn- last-entry-timestamp [transcript]
  (or (last-message-timestamp transcript)
      (->> transcript last :timestamp)))

(defn ttl-minutes [cfg]
  (or (get-in cfg [:episodes :ttl-minutes]) DEFAULT_TTL_MINUTES))

(defn idle-minutes [cfg]
  (or (get-in cfg [:episodes :seal :idle-minutes]) DEFAULT_IDLE_MINUTES))

(defn episodes-crew?
  "True when the crew is opted into :session-policy :episodes."
  [cfg crew]
  (let [crew-id (if (keyword? crew) (name crew) (str crew))
        raw     (get-in (or cfg {}) [:crew crew-id :session-policy])]
    (= :episodes (if (keyword? raw) raw (when raw (keyword (str raw)))))))

(defn warm?
  "True when the last transcript entry is within ttl-minutes of memory/now.
   Empty (no messages) successors still count as warm while their compaction
   marker is inside the TTL — the sweep only deletes them once they go cold."
  [transcript ttl]
  (let [ts (parse-timestamp (last-entry-timestamp transcript))]
    (boolean
      (when ts
        (let [age (.toMinutes (Duration/between ts (now-instant)))]
          (< age (or ttl DEFAULT_TTL_MINUTES)))))))

(defn- runtime-fs [fs*]
  (or fs* (nexus/get :fs) (fs/instance)))

(defn- runtime-root [root]
  (or root (loader/root)))

(defn- runtime-store [session-store*]
  (or session-store* (session-store/registered-store)))

(defn- backing-session-id
  "Store key for an episode's transcript. Lifecycle still names the backing
   session by episode id; the episodes policy names it by :session-id (the
   stable chain id). Prefer whichever actually exists on the store."
  [ss episode]
  (let [id  (some-> episode :id)
        sid (some-> episode :session-id)]
    (or (when (and ss id (session-store/get-session ss id)) id)
        (when (and ss sid (session-store/get-session ss sid)) sid)
        id
        sid)))

(defn- transcript-for
  "Prefer the live store under the resolved backing id; fall back to planted
   current.ednl so a memory store that never opened the session still sees
   the fixture (isaac-9tjo)."
  [ss fs* root episode]
  (let [backing (backing-session-id ss episode)]
    (or (when (and ss backing) (session-store/chronicle-transcript ss backing))
        (when (and fs* root backing)
          (let [entries (impl-common/read-transcript-raw root backing fs*)]
            (when (seq entries) entries)))
        (when (and fs* root (:id episode) (not= backing (:id episode)))
          (let [entries (impl-common/read-transcript-raw root (:id episode) fs*)]
            (when (seq entries) entries))))))

(defn- gist-provider+model [cfg root provider model]
  (if (and provider model)
    {:provider provider :model model}
    (let [gist-ref (get-in cfg [:episodes :gist-model])
          model-id (or model
                       (when gist-ref (if (keyword? gist-ref) (name gist-ref) (str gist-ref)))
                       (get-in cfg [:defaults :model]))
          ctx (when model-id
                (try
                  (resolve/resolve-crew-context cfg "main" {:model-override model-id})
                  (catch Exception _
                    nil)))
          provider* (or provider (:provider ctx))
          provider* (if (and provider* root)
                      (llm-provider/make-provider (api/display-name provider*)
                                                  (merge (api/config provider*) {:root root}))
                      provider*)]
      {:provider provider* :model (or model (:model ctx) model-id)})))

(defn open-episode!
  "Create an :open episode record and a backing session named by the episode id.
   opts: :fs :root :crew :thread :session-store :parent-episode :cwd :origin
         :compaction :seed-compaction {:summary ...}"
  [{:keys [fs root crew thread session-store parent-episode cwd origin compaction seed-compaction]}]
  (let [fs*     (runtime-fs fs)
        root    (runtime-root root)
        ss      (runtime-store session-store)
        crew    (or crew "main")
        id      (ids/timestamped-id (str (now-instant)))
        episode (cond-> {:id         id
                         :crew       crew
                         :status     :open
                         :thread     thread
                         :session-id thread}
                  parent-episode (assoc :parent-episode parent-episode))
        create-opts (cond-> {:crew          crew
                             :cwd           cwd
                             :origin        (or origin {:kind :cli})
                             :session-store ss}
                      compaction (assoc :compaction compaction))]
    (store/write-episode! fs* root episode [])
    (if ss
      (session-ctx/create-with-resolved-behavior! id create-opts)
      (log/warn :episodes/open-without-store :episode id :crew crew))
    (when-let [summary (:summary seed-compaction)]
      (when ss
        (session-store/append-compaction! ss id {:summary summary})))
    (log/info :episodes/opened :episode id :crew crew :thread thread :origin origin)
    episode))

(defn- index-after-close!
  "Embed+index scenes sealed by this close. Unconfigured embedding is a quiet
   skip; provider failure logs and leaves scenes unindexed."
  [fs* root crew cfg]
  (try
    (let [result (recall-index/index-crew! fs* root crew (or cfg {}) {})]
      (cond
        (nil? result) nil
        (:error result)
        (when (and (not= :no-embedding (:error result)))
          (log/warn :recall/index-skipped :crew crew :reason (:error result)
                    :message (:message result)))

        :else result))
    (catch Exception e
      (log/warn :recall/index-skipped :crew crew :reason :embed-failed
                :error (.getMessage e))
      nil)))

(defn- empty-transcript?
  "True when there are no type=message entries — only markers/compaction."
  [transcript]
  (empty? (filter #(= "message" (:type %)) (or transcript []))))

(defn- close-result-status [result]
  (or (:status result) (get-in result [:episode :status])))

(defn- succeeded-close? [result]
  (contains? #{:closed :partial :resumed} (close-result-status result)))

(defn- delete-empty-episode!
  "Drop the episode record and its backing session. No LLM pass."
  [fs* root crew episode-id ss]
  (store/delete-episode! fs* root crew episode-id)
  (when ss
    (session-store/delete-session! ss episode-id))
  ;; Memory store only deletes disk when the session is in its atom; planted
  ;; file fixtures (and sidecar leftovers) still need the directory gone.
  (impl-common/delete-tree! fs* (impl-common/session-dir root episode-id))
  (log/info :episodes/deleted :episode episode-id :crew crew :reason :empty)
  {:status :deleted :reason :empty :episode-id episode-id})

(defn close-episode!
  "Seal an open episode via the migrate/segment pipeline. Preserves :thread
   and :parent-episode on the closed record. Indexes sealed scenes when
   embedding is configured (isaac-h5dk)."
  [{:keys [fs root crew episode-id session-store provider model cfg]}]
  (let [fs*     (runtime-fs fs)
        root    (runtime-root root)
        ss      (runtime-store session-store)
        crew    (or crew "main")
        existing (store/read-episode fs* root crew episode-id)
        backing  (backing-session-id ss existing)
        session  (when ss (session-store/get-session ss backing))
        transcript (transcript-for ss fs* root existing)
        {:keys [provider model]} (gist-provider+model (or cfg {}) root provider model)]
    (cond
      (nil? existing)
      {:exit 1 :status :error :message (str "unknown episode: " episode-id)}

      (not= :open (:status existing))
      {:exit 0 :status (:status existing) :episode existing :message "already closed"}

      (nil? session)
      {:exit 1 :status :error :message (str "unknown backing session: " episode-id)}

      (empty-transcript? transcript)
      (delete-empty-episode! fs* root crew episode-id ss)

      (nil? provider)
      {:exit 1 :status :error :message "no gist model/provider resolved — set :episodes {:gist-model ...} or :defaults :model"}

      :else
      (let [_ (when-not (:migrated-from existing)
                (store/write-episode! fs* root (assoc existing :migrated-from episode-id) []))
            result (migrate/migrate-session!
                     {:fs fs* :root root :session session :transcript transcript
                      :provider provider :model model :force? false
                      :episode-id episode-id})
            closed (when-let [ep (:episode result)]
                     (let [merged (cond-> (assoc ep
                                            :id                episode-id
                                            :thread            (:thread existing)
                                            :session-id        (or (:session-id existing) (:thread existing))
                                            :status            (or (:status ep) :closed)
                                            :last-input-tokens (or (:last-input-tokens session)
                                                                   (:last-input-tokens existing)
                                                                   0))
                                    (:parent-episode existing)
                                    (assoc :parent-episode (:parent-episode existing)))]
                       (store/write-episode! fs* root merged (or (:scenes result) [])
                                             {:replace-scenes? true})
                       merged))]
        (when closed
          (log/info :episodes/closed :episode episode-id :crew crew :thread (:thread existing)))
        (let [indexed (when closed (index-after-close! fs* root crew cfg))]
          (cond-> result
            closed (assoc :episode closed)
            (and indexed (pos? (or (:new indexed) 0)))
            (assoc :indexed (:new indexed))))))))

(defn close-open-episodes!
  "Close every :open episode for crew. Returns {:closed n :results [...]}."
  [{:keys [fs root crew session-store provider model cfg] :as opts}]
  (let [fs*  (runtime-fs fs)
        root (runtime-root root)
        crew (or crew "main")
        open (->> (store/list-episodes fs* root crew)
                  (filter #(= :open (:status %))))
        results (mapv (fn [ep]
                        (close-episode! (assoc opts :fs fs* :root root :crew crew
                                               :episode-id (:id ep)
                                               :session-store session-store
                                               :provider provider :model model :cfg cfg)))
                      open)]
    {:closed  (count (filter #(contains? #{:closed :partial :resumed}
                                        (or (:status %) (get-in % [:episode :status])))
                            results))
     :deleted (count (filter #(= :deleted (:status %)) results))
     :failed  (count (filter #(= :error (:status %)) results))
     :results results}))

(defn- chain-successor!
  "Close the open episode and open a successor on the same thread."
  [{:keys [fs root crew thread session-store cwd origin compaction seed-compaction]
    :as opts}
   open-ep]
  (let [closed    (close-episode! (assoc opts :episode-id (:id open-ep)))
        successor (open-episode! {:fs fs :root root :crew crew :thread thread
                                  :session-store session-store
                                  :parent-episode (:id open-ep)
                                  :cwd cwd :origin origin :compaction compaction
                                  :seed-compaction seed-compaction})]
    {:session-key (:id successor)
     :episode     successor
     :closed      (:episode closed)
     :action      :chained}))

(defn- latest-on-thread [fs* root crew thread]
  (->> (store/list-episodes fs* root crew)
       (filter #(= thread (:thread %)))
       (sort-by :id)
       last))

(defn resolve-thread!
  "Map a THREAD handle to the backing session of the current open episode.
   Warm (last message within TTL) -> append. Cold/absent -> close-then-open
   with :parent-episode. After an explicit close, the next prompt still
   chains from the most recent episode on the thread even inside the warm
   window. opts: :fs :root :crew :thread :session-store :cfg
   :provider :model :cwd :origin :compaction"
  [{:keys [fs root crew thread session-store cfg] :as opts}]
  (let [fs*  (runtime-fs fs)
        root (runtime-root root)
        ss   (runtime-store session-store)
        crew (or crew "main")
        ttl  (ttl-minutes cfg)
        open (store/find-open-on-thread fs* root crew thread)]
    (cond
      (nil? open)
      (let [prior (latest-on-thread fs* root crew thread)
            ep    (open-episode! (cond-> (assoc opts :fs fs* :root root :crew crew
                                                :session-store ss)
                                   prior (assoc :parent-episode (:id prior))))]
        {:session-key (:id ep)
         :episode     ep
         :action      (if prior :chained :opened)})

      :else
      (let [backing    (backing-session-id ss open)
            transcript (when ss (session-store/chronicle-transcript ss backing))]
        (if (warm? transcript ttl)
          {:session-key backing :episode open :action :warm}
          (chain-successor! (assoc opts :fs fs* :root root :crew crew
                                   :session-store ss)
                            open))))))

(defn maybe-recall-at-open!
  "Inject lineage + search-recall after resolve-thread! on a cold open/chain."
  [{:keys [action episode] :as resolved} {:keys [query cfg fs root crew session-store]}]
  (recall-inject/inject-on-open!
    {:fs            fs
     :root          root
     :cfg           cfg
     :crew          (or crew (:crew episode))
     :episode       episode
     :query         query
     :action        action
     :session-store session-store})
  resolved)

(defn- seal-knobs [cfg]
  (let [seal (get-in (or cfg {}) [:episodes :seal] {})]
    {:size-cap         (or (:size-cap seal) segment/DEFAULT_SIZE_CAP)
     :drift-threshold  (:drift-threshold seal)
     :min-tail         (:min-tail seal)
     :idle-minutes     (or (:idle-minutes seal) DEFAULT_IDLE_MINUTES)}))

(defn- message-entries [transcript]
  (filterv #(= "message" (:type %)) (or transcript [])))

(defn- tail-after-sealed [messages sealed-scenes]
  (let [ids     (mapv :id messages)
        ranks   (keep (fn [s]
                        (let [i (.indexOf ids (:end-id s))]
                          (when-not (neg? i) i)))
                      sealed-scenes)
        cut     (if (seq ranks) (inc (apply max ranks)) 0)]
    (subvec messages (min cut (count messages)))))

(defn- last-exchange-text [messages]
  (->> (take-last 2 messages)
       (map distill/distill-entry)
       (keep :text)
       (str/join "\n")))

(defn- embed-one [cfg text]
  (try
    (when-not (str/blank? text)
      (let [r (embedding/embed-texts cfg [text])]
        (when-not (:error r)
          (first (:vectors r)))))
    (catch Exception _
      nil)))

(defn- running-mean [prev-ints n new-raw]
  (let [new-u (vec (seq (score/normalize-vector new-raw)))]
    (if (or (empty? prev-ints) (zero? (or n 0)))
      {:vector (vec (seq (score/quantize-vector new-u))) :n 1}
      (let [prev (mapv #(/ (double %) score/VECTOR_SCALE) prev-ints)
            n    (long n)
            mean (mapv (fn [p x] (/ (+ (* p n) x) (inc n))) prev new-u)
            unit (score/normalize-vector mean)]
        {:vector (vec (seq (score/quantize-vector unit))) :n (inc n)}))))

(defn- cosine-to-rolling [rolling-ints new-raw]
  (when (seq rolling-ints)
    (score/cosine (int-array rolling-ints)
                  (score/quantize-vector (score/normalize-vector new-raw)))))

(defn- persist-vector! [fs* root episode mean]
  (store/write-episode! fs* root
                        (assoc episode
                          :open-scene-vector (:vector mean)
                          :open-scene-vector-n (:n mean))
                        []))

(defn- commit-live-seal!
  [fs* root crew cfg episode sealed new-scenes trigger]
  (let [all (vec (concat (remove nil? sealed) new-scenes))
        ep  (-> episode
                (assoc :scene-ids (mapv :id all))
                (dissoc :open-scene-vector :open-scene-vector-n))]
    (store/write-episode! fs* root ep new-scenes)
    (let [indexed (index-after-close! fs* root crew cfg)]
      (log/info :episodes/live-sealed :episode (:id episode)
                :sealed (count new-scenes) :trigger trigger)
      (cond-> {:status :sealed :sealed (count new-scenes)
               :trigger trigger :scenes new-scenes}
        (and indexed (pos? (or (:new indexed) 0)))
        (assoc :indexed (:new indexed))))))

(defn maybe-seal!
  "Live seal. Order: update rolling open-scene vector → check
   idle/drift/cap triggers → segment tail → seal (idle/hard-cap
   single-scene seals the whole tail; otherwise leave-open 1) → index → reset vector.
   Failure is loud-logged and leaves the turn / episode unharmed."
  [{:keys [fs root crew episode-id session-store provider model cfg trigger]}]
  (let [fs*  (runtime-fs fs)
        root (runtime-root root)
        ss   (runtime-store session-store)
        crew (or crew "main")
        cfg  (or cfg {})
        existing (when (and root episode-id)
                   (store/read-episode fs* root crew episode-id))]
    (cond
      (or (nil? existing) (not= :open (:status existing)))
      {:status :skipped :reason :not-open}

      (nil? ss)
      {:status :skipped :reason :no-store}

      :else
      (try
        (let [{:keys [size-cap drift-threshold min-tail idle-minutes]} (seal-knobs cfg)
              transcript (session-store/chronicle-transcript ss (backing-session-id ss existing))
              sealed     (vec (remove nil? (store/list-scenes fs* root crew episode-id)))
              tail       (tail-after-sealed (message-entries transcript) sealed)
              n          (count tail)
              new-raw    (embed-one cfg (last-exchange-text tail))
              prior-vec  (:open-scene-vector existing)
              cosine     (when new-raw (cosine-to-rolling prior-vec new-raw))
              new-mean   (when new-raw (running-mean prior-vec (:open-scene-vector-n existing) new-raw))
              cap-fired? (>= n size-cap)
              drift-fired? (boolean
                             (and drift-threshold min-tail new-raw (seq prior-vec)
                                  (>= n min-tail)
                                  (number? cosine)
                                  (< cosine drift-threshold)))
              idle-fired? (boolean
                            (and (= :idle trigger)
                                 (pos? n)
                                 (let [ts (parse-timestamp (last-message-timestamp transcript))]
                                   (and ts
                                        (>= (.toMinutes (Duration/between ts (now-instant)))
                                            idle-minutes)))))
              trigger    (cond
                           idle-fired?   :idle
                           cap-fired?    :size-cap
                           drift-fired?  :drift
                           :else         nil)
              episode*   (cond-> existing
                           new-mean (assoc :open-scene-vector (:vector new-mean)
                                           :open-scene-vector-n (:n new-mean)))]
          (when (and new-mean (nil? trigger))
            (persist-vector! fs* root existing new-mean))
          (if-not trigger
            {:status :skipped :reason :no-trigger}
            (let [{:keys [provider model]} (gist-provider+model cfg root provider model)]
              (if-not provider
                (do
                  (when new-mean (persist-vector! fs* root existing new-mean))
                  (log/warn :episodes/seal-failed :episode episode-id :reason :no-provider)
                  {:status :skipped :reason :no-provider})
                (let [distilled (mapv distill/distill-entry tail)
                      result    (segment/segment-span! provider model distilled nil)]
                  (if-not (:ok result)
                    (do
                      (when new-mean (persist-vector! fs* root existing new-mean))
                      (log/warn :episodes/seal-failed :episode episode-id
                                :reason (or (:error result) :bad-parse)
                                :raw (:raw result))
                      {:status :error :reason (:error result)})
                    (let [resolved   (:ok result)
                          leave-open (if (or (= :idle trigger)
                                             (and (= :size-cap trigger)
                                                  (= 1 (count resolved))))
                                       0 1)
                          new-scenes (segment/seal-scenes distilled resolved trigger
                                                          {:leave-open leave-open
                                                           :used-ids   (set (keep :id sealed))})]
                      (if (empty? new-scenes)
                        (do
                          (when new-mean (persist-vector! fs* root existing new-mean))
                          {:status :skipped :reason :single-scene :trigger trigger})
                        (commit-live-seal! fs* root crew cfg episode* sealed
                                           new-scenes trigger)))))))))
        (catch Exception e
          (log/warn :episodes/seal-failed :episode episode-id
                    :reason :exception :error (.getMessage e))
          {:status :error :reason :exception :message (.getMessage e)})))))

(defn maybe-close-if-cold!
  "TTL close after the idle-seal pass. Skips in-flight / already-closed /
   still-warm episodes. Empty transcripts (no messages) are deleted, not closed.
   Logs :episodes/closing before the attempt; :closed / :deleted only after
   success; :close-failed with the error on failure."
  [{:keys [fs root crew episode-id session-store provider model cfg]}]
  (let [fs*      (runtime-fs fs)
        root     (runtime-root root)
        ss       (runtime-store session-store)
        crew     (or crew "main")
        existing (when (and root episode-id)
                   (store/read-episode fs* root crew episode-id))]
    (cond
      (or (nil? existing) (not= :open (:status existing)))
      {:status :skipped :reason :not-open}

      (and ss (session-store/in-flight? ss episode-id))
      {:status :skipped :reason :in-flight}

      :else
      (let [transcript (transcript-for ss fs* root existing)
            ttl        (ttl-minutes cfg)]
        (if (warm? transcript ttl)
          {:status :skipped :reason :warm}
          (do
            (log/info :episodes/closing :episode episode-id :crew crew)
            (try
              (if (empty-transcript? transcript)
                (delete-empty-episode! fs* root crew episode-id ss)
                (let [closed (close-episode! {:fs fs* :root root :crew crew
                                              :episode-id episode-id
                                              :session-store ss
                                              :provider provider :model model :cfg cfg})]
                  (if (succeeded-close? closed)
                    (do
                      (log/info :episodes/closed :episode episode-id :crew crew :reason :ttl-sweep)
                      {:status :closed :reason :ttl :episode (:episode closed)})
                    (let [message (or (:message closed) "close did not write a closed episode")]
                      (log/warn :episodes/close-failed :episode episode-id :crew crew :error message)
                      {:status :error :reason :close-failed :error message :result closed}))))
              (catch Exception e
                (let [message (or (.getMessage e) (str e))]
                  (log/warn :episodes/close-failed :episode episode-id :crew crew :error message)
                  {:status :error :reason :close-failed :error message})))))))))

(defn compact-close!
  "Compaction on an episode crew: close the current episode and open a
   successor whose transcript begins with the compaction summary.
   Reuses an already-open successor on the thread (at most one open episode)."
  [{:keys [fs root crew thread session-store summary cfg cwd origin compaction] :as opts}]
  (let [fs*  (runtime-fs fs)
        root (runtime-root root)
        ss   (runtime-store session-store)
        crew (or crew "main")
        target (when-let [id (:episode-id opts)]
                 (store/read-episode fs* root crew id))
        open   (store/find-open-on-thread fs* root crew thread)]
    (cond
      (and open target (not= (:id open) (:id target)))
      {:session-key (:id open)
       :episode     open
       :action      :reused}

      (or open (and target (= :open (:status target))))
      (chain-successor! (assoc opts :fs fs* :root root :crew crew
                               :thread (or thread (:thread (or open target)))
                               :session-store ss
                               :seed-compaction {:summary summary}
                               :cwd cwd :origin origin :compaction compaction
                               :cfg cfg)
                        (or open target))

      :else
      {:error :no-open-episode})))
