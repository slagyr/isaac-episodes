(ns isaac.session.policy.episodes
  "Episodes policy: a container per episode, opened on a cold first append
   (recall injected ahead of the message), sealed on clear-turn-marker!,
   closed and chained on compaction. Session ids never change."
  (:require
    [isaac.config.loader :as loader]
    [isaac.episodes.ids :as ids]
    [isaac.episodes.lifecycle :as lifecycle]
    [isaac.episodes.store :as episode-store]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.recall.inject :as recall-inject]
    [isaac.session.context :as session-ctx]
    [isaac.session.policy :as policy]
    [isaac.session.store.spi :as store]
    [isaac.tool.memory :as memory]))

(defn- runtime-fs []
  (or (nexus/get :fs) (fs/instance)))

(defn- runtime-root []
  (or (nexus/get :root) (loader/root)))

(defn- runtime-cfg []
  (or (try (loader/snapshot "episodes policy")
           (catch Exception _ nil))
      {}))

(defn- session-id* [name]
  (str name))

(defn- episode-session-id [episode]
  (or (:session-id episode) (:thread episode)))

(defn- find-open [fs* root crew session-id]
  (or (episode-store/find-open-on-thread fs* root crew session-id)
      (some (fn [ep]
              (when (and (= :open (:status ep))
                         (or (= session-id (:session-id ep))
                             (= session-id (:thread ep))))
                ep))
            (episode-store/list-episodes fs* root crew))))

(defn- latest-on-session [fs* root crew session-id]
  (->> (episode-store/list-episodes fs* root crew)
       (filter #(or (= session-id (:session-id %))
                    (= session-id (:thread %))))
       (sort-by :id)
       last))

(defn- open-container!
  "Open a new episode container for session-id. The backing store session
   is named by the (stable) session id, never by the episode id."
  [{:keys [store crew session-id parent-episode cwd origin compaction seed-compaction cfg]}]
  (let [fs*     (runtime-fs)
        root    (runtime-root)
        crew    (or crew "main")
        id      (ids/timestamped-id (str (or (memory/now) (java.time.Instant/now))))
        episode (cond-> {:id         id
                         :crew       crew
                         :status     :open
                         :session-id session-id
                         :thread     session-id}
                  parent-episode (assoc :parent-episode parent-episode))
        create-opts (cond-> {:crew          crew
                             :cwd           cwd
                             :origin        (or origin {:kind :cli})
                             :session-store store}
                      compaction (assoc :compaction compaction))]
    (episode-store/write-episode! fs* root episode [])
    (when store
      (when-not (store/get-session store session-id)
        (session-ctx/create-with-resolved-behavior! session-id create-opts)))
    (when-let [summary (:summary seed-compaction)]
      (when store
        (store/append-compaction! store session-id {:summary summary})))
    (log/info :episodes/opened :episode id :crew crew :session-id session-id :origin origin)
    episode))

(defn- stamp-closed-counters!
  "episode.edn holds transcript-level counters. Copy last-input-tokens off
   the session before compaction zeroes them."
  [fs* root episode session]
  (let [stamped (assoc episode
                  :status :closed
                  :last-input-tokens (or (:last-input-tokens session) 0))]
    (episode-store/write-episode! fs* root stamped [])
    stamped))

(defn- ensure-open-container!
  [{:keys [store crew session-id] :as opts}]
  (let [fs*        (runtime-fs)
        root       (runtime-root)
        crew       (or crew "main")
        open       (find-open fs* root crew session-id)
        ttl        (lifecycle/ttl-minutes (runtime-cfg))
        transcript (when (and store session-id)
                     (store/chronicle-transcript store session-id))
        warm?      (and open (lifecycle/warm? transcript ttl))]
    (if warm?
      {:episode open :action :warm}
      (let [prior (or (when open
                        (stamp-closed-counters! fs* root open
                                                (when store (store/get-session store session-id))))
                      (latest-on-session fs* root crew session-id))
            ep    (open-container! (cond-> opts
                                     prior (assoc :parent-episode (:id prior))))]
        {:episode ep :action (if prior :chained :opened)}))))

(defn- recall-ahead!
  [{:keys [store crew session-id query cfg]} {:keys [action episode]}]
  (when (contains? #{:opened :chained} action)
    (recall-inject/inject-on-open!
      {:fs            (runtime-fs)
       :root          (runtime-root)
       :cfg           (or cfg (runtime-cfg))
       :crew          (or crew (:crew episode))
       :episode       episode
       :query         query
       :action        action
       :session-store store})))

(defn- crew-of [store session-id fallback]
  (or fallback
      (when store (:crew (store/get-session store session-id)))
      "main"))

(defn- compact-chain!
  "Close the open episode (if any) against the pre-splice transcript, splice
   the backing session in place (session id never changes), then open a
   successor container on the same session-id. A session that has never
   opened a container still gets a closed sibling + successor — compaction
   is the chain event."
  [{:keys [store crew session-id compaction cfg cwd origin session-compaction]}]
  (let [fs*     (runtime-fs)
        root    (runtime-root)
        crew    (crew-of store session-id crew)
        session (when store (store/get-session store session-id))
        open    (or (find-open fs* root crew session-id)
                    (open-container! {:store      store
                                      :crew       crew
                                      :session-id session-id
                                      :cwd        cwd
                                      :origin     origin
                                      :compaction session-compaction}))
        closed-result (lifecycle/close-episode!
                        {:fs            fs*
                         :root          root
                         :crew          crew
                         :episode-id    (:id open)
                         :session-store store
                         :cfg           (or cfg (runtime-cfg))})
        closed-ep (stamp-closed-counters!
                    fs* root
                    (or (when (and closed-result (not= :error (:status closed-result)))
                          (:episode closed-result))
                        (episode-store/read-episode fs* root crew (:id open))
                        open)
                    session)
        spliced   (store/splice-compaction! store session-id compaction)
        successor (open-container!
                    {:store          store
                     :crew           crew
                     :session-id     session-id
                     :parent-episode (:id closed-ep)
                     :cwd            cwd
                     :origin         origin
                     :compaction     session-compaction})]
    (when successor
      (log/info :episodes/compact-chained
                :session-id session-id
                :closed (:id closed-ep)
                :successor (:id successor)))
    (when store
      (store/update-session! store session-id {:last-input-tokens 0}))
    (cond-> spliced
      successor (assoc :successor-container (:id successor)))))

(deftype EpisodesPolicy [store]
  policy/SessionPolicy
  (open-session! [_ name opts]
    (let [session-id (session-id* name)]
      (or (store/get-session store session-id)
          (store/open-session! store session-id (merge {:session-policy :episodes} opts)))))
  (delete-session! [_ name] (store/delete-session! store name))
  (rename-session! [_ old-name new-name] (store/rename-session! store old-name new-name))
  (list-sessions [_] (store/list-sessions store))
  (list-sessions-by-agent [_ agent] (store/list-sessions-by-agent store agent))
  (most-recent-session [_] (store/most-recent-session store))
  (get-session [_ name] (store/get-session store name))
  (get-transcript [_ name] (store/get-transcript store name))
  (active-transcript [_ name] (store/active-transcript store name))
  (chronicle-transcript [_ name] (store/chronicle-transcript store name))
  (update-session! [_ name updates] (store/update-session! store name updates))
  (append-message! [_ name message]
    (let [session-id (session-id* name)
          crew       (crew-of store session-id (:crew message))
          query      (when (= "user" (or (:role message) (get-in message [:message :role])))
                       (or (:content message) (get-in message [:message :content])))
          resolved   (ensure-open-container!
                       {:store      store
                        :crew       crew
                        :session-id session-id})]
      (when (and query (contains? #{:opened :chained} (:action resolved)))
        (recall-ahead! {:store store :crew crew :session-id session-id :query query :cfg (runtime-cfg)}
                       resolved))
      (store/append-message! store session-id message)))
  (append-error! [_ name error] (store/append-error! store name error))
  (append-compaction! [_ name compaction] (store/append-compaction! store name compaction))
  (append-reckoning! [_ name reckoning] (store/append-reckoning! store name reckoning))
  (splice-compaction! [_ name compaction]
    (let [session-id (session-id* name)
          session    (store/get-session store session-id)]
      (compact-chain! {:store               store
                       :crew                (:crew session)
                       :session-id          session-id
                       :compaction          compaction
                       :cfg                 (runtime-cfg)
                       :cwd                 (:cwd session)
                       :origin              (:origin session)
                       :session-compaction  (:compaction session)})))
  (truncate-after-compaction! [_ name] (store/truncate-after-compaction! store name))
  (record-turn-marker! [_ session-id marker]
    (store/record-turn-marker! store session-id marker))
  (clear-turn-marker! [_ session-id]
    (let [crew    (crew-of store session-id nil)
          open    (find-open (runtime-fs) (runtime-root) crew session-id)]
      (when open
        (lifecycle/maybe-seal!
          {:fs            (runtime-fs)
           :root          (runtime-root)
           :crew          crew
           :episode-id    (:id open)
           :session-store store
           :cfg           (runtime-cfg)}))
      (store/clear-turn-marker! store session-id)))
  (get-turn-marker [_ session-id] (store/get-turn-marker store session-id))
  (turn-markers [_] (store/turn-markers store))
  (default-session [_ _crew _opts]
    (ids/timestamped-id (str (or (memory/now) (java.time.Instant/now)))))
  (repair-transcript! [_ session-id] (store/repair-transcript! store session-id))
  (request-cancel! [_ session-id] (store/request-cancel! store session-id)))

(defn create [store]
  (->EpisodesPolicy store))

(policy/register-factory! :episodes #'create)
