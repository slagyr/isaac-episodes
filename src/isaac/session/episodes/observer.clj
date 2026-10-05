(ns isaac.session.episodes.observer
  "Session observer: episode open/seal, scene feeding, index rows.
   Does not modify model context — that belongs to the episodes context mode."
  (:require
    [isaac.foundation.config.loader :as loader]
    [isaac.session.episodes.crew :as episode-crew]
    [isaac.session.episodes.lifecycle :as lifecycle]
    [isaac.session.episodes.store :as episode-store]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.session.session-observer :as session-observer]
    [isaac.agent.session.store.spi :as session-store]))

(defn- runtime-fs []
  (or (nexus/get :fs) (fs/instance)))

(defn- runtime-root []
  (or (nexus/get :root) (loader/root)))

(defn- runtime-store []
  (or (nexus/get-in [:sessions :store]) (session-store/registered-store)))

(defn- runtime-cfg [event]
  (or (:config event) (try (loader/snapshot "episodes observer") (catch Exception _ nil)) {}))

(defn- crew-of [store session-id cfg]
  (or (when store (:crew (session-store/get-session store session-id)))
      (episode-crew/resolve-id nil cfg)))

(defn- handle [cfg event]
  (let [session-id (:session-id event)
        fs*        (runtime-fs)
        root       (runtime-root)
        store      (runtime-store)
        cfg        (or cfg (runtime-cfg event))
        crew       (crew-of store session-id cfg)
        opts       {:fs            fs*
                    :root          root
                    :crew          crew
                    :thread        session-id
                    :session-store store
                    :cfg           cfg}]
    (when session-id
      (case (:event event)
        (:session-opened :turn-started)
        (let [resolved (lifecycle/resolve-thread! opts)
              open     (:episode resolved)
              tokens   (when store (:last-input-tokens (session-store/get-session store session-id)))]
          (when (and open tokens)
            (episode-store/write-episode! fs* root (assoc open :last-input-tokens tokens)
                                          (episode-store/list-scenes fs* root crew (:id open))))
          resolved)

        :turn-ended
        (when-let [open (episode-store/find-open-on-thread fs* root crew session-id)]
          (lifecycle/maybe-seal! (assoc opts :episode-id (:id open))))

        :compaction-spliced
        (lifecycle/chain-on-compaction! opts)

        :message-appended
        nil

        nil))))

(defn create [cfg]
  (fn [event]
    (handle cfg event)))

(session-observer/register! :episodes create)
