(ns isaac.episodes.store
  "Filesystem layout for episodes nested under their session:

     <root>/sessions/<crew>/<session-id>/episodes/<episode-id>/episode.edn
     <root>/sessions/<crew>/<session-id>/episodes/<episode-id>/current.ednl
     <root>/sessions/<crew>/<session-id>/episodes/<episode-id>/scenes/<scene-id>.md

   Legacy layout (read during migrate-layout, still listed as a fallback):

     <root>/episodes/<crew>/<episode-id>/episode.edn
     <root>/episodes/<crew>/<episode-id>/<scene-id>.md

   Scenes are markdown with YAML frontmatter (structure) + distilled text body.
   episode.edn stays EDN (all structure, no prose).

   Frontmatter split/parse matches isaac.config's md-with-frontmatter
   component (same regex + clj-yaml)."
  (:require
    [clj-yaml.core :as yaml]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.session.store.impl-common :as impl]))

(def ^:private SCENE_FRONTMATTER_KEYS
  [:id :start-id :end-id :started-at :ended-at :seal-reason :gist :routine :continues])

(defn episodes-root [root]
  (str root "/episodes"))

(defn crew-dir [root crew]
  (str (episodes-root root) "/" (name crew)))

(defn- session-id-of
  "Nested layout key. Only an explicit :session-id nests the record under
   sessions/<crew>/<sid>/episodes/. :thread alone is the legacy handle —
   lifecycle still names the backing session by episode id."
  [episode]
  (:session-id episode))

(defn nested-episode-path [root crew session-id episode-id]
  (str (impl/session-dir root (name crew) session-id) "/episodes/" episode-id))

(defn nested-episode-edn-path [root crew session-id episode-id]
  (str (nested-episode-path root crew session-id episode-id) "/episode.edn"))

(defn nested-scene-md-path [root crew session-id episode-id scene-id]
  (str (nested-episode-path root crew session-id episode-id) "/scenes/" scene-id ".md"))

(defn nested-current-path [root crew session-id episode-id]
  (str (nested-episode-path root crew session-id episode-id) "/current.ednl"))

(defn episode-path
  "Legacy episodes/<crew>/<eid>/ path. Prefer nested-episode-path for writes."
  [root crew episode-id]
  (str (crew-dir root crew) "/" episode-id))

(defn- episode-edn-path [root crew episode-id]
  (str (episode-path root crew episode-id) "/episode.edn"))

(defn- scene-md-path [root crew episode-id scene-id]
  (str (episode-path root crew episode-id) "/" scene-id ".md"))

(defn- write-edn! [fs* path value]
  (fs/mkdirs fs* (fs/parent path))
  (fs/spit fs* path (impl/write-edn value)))

(defn- keywordize-status [v]
  (cond
    (keyword? v) v
    (string? v)  (keyword v)
    :else        v))

(defn- read-edn [fs* path]
  (when (fs/exists? fs* path)
    (let [data (edn/read-string (fs/slurp fs* path))]
      (cond-> data
        (:status data) (update :status keywordize-status)))))

(defn- yaml-scalar [value]
  (cond
    (keyword? value) (name value)
    (string? value)  value
    (number? value)  value
    (true? value)    true
    (false? value)   false
    (nil? value)     nil
    :else            (str value)))

(defn- scene->frontmatter [scene]
  (into (array-map)
        (keep (fn [k]
                (when-let [v (get scene k)]
                  [(name k) (yaml-scalar v)]))
              SCENE_FRONTMATTER_KEYS)))

(defn- format-scene-md [scene]
  (let [fm   (scene->frontmatter scene)
        body (or (:text scene) "")]
    (str "---\n"
         (yaml/generate-string fm :dumper-options {:flow-style :block})
         "---\n"
         (when-not (str/blank? body)
           (str "\n" body
                (when-not (str/ends-with? body "\n") "\n"))))))

(defn- write-scene-md! [fs* path scene]
  (fs/mkdirs fs* (fs/parent path))
  (fs/spit fs* path (format-scene-md scene)))

;; Same split regex as isaac.config.loader / isaac.config.parse (md frontmatter).
(defn- split-frontmatter [content]
  (when-let [[_ frontmatter body]
             (re-matches #"(?s)\A---\r?\n(.*?)\r?\n---\r?\n?(.*)\z" content)]
    {:frontmatter frontmatter
     :body        (str/replace body #"^\r?\n" "")}))

(defn- keywordize-seal-reason [v]
  (cond
    (keyword? v) v
    (string? v)  (keyword v)
    :else        v))

(defn- parse-scene-md [content]
  (when-let [{:keys [frontmatter body]} (split-frontmatter content)]
    (let [data (yaml/parse-string frontmatter :keywords true)
          ;; File convention adds a trailing newline; strip it so :text
          ;; round-trips to the sealed scene value.
          text (str/replace (or body "") #"\r?\n\z" "")]
      (cond-> (select-keys data SCENE_FRONTMATTER_KEYS)
        (:seal-reason data)
        (update :seal-reason keywordize-seal-reason)
        true
        (assoc :text text)))))

(defn- list-dir-names [fs* dir]
  (if (fs/exists? fs* dir)
    (->> (or (fs/children fs* dir) [])
         (remove #(str/starts-with? % "."))
         sort
         vec)
    []))

(defn- scene-file?
  "True for scene payloads — .md preferred; legacy .edn still recognized for cleanup."
  [name]
  (and (or (str/ends-with? name ".md")
           (str/ends-with? name ".edn"))
       (not= name "episode.edn")))

(defn- nested-dir [root episode]
  (when-let [sid (session-id-of episode)]
    (nested-episode-path root (:crew episode) sid (:id episode))))

(defn- nested-edn [root episode]
  (when-let [sid (session-id-of episode)]
    (nested-episode-edn-path root (:crew episode) sid (:id episode))))

(defn- nested-episode-refs
  "Walk sessions/<crew>/*/episodes/<eid>/ even when the parent session has no
   session.edn (lifecycle nests under :session-id / :thread)."
  [fs* root crew]
  (let [crew-name (name crew)
        crew-dir  (impl/crew-sessions-dir root crew-name)]
    (mapcat (fn [sid]
              (map (fn [eid] {:session-id sid :id eid})
                   (list-dir-names fs* (str crew-dir "/" sid "/episodes"))))
            (list-dir-names fs* crew-dir))))

(defn write-episode!
  "Persist episode record + scene markdown files. Nested under
   sessions/<crew>/<session-id>/episodes/<eid>/ when :session-id is present;
   otherwise the legacy episodes/<crew>/<eid>/ tree."
  ([fs* root episode scenes]
   (write-episode! fs* root episode scenes {}))
  ([fs* root episode scenes {:keys [replace-scenes?]}]
   (let [crew (:crew episode)
         id   (:id episode)
         sid  (session-id-of episode)
         dir  (if sid
                (nested-episode-path root crew sid id)
                (episode-path root crew id))
         edn  (str dir "/episode.edn")
         scene-dir (if sid (str dir "/scenes") dir)]
     (fs/mkdirs fs* dir)
     (when replace-scenes?
       (doseq [name (list-dir-names fs* scene-dir)
               :when (scene-file? name)]
         (fs/delete fs* (str scene-dir "/" name)))
       (when-not sid
         (doseq [name (list-dir-names fs* dir)
                 :when (scene-file? name)]
           (fs/delete fs* (str dir "/" name)))))
     (write-edn! fs* edn episode)
     (doseq [scene scenes]
       (let [path (if sid
                    (nested-scene-md-path root crew sid id (:id scene))
                    (scene-md-path root crew id (:id scene)))]
         (write-scene-md! fs* path scene)))
     episode)))

(defn delete-episode!
  "Remove the episode directory (record + scenes) so list/read no longer see it."
  [fs* root crew episode-id]
  (impl/delete-tree! fs* (episode-path root crew episode-id))
  (doseq [{:keys [session-id]} (nested-episode-refs fs* root crew)
          :when session-id]
    (impl/delete-tree! fs* (nested-episode-path root crew session-id episode-id))))

(defn- read-nested-episode [fs* root crew episode-id]
  (some (fn [{:keys [session-id id]}]
          (when (= id episode-id)
            (read-edn fs* (nested-episode-edn-path root crew session-id episode-id))))
        (nested-episode-refs fs* root crew)))

(defn read-episode
  "Read episode.edn for crew/id, or nil. Nested layout first, then legacy."
  [fs* root crew episode-id]
  (or (read-nested-episode fs* root crew episode-id)
      (read-edn fs* (episode-edn-path root crew episode-id))))

(defn- scene-path-candidates [fs* root crew episode-id scene-id]
  (let [nested (keep (fn [{:keys [session-id id]}]
                       (when (= id episode-id)
                         (nested-scene-md-path root crew session-id episode-id scene-id)))
                     (nested-episode-refs fs* root crew))]
    (concat nested
            [(scene-md-path root crew episode-id scene-id)])))

(defn read-scene
  "Read a scene markdown file (YAML frontmatter + body as :text)."
  [fs* root crew episode-id scene-id]
  (some (fn [path]
          (when (fs/exists? fs* path)
            (parse-scene-md (fs/slurp fs* path))))
        (scene-path-candidates fs* root crew episode-id scene-id)))

(defn- list-scene-ids-in [fs* dir]
  (->> (list-dir-names fs* dir)
       (filter #(str/ends-with? % ".md"))
       (map #(subs % 0 (- (count %) 3)))
       sort
       vec))

(defn list-scene-ids
  "Scene file basenames (sans .md), sorted — chronological when ids are timestamped."
  [fs* root crew episode-id]
  (or (not-empty
        (some (fn [{:keys [session-id id]}]
                (when (= id episode-id)
                  (list-scene-ids-in fs* (str (nested-episode-path root crew session-id episode-id) "/scenes"))))
              (nested-episode-refs fs* root crew)))
      (list-scene-ids-in fs* (episode-path root crew episode-id))))

(defn list-scenes
  "Scenes in episode record order when :scene-ids is present; otherwise
   sorted by :started-at then id."
  [fs* root crew episode-id]
  (let [ep (read-episode fs* root crew episode-id)
        ids (or (not-empty (:scene-ids ep))
                (list-scene-ids fs* root crew episode-id))
        scenes (mapv #(read-scene fs* root crew episode-id %) ids)]
    (if (seq (:scene-ids ep))
      scenes
      (->> scenes
           (sort-by (fn [s] [(or (:started-at s) "") (:id s)]))
           vec))))

(defn- list-nested-episode-ids [fs* root crew]
  (vec (nested-episode-refs fs* root crew)))

(defn list-episodes
  "All episode records under a crew (nested sessions first, then leftover
   legacy episodes/<crew>/), sorted by id."
  [fs* root crew]
  (let [nested (->> (list-nested-episode-ids fs* root crew)
                    (keep (fn [{:keys [id session-id]}]
                            (when-let [ep (read-edn fs* (nested-episode-edn-path root crew session-id id))]
                              (assoc ep :id (or (:id ep) id) :session-id (or (:session-id ep) session-id))))))
        nested-ids (set (map :id nested))
        legacy (->> (list-dir-names fs* (crew-dir root crew))
                    (keep (fn [id]
                            (when-not (contains? nested-ids id)
                              (when-let [ep (read-edn fs* (episode-edn-path root crew id))]
                                (assoc ep :id (or (:id ep) id))))))
                    vec)]
    (->> (concat nested legacy)
         (sort-by :id)
         vec)))

(defn find-open-on-thread
  "Return the open episode whose :thread or :session-id equals thread, if any."
  [fs* root crew thread]
  (some (fn [ep]
          (when (and (= :open (:status ep))
                     (or (= thread (:thread ep))
                         (= thread (:session-id ep))))
            ep))
        (list-episodes fs* root crew)))

(defn find-by-migrated-from
  "Return the episode whose :migrated-from equals session-id, if any."
  [fs* root crew session-id]
  (some (fn [ep]
          (when (= session-id (or (:migrated-from ep) (:migrated_from ep)))
            ep))
        (list-episodes fs* root crew)))

(defn find-migrated-anywhere
  "Scan all crews for a migrated-from match. Returns [crew episode] or nil."
  [fs* root session-id]
  (let [crews (distinct
                (concat (list-dir-names fs* (episodes-root root))
                        (keep (fn [[_ loc]] (:crew loc)) (impl/scan-session-dirs fs* root))
                        (keep (fn [[_ row]] (:crew row)) (impl/read-index fs* root))))]
    (some (fn [crew]
            (when-let [ep (find-by-migrated-from fs* root crew session-id)]
              [crew ep]))
          crews)))
