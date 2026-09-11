(ns isaac.episodes.worker
  "Idle-seal + TTL-close housekeeping on a shared-scheduler interval."
  (:require
    [isaac.config.loader :as loader]
    [isaac.episodes.lifecycle :as lifecycle]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [clojure.string :as str]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.store.spi :as session-store]
    [isaac.tool.memory :as memory]))

(def default-tick-ms 30000)

(def ^:private ticking? (atom false))

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

(defn- process-episode! [opts ep]
  (let [ss (:session-store opts)]
    (if (and ss (session-store/in-flight? ss (:id ep)))
      {:status :skipped :reason :in-flight :episode-id (:id ep)}
      (let [sealed (lifecycle/maybe-seal! (assoc opts :episode-id (:id ep) :trigger :idle))]
        (lifecycle/maybe-close-if-cold! (assoc opts :episode-id (:id ep)))
        sealed))))

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
       (try
         (binding [memory/*now* now]
           (doseq [crew crews]
             (let [open (->> (store/list-episodes fs* root crew)
                             (filter #(= :open (:status %))))]
               (doseq [ep open]
                 (process-episode! {:fs            fs*
                                    :root          root
                                    :crew          crew
                                    :cfg           cfg
                                    :session-store ss
                                    :provider      provider
                                    :model         model
                                    :now           now}
                                   ep)))))
         (finally
           (reset! ticking? false)))))))

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
