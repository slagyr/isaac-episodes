(ns isaac.session.episodes.context
  "Episodes context mode: cold open at episode boundaries, lineage +
   continuation seeding, recall injection. Waits for the episodes observer
   before constructing context. Does not write the recall block into the
   stored transcript — :full still sees the raw conversation."
  (:require
    [clojure.string :as str]
    [isaac.foundation.config.loader :as loader]
    [isaac.session.episodes.crew :as episode-crew]
    [isaac.session.episodes.lifecycle :as lifecycle]
    [isaac.session.episodes.recall.inject :as recall-inject]
    [isaac.session.episodes.store :as episode-store]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.session.context-mode :as context-mode]
    [isaac.agent.session.session-observer :as session-observer]
    [isaac.agent.session.store.spi :as session-store]))

(defonce ^:private turn-ctx* (atom {}))

(defn- runtime-fs []
  (or (nexus/get :fs) (fs/instance)))

(defn- runtime-root []
  (or (nexus/get :root) (loader/root)))

(defn- runtime-store [store]
  (or store (nexus/get-in [:sessions :store]) (session-store/registered-store)))

(defn- runtime-cfg []
  (or (try (loader/snapshot "episodes context") (catch Exception _ nil)) {}))

(defn- crew-of [store session-id]
  (or (when store (:crew (session-store/get-session store session-id)))
      (episode-crew/resolve-id nil)))

(defn- thread-key []
  (Thread/currentThread))

(defn- entry-text [entry]
  (let [content (get-in entry [:message :content] (:content entry))]
    (cond
      (string? content) content
      (sequential? content) (str (or (get-in content [0 :text])
                                     (get-in content [0 "text"])
                                     ""))
      :else (str content))))

(defn- set-entry-text [entry text]
  (let [content (get-in entry [:message :content])]
    (cond
      (string? content) (assoc-in entry [:message :content] text)
      (sequential? content) (assoc-in entry [:message :content 0 :text] text)
      (get-in entry [:message :content]) (assoc-in entry [:message :content] text)
      :else (assoc entry :content text))))

(defn- prefix-last-user [transcript prefix]
  (if (str/blank? prefix)
    (vec transcript)
    (let [idx (last (keep-indexed
                      (fn [i e]
                        (when (and (= "message" (:type e))
                                   (= "user" (or (get-in e [:message :role]) (:role e))))
                          i))
                      transcript))]
      (if idx
        (let [entry (nth transcript idx)
              text  (entry-text entry)]
          (if (and text (str/includes? text prefix))
            (vec transcript)
            (assoc (vec transcript) idx (set-entry-text entry (str prefix "\n\n" text)))))
        (vec transcript)))))

(defn- empty-episode? [store episode session-id]
  (let [transcript (when (and store session-id)
                     (session-store/active-transcript store session-id))]
    (empty? (filter #(= "message" (:type %))
                    (lifecycle/slice-for-episode transcript episode)))))

(defn prepare-turn!
  "Wait for the observer, ensure an episode exists, inject recall on a cold
   or first open, and stash session-id so select-transcript can slice."
  [store session-key input]
  (session-observer/await! session-key :episodes)
  (let [store  (runtime-store store)
        fs*    (runtime-fs)
        root   (runtime-root)
        cfg    (runtime-cfg)
        crew   (crew-of store session-key)
        open   (or (episode-store/find-open-on-thread fs* root crew session-key)
                   (:episode (lifecycle/resolve-thread!
                               {:fs fs* :root root :crew crew :thread session-key
                                :session-store store :cfg cfg})))
        cold?  (boolean (and open (empty-episode? store open session-key)))
        action (cond
                 (not cold?) :warm
                 (:parent-episode open) :chained
                 :else :opened)]
    (when (and cold? input)
      (recall-inject/inject-on-open!
        {:fs            fs*
         :root          root
         :cfg           cfg
         :crew          crew
         :episode       open
         :query         input
         :action        action
         :session-store store})
      (when store
        (session-store/update-session! store session-key {:last-input-tokens 0})))
    (let [open (episode-store/find-open-on-thread fs* root crew session-key)]
      (swap! turn-ctx* assoc (thread-key) {:session-id session-key
                                           :crew       crew
                                           :prefix     (:pending-recall open)}))
    action))

(defn select-transcript
  "Slice the session transcript to the current episode and prefix pending
   recall onto the last user message. The stored transcript is unchanged."
  [transcript]
  (if-let [{:keys [session-id crew]} (get @turn-ctx* (thread-key))]
    (let [store  (runtime-store nil)
          fs*    (runtime-fs)
          root   (runtime-root)
          crew   (or crew (crew-of store session-id))
          open   (episode-store/find-open-on-thread fs* root crew session-id)
          sliced (lifecycle/slice-for-episode transcript open)
          prefix (when open (:pending-recall open))]
      (prefix-last-user sliced prefix))
    (vec (or transcript []))))

(defn- has-episodes-observer? [store session-key]
  (let [cfg  (runtime-cfg)
        sess (when store (session-store/get-session store session-key))
        crew (or (:crew sess) (crew-of store session-key))
        obs  (or (:observers sess)
                 (get-in cfg [:crew crew :observers])
                 (get-in cfg [:crew (keyword crew) :observers]))]
    (boolean (some #{:episodes} (map keyword obs)))))

(defn- reset-prepare [store session-key input]
  (when (has-episodes-observer? store session-key)
    (prepare-turn! store session-key input)))

(defn- wrap-reset-input [input]
  (let [prefix (:prefix (get @turn-ctx* (thread-key)))]
    (if (str/blank? prefix)
      input
      (str prefix "\n\n" input))))

(context-mode/register! :episodes {:factory    select-transcript
                                   :prepare    prepare-turn!
                                   :wait-for   :episodes
                                   :requires   {:observers #{:episodes}}})

(context-mode/register! :reset {:factory    context-mode/reset-transcript
                                :prepare    reset-prepare
                                :wrap-input wrap-reset-input})
