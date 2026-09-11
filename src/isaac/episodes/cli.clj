(ns isaac.episodes.cli
  "isaac episodes — migrate sessions into closed episode/scene stores."
  (:require
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.agent.config.runtime :as runtime]
    [isaac.cli.api :as cli-api]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.episodes.layout :as layout]
    [isaac.episodes.lifecycle :as lifecycle]
    [isaac.episodes.migrate :as migrate]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.recall.index :as recall-index]
    [isaac.session.store.spi :as session-store]))

(def option-spec
  [["-h" "--help" "Show help"]
   [nil  "--force" "Re-run segmentation and replace sealed scenes"]
   [nil  "--crew CREW" "Crew whose sealed scenes to index"]
   [nil  "--rebuild" "Re-embed every scene, replacing existing index rows"]
   [nil  "--dry-run" "Print the migrate-layout plan without moving files"]])

(def ^:private help-text
  (str/join "\n"
            ["Usage: isaac episodes [subcommand] [options]"
             ""
             "Subcommands:"
             "  migrate-session <session-id>  Materialize a session as a closed episode"
              "  migrate-layout                Fold leftover sessions/episodes into the nested layout"
              "  close                         Close open episodes now (seal scenes)"
              "  list                          List a crew's episodes"
              "  index                         Embed sealed scenes into the per-crew retrieval index"
              ""
              "Options:"
              "  --force   Re-run the LLM pass and replace scenes in place"
              "  --crew    Crew for close/list/index"
              "  -h, --help  Show help"]))

(def ^:private migrate-help
  (str/join "\n"
            ["Usage: isaac episodes migrate-session <session-id> [options]"
             ""
             "Materialize a session as a closed episode under"
             "~/.isaac/episodes/<crew>/<episode-id>/ (episode.edn + scene .md files)."
             "Session files are not modified."
             ""
             "Options:"
             "  --force   Re-run segmentation even if already migrated"
             "  -h, --help  Show help"]))

(defn- print-err! [msg]
  (binding [*out* *err*]
    (println msg)))

(defn- install! [opts]
  (let [root-dir (or (:root opts) (root/default-root opts))
        fs*      (or (:fs opts) (fs/instance) (fs/real-fs))
        cfg      (loader/load-config! root-dir fs* "episodes cli")]
    (runtime/install! {:config cfg})
    {:root root-dir :fs fs* :cfg cfg :store (session-store/registered-store)}))

(def ^:private index-option-spec
  [["-h" "--help" "Show help"]
   [nil  "--crew CREW" "Crew whose sealed scenes to index"]
   [nil  "--rebuild" "Re-embed every scene, replacing existing index rows"]])

(def ^:private index-help
  (str/join "\n"
            ["Usage: isaac episodes index [options]"
             ""
             "Embed sealed scenes into the per-crew retrieval index"
             "(<root>/episodes/<crew>/index.edn + vectors.bin)."
             ""
             "Options:"
             "  --crew CREW  Crew to index (defaults to :defaults :crew, else all crews)"
             "  --rebuild    Drop existing rows and re-embed everything"
             "  -h, --help   Show help"]))

(defn- format-index-summary [result]
  (let [n (or (:new result) 0)
        skipped (or (:skipped-routine result) 0)]
    (if (pos? skipped)
      (str n " new rows, " skipped " routine scene"
           (when (not= 1 skipped) "s") " skipped")
      (str n " new rows"))))

(defn- default-crew [cfg]
  (get-in cfg [:defaults :crew]))

(defn- list-crews [fs* root]
  (let [dir (str root "/episodes")]
    (if (fs/exists? fs* dir)
      (->> (or (fs/children fs* dir) [])
           (remove #(str/starts-with? % "."))
           sort
           vec)
      [])))

(defn- run-index [opts crew rebuild?]
  (try
    (let [{:keys [root fs cfg]} (install! opts)
          crews (cond
                  (not (str/blank? crew)) [crew]
                  (default-crew cfg)      [(default-crew cfg)]
                  :else                   (list-crews fs root))]
      (if (empty? crews)
        (do (print-err! "no crews to index") 1)
        (loop [remaining crews]
          (if-let [c (first remaining)]
            (let [result (recall-index/index-crew! fs root c cfg {:rebuild? rebuild?})]
              (if (:error result)
                (do
                  (print-err! (or (:message result) "no embedding configured"))
                  1)
                (do
                  (println (format-index-summary result))
                  (recur (rest remaining)))))
            0))))
    (catch Exception e
      (print-err! (or (ex-message e) (.getMessage e)))
      1)))

(defn- format-list-row [ep]
  (let [n     (count (or (:scene-ids ep) []))
        scene (str n " scene" (when (not= 1 n) "s"))]
    (str (:id ep) "  " (name (or (:status ep) :unknown)) "  "
         (or (:thread ep) "-") "  " scene)))

(defn- run-list [opts crew]
  (try
    (let [{:keys [root fs cfg]} (install! opts)
          crew* (or (when-not (str/blank? crew) crew)
                    (default-crew cfg)
                    "main")
          eps   (store/list-episodes fs root crew*)]
      (doseq [ep eps]
        (println (format-list-row ep)))
      0)
    (catch Exception e
      (print-err! (or (ex-message e) (.getMessage e)))
      1)))

(defn- result-episode-id [r]
  (or (:episode-id r) (get-in r [:episode :id])))

(defn- result-error [r]
  (or (:error r) (:message r) "close failed"))

(defn- format-close-outcome [r]
  (let [id (or (result-episode-id r) "?")
        st (or (:status r) (get-in r [:episode :status]))]
    (case st
      :deleted (str "deleted " id)
      :error   (str "failed " id ": " (result-error r))
      :closed  (str "closed " id)
      :partial (str "partial " id)
      :resumed (str "resumed " id)
      (str (name (or st :unknown)) " " id))))

(defn- run-close [opts crew]
  (try
    (let [{:keys [root fs cfg store]} (install! opts)
          crew*  (or (when-not (str/blank? crew) crew)
                     (default-crew cfg)
                     "main")
          result (lifecycle/close-open-episodes!
                   {:fs fs :root root :crew crew* :session-store store :cfg cfg})
          results (or (:results result) [])
          n       (or (:closed result) 0)
          deleted (count (filter #(= :deleted (:status %)) results))
          failed  (count (filter #(= :error (:status %)) results))
          indexed (reduce + 0 (keep :indexed results))]
      (cond
        (and (zero? n) (or (pos? deleted) (pos? failed) (seq results)))
        (doseq [r results]
          (println (format-close-outcome r)))

        :else
        (println (str "closed " n " episode" (when (not= 1 n) "s"))))
      (when (pos? indexed)
        (println (str "indexed " indexed " rows")))
      0)
    (catch Exception e
      (print-err! (or (ex-message e) (.getMessage e)))
      1)))

(def ^:private migrate-layout-help
  (str/join "\n"
            ["Usage: isaac episodes migrate-layout [options]"
             ""
             "Move leftover sessions/<sid>/ directories under sessions/<crew>/<sid>/"
             "and fold leftover episodes/<crew>/<eid>/ into the nested session tree."
             "Idempotent. Existing ids are kept."
             ""
             "Options:"
             "  --dry-run  Print the plan without moving files"
             "  -h, --help  Show help"]))

(defn- run-migrate-layout [opts dry-run?]
  (try
    (let [{:keys [root fs]} (install! opts)]
      (layout/migrate-layout! {:fs fs :root root :dry-run? dry-run?}))
    (catch Exception e
      (print-err! (or (ex-message e) (.getMessage e)))
      1)))

(defn- run-migrate-session [opts session-id force?]
  (cond
    (str/blank? session-id)
    (do (println migrate-help) 1)

    :else
    (try
      (let [{:keys [root fs cfg store]} (install! opts)
            result (migrate/migrate-session-id!
                     {:fs fs :root root :cfg cfg :session-id session-id
                      :force? force? :session-store store})
            msg (:message result)
            exit (long (or (:exit result) 1))]
        (when msg
          (if (pos? exit)
            (print-err! msg)
            (println msg)))
        exit)
      (catch Exception e
        (print-err! (or (ex-message e) (.getMessage e)))
        1))))

(defn run
  "Dispatch episodes subcommands."
  [opts]
  (let [raw (or (:_raw-args opts) [])
        sub (first raw)
        rest-args (vec (rest raw))]
    (cond
      (or (nil? sub) (= "help" sub) (#{"-h" "--help"} sub))
      (do (println help-text) 0)

      (= "migrate-layout" sub)
      (let [{:keys [options errors]}
            (tools-cli/parse-opts rest-args option-spec)]
        (cond
          (seq errors)
          (do (doseq [e errors] (print-err! e)) 1)

          (:help options)
          (do (println migrate-layout-help) 0)

          :else
          (run-migrate-layout opts (boolean (:dry-run options)))))

      (= "migrate-session" sub)
      ;; Parse options anywhere in the tail so `migrate-session id --force` works
      ;; (tools.cli :in-order stops option parsing at the first non-option).
      (let [{:keys [options arguments errors]}
            (tools-cli/parse-opts rest-args option-spec)
            session-id (->> arguments (remove #(str/starts-with? % "-")) first)]
        (cond
          (seq errors)
          (do (doseq [e errors] (print-err! e)) 1)

          (:help options)
          (do (println migrate-help) 0)

          :else
          (run-migrate-session opts session-id (boolean (:force options)))))

      (= "index" sub)
      (let [{:keys [options errors]}
            (tools-cli/parse-opts rest-args index-option-spec)]
        (cond
          (seq errors)
          (do (doseq [e errors] (print-err! e)) 1)

          (:help options)
          (do (println index-help) 0)

          :else
          (run-index opts (:crew options) (boolean (:rebuild options)))))

      (= "close" sub)
      (let [{:keys [options errors]}
            (tools-cli/parse-opts rest-args option-spec)]
        (cond
          (seq errors)
          (do (doseq [e errors] (print-err! e)) 1)

          (:help options)
          (do (println help-text) 0)

          :else
          (run-close opts (:crew options))))

      (= "list" sub)
      (let [{:keys [options errors]}
            (tools-cli/parse-opts rest-args option-spec)]
        (cond
          (seq errors)
          (do (doseq [e errors] (print-err! e)) 1)

          (:help options)
          (do (println help-text) 0)

          :else
          (run-list opts (:crew options))))

      :else
      (do
        (print-err! (str "Unknown episodes subcommand: " sub))
        (println help-text)
        1))))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :episodes [_id opts]
  (run opts))

(defmethod cli-api/option-spec :episodes [_id]
  option-spec)

(defmethod cli-api/help :episodes [_id]
  help-text)

(defmethod cli-api/subcommands :episodes [_id]
  [{:name "migrate-session"
    :summary "Materialize a session as a closed episode"}
   {:name "migrate-layout"
    :summary "Fold leftover sessions/episodes into the nested layout"}
   {:name "close"
    :summary "Close open episodes now (seal scenes)"}
   {:name "list"
    :summary "List a crew's episodes"}
   {:name "index"
    :summary "Embed sealed scenes into the per-crew retrieval index"}])
