(ns isaac.episodes.worker
  "Idle-seal + TTL-close housekeeping on a shared-scheduler interval."
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.episodes.lifecycle :as lifecycle]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.store.impl-common :as impl-common]
    [isaac.session.store.spi :as session-store]
    [isaac.tool.memory :as memory]))

(def default-tick-ms 30000)

(def ^:private ticking? (atom false))
(defonce ^:private transcript-cache (atom {}))
(defonce ^:private seal-failure-streaks (atom {}))

(defn -reset-state! []
  (reset! transcript-cache {})
  (reset! seal-failure-streaks {}))

(defn- runtime-fs [fs*]
  (or fs* (nexus/get :fs) (fs/instance)))

(defn- runtime-root [root]
  (or root (nexus/get :root) (loader/root)))

(defn- runtime-store [session-store*]
  (or session-store* (nexus/get-in [:sessions :store]) (session-store/registered-store)))

(defn- load-cfg [cfg fs* root]
  (if (seq cfg)
    cfg
    (or (loader/snapshot "episodes worker tick — live config")
        (try
          (loader/load-config! root fs* "episodes worker tick — empty snapshot, load from root")
          (catch Exception _
            nil))
        {})))

(defn- list-crew-names [fs* root]
  (let [dir (store/episodes-root root)]
    (if (fs/exists? fs* dir)
      (->> (or (fs/children fs* dir) [])
           (remove #(str/starts-with? % "."))
           vec)
      [])))

(defn- episodes-crews [cfg fs* root]
  (let [from-cfg (->> (or (:crew cfg) {})
                      (keep (fn [[crew-id crew-cfg]]
                              (when (= :episodes (:session-policy crew-cfg))
                                (if (keyword? crew-id) (name crew-id) (str crew-id))))))
        from-disk (list-crew-names fs* root)]
    (vec (distinct (concat from-cfg from-disk)))))

(defn- backing-session-id [ss ep]
  (or (when (and ss (:id ep) (session-store/get-session ss (:id ep))) (:id ep))
      (when (and ss (:session-id ep) (session-store/get-session ss (:session-id ep))) (:session-id ep))
      (:id ep)
      (:session-id ep)))

(defn- transcript-path [fs* root session-id]
  (let [loc (impl-common/locate-session root session-id fs*)]
    (str (or (:dir loc) (impl-common/session-dir root session-id)) "/current.ednl")))

(defn- cached-transcript [opts ep]
  (let [fs*        (:fs opts)
        root       (:root opts)
        ss         (:session-store opts)
        session-id (backing-session-id ss ep)
        key        [fs* root session-id]
        stamp      (fs/modified fs* (transcript-path fs* root session-id))
        cached     (get @transcript-cache key)]
    (if (and cached (= stamp (:stamp cached)))
      {:transcript (:transcript cached) :reads 0}
      (let [transcript (or (when (and ss session-id)
                             (session-store/chronicle-transcript ss session-id))
                           [])]
        (swap! transcript-cache assoc key {:stamp stamp :transcript transcript})
        {:transcript transcript :reads 1}))))

(defn- power-of-two? [n]
  (zero? (bit-and n (dec n))))

(defn- report-seal-result! [opts ep result]
  (let [key [(:root opts) (:crew opts) (:id ep)]]
    (if (or (= :error (:status result))
            (= :no-provider (:reason result)))
      (let [consecutive (get (swap! seal-failure-streaks update key (fnil inc 0)) key)]
        (when (power-of-two? consecutive)
          (log/warn :episodes/seal-failed
                    :episode (:id ep)
                    :crew (:crew opts)
                    :reason (:reason result)
                    :raw (:raw result)
                    :error (:message result)
                    :consecutive consecutive)))
      (swap! seal-failure-streaks dissoc key))))

(defn- process-episode! [opts ep]
  (let [ss (:session-store opts)]
    (if (and ss (session-store/in-flight? ss (:id ep)))
      {:status :skipped :reason :in-flight :episode-id (:id ep) :transcript-reads 0}
      (let [{:keys [transcript reads]} (cached-transcript opts ep)
            episode-opts (assoc opts :episode-id (:id ep) :transcript transcript)
            sealed       (lifecycle/maybe-seal! (assoc episode-opts
                                                       :trigger :idle
                                                       :warn-on-failure? false))
            _            (report-seal-result! opts ep sealed)
            closed       (lifecycle/maybe-close-if-cold! episode-opts)]
        {:status           (:status sealed)
         :close-status     (:status closed)
         :reason           (:reason sealed)
         :transcript-reads reads}))))

(defn tick!
  ([] (tick! {}))
  ([{:keys [now fs root cfg session-store provider model] :as opts}]
   (let [now (or now (memory/now))
         fs* (runtime-fs fs)
         root (runtime-root root)
         ss  (runtime-store session-store)
         cfg (load-cfg cfg fs* root)
         crews (episodes-crews cfg fs* root)]
     (when (compare-and-set! ticking? false true)
       (let [started (System/nanoTime)
             results (atom [])]
         (try
           (binding [memory/*now* now]
             (doseq [crew crews]
               (let [open (->> (store/list-episodes fs* root crew)
                               (filter #(= :open (:status %))))]
                 (doseq [ep open]
                   (swap! results conj
                          (process-episode! {:fs            fs*
                                             :root          root
                                             :crew          crew
                                             :cfg           cfg
                                             :session-store ss
                                             :provider      provider
                                             :model         model
                                             :now           now}
                                            ep))))))
           (finally
             (let [results @results]
               (log/info :episodes/tick
                         :elapsed-ms (long (/ (- (System/nanoTime) started) 1000000))
                         :crews (count crews)
                         :episodes-examined (count results)
                         :sealed (count (filter #(= :sealed (:status %)) results))
                         :closed (count (filter #(= :closed (:close-status %)) results))
                         :skipped-in-flight (count (filter #(= :in-flight (:reason %)) results))
                         :transcript-reads (reduce + 0 (map :transcript-reads results))))
             (reset! ticking? false))))))))

(defn start!
  [{:keys [tick-ms]
    :or   {tick-ms default-tick-ms}}]
  (let [shared-scheduler (or (nexus/get :scheduler)
                             (throw (ex-info "episodes worker requires :scheduler in isaac.nexus" {})))]
    (scheduler/schedule! shared-scheduler
                         {:id      :episodes/tick
                          :trigger {:kind :interval :ms tick-ms}
                          :handler (fn [_] (tick! {}))})
    {:scheduler shared-scheduler
     :task-id   :episodes/tick}))

(defn stop! [{:keys [scheduler task-id]}]
  (when scheduler
    (scheduler/cancel! scheduler task-id)))
