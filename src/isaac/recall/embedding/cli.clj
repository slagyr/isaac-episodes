(ns isaac.recall.embedding.cli
  "isaac embed — debug/eval CLI for the optional embedding seam."
  (:require
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.api :as cli-api]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.recall.embedding :as embedding]))

(def option-spec
  [["-h" "--help" "Show help"]])

(def ^:private help-text
  (str/join "\n"
            ["Usage: isaac embed [options] [text ...]"
             ""
             "Embed text with the configured embedding provider"
             ""
             "Arguments:"
             "  text  Text to embed (one vector per argument)"
             ""
             "Options:"
             "  -h, --help  Show help"]))

(defn- load-cfg [opts]
  (let [root-dir (or (:root opts) (root/default-root opts))
        fs*      (or (fs/instance) (fs/real-fs))]
    (loader/load-config! root-dir fs* "embed cli")))

(defn- print-err! [msg]
  (binding [*out* *err*]
    (println msg)))

(defn- format-component [x]
  (if (== x (long x)) (long x) x))

(defn- format-vector
  "Whole-number components print as integers (grover's stub contract);
   real float embeddings keep their precision."
  [v]
  (pr-str (mapv format-component v)))

(defn run
  "Embed each argument as one text. Prints one vector line per argument.
   Exit 1 when :embedding is absent or the request fails."
  [opts]
  (let [raw   (or (:_raw-args opts) [])
        {:keys [options arguments errors]} (tools-cli/parse-opts raw option-spec :in-order true)]
    (cond
      (seq errors)
      (do (doseq [e errors] (print-err! e)) 1)

      (:help options)
      (do (println help-text) 0)

      (empty? arguments)
      (do (println help-text) 0)

      :else
      (try
        (let [cfg    (load-cfg opts)
              result (embedding/embed-texts cfg arguments)]
          (if (= :no-embedding (:error result))
            (do
              (print-err! (:message result))
              1)
            (do
              (doseq [v (:vectors result)]
                (println (format-vector v)))
              0)))
        (catch Exception e
          (print-err! (or (ex-message e) (.getMessage e)))
          1)))))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :embed [_id opts]
  (run opts))

(defmethod cli-api/option-spec :embed [_id]
  option-spec)

(defmethod cli-api/help :embed [_id]
  help-text)
