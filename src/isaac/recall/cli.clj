(ns isaac.recall.cli
  "isaac recall — rank a crew's indexed scenes against a query."
  (:require
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.agent.config.runtime :as runtime]
    [isaac.cli.api :as cli-api]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.recall.query :as query]
    [isaac.tool.memory :as memory]))

(def option-spec
  [["-h" "--help" "Show help"]
   [nil  "--crew CREW" "Crew whose index to query"]
   ["-n" "--top N" "Maximum hits to print"
    :parse-fn #(Integer/parseInt %)]
   [nil  "--w-text W" "Text-channel weight (parts)"
    :parse-fn #(Double/parseDouble %)]
   [nil  "--w-gist W" "Gist-channel weight (parts)"
    :parse-fn #(Double/parseDouble %)]
   [nil  "--w-lex W" "Lexical-channel weight (parts)"
    :parse-fn #(Double/parseDouble %)]
   [nil  "--w-recency W" "Recency-channel weight (parts)"
    :parse-fn #(Double/parseDouble %)]
   [nil  "--half-life DAYS" "Recency half-life in days"
    :parse-fn #(Double/parseDouble %)]
   [nil  "--floor-cos C" "Match-floor cosine (0 disables)"
    :parse-fn #(Double/parseDouble %)]])

(def ^:private help-text
  (str/join "\n"
            ["Usage: isaac recall [options] <query>"
             ""
             "Rank a crew's indexed scenes against a query"
             ""
             "Arguments:"
             "  query  Free-text query"
             ""
             "Options:"
             "  --crew CREW         Crew whose index to query"
             "  -n, --top N         Maximum hits to print"
             "  --w-text W          Text-channel weight (parts)"
             "  --w-gist W          Gist-channel weight (parts)"
             "  --w-lex W           Lexical-channel weight (parts)"
             "  --w-recency W       Recency-channel weight (parts)"
             "  --half-life DAYS    Recency half-life in days (default 30)"
             "  --floor-cos C       Match-floor cosine (default 0.47; 0 disables)"
             "  -h, --help          Show help"]))

(defn- print-err! [msg]
  (binding [*out* *err*]
    (println msg)))

(defn- install! [opts]
  (let [root-dir (or (:root opts) (root/default-root opts))
        fs*      (or (:fs opts) (fs/instance) (fs/real-fs))
        cfg      (loader/load-config! root-dir fs* "recall cli")]
    (runtime/install! {:config cfg})
    {:root root-dir :fs fs* :cfg cfg}))

(defn- format-score [n]
  (let [n (double (or n 0.0))]
    (if (== n (long n))
      (format "%.1f" n)
      (let [s (format "%.4f" n)]
        (-> s
            (str/replace #"0+$" "")
            (str/replace #"\.$" ".0"))))))

(defn- format-hit [idx hit]
  (str (inc idx) ". " (:scene-id hit)
       "  score " (format-score (:score hit))
       "  text " (format-score (:text hit))
       "  gist " (format-score (:gist hit))
       "  lex " (format-score (:lex hit))
       "  rec " (format-score (:rec hit))
       (when (seq (:terms hit))
         (str "  terms [" (str/join " " (:terms hit)) "]"))
       "\n  " (or (:gist-text hit) "")))

(defn- flag-weights [options]
  (cond-> {}
    (contains? options :w-text)    (assoc :text (:w-text options))
    (contains? options :w-gist)    (assoc :gist (:w-gist options))
    (contains? options :w-lex)     (assoc :lex (:w-lex options))
    (contains? options :w-recency) (assoc :recency (:w-recency options))))

(defn run
  "Rank indexed scenes. Exit 1 on missing index / model drift / no query."
  [opts]
  (let [raw (or (:_raw-args opts) [])
        {:keys [options arguments errors]} (tools-cli/parse-opts raw option-spec)]
    (cond
      (seq errors)
      (do (doseq [e errors] (print-err! e)) 1)

      (:help options)
      (do (println help-text) 0)

      (empty? arguments)
      (do (println help-text) 0)

      :else
      (try
        (let [{:keys [root fs cfg]} (install! opts)
              crew (or (:crew options)
                       (get-in cfg [:defaults :crew])
                       "main")
              q    (str/join " " arguments)
              result (query/query fs root crew q cfg
                                  (cond-> {:now       (memory/now)
                                           :weights   (flag-weights options)
                                           :half-life (:half-life options)
                                           :top       (:top options)}
                                    (contains? options :floor-cos)
                                    (assoc :floor-cos (:floor-cos options))))]
          (cond
            (:error result)
            (do (print-err! (:message result)) 1)

            :else
            (do
              (when-let [w (:warning result)]
                (print-err! w))
              (if (empty? (:hits result))
                (println "no hits")
                (do
                  (println (str "recall \"" q "\" (crew " crew
                                ", model " (:model result)
                                ", " (:scene-count result) " scenes)"))
                  (doseq [[i hit] (map-indexed vector (:hits result))]
                    (println (format-hit i hit)))))
              (when-let [t (:timings result)]
                (let [s (:index-stats result)
                      mb (fn [b] (format "%.1f" (/ (double (or b 0)) 1048576.0)))]
                  (println (str "timing: index " (:index-ms t) "ms"
                                " | scenes " (:scenes-ms t) "ms"
                                " | embed " (:embed-ms t) "ms"
                                " | score " (:score-ms t) "ms"))
                  (println (str "index: " (:rows s) " rows, "
                                (mb (:file-bytes s)) " MB file, ~"
                                (mb (:heap-bytes s)) " MB heap"))))
              0)))
        (catch Exception e
          (print-err! (or (ex-message e) (.getMessage e)))
          1)))))

(defmethod cli-api/run :recall [_id opts]
  (run opts))

(defmethod cli-api/option-spec :recall [_id]
  option-spec)

(defmethod cli-api/help :recall [_id]
  help-text)
