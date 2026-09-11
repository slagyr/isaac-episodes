(ns isaac.recall.embedding
  "Optional embedding seam: protocol + config resolution.

   Root-level `:embedding` is a discriminated union on `:source`. Absence is a
   legal Base/Remembering tier — not a validation error. Present configs resolve
   to an Embedder via `resolve-embedder`."
  (:require
    [clojure.string :as str]
    [isaac.config.resolve :as resolve]
    [isaac.llm.providers :as providers]
    [isaac.recall.embedding.ollama :as ollama]
    [isaac.recall.embedding.protocol :as protocol]))

(def Embedder protocol/Embedder)
(def embed protocol/embed)

;; region ----- Grover deterministic vectors -----

(def ^:private WINE_TOPIC
  #"(?i)\b(wine|pinot|pheasant|zinfandel|harvest)\b")

(def ^:private REGATTA_TOPIC
  #"(?i)\b(regatta|race|schedule|saturday|anchor(?:age)?|harbor|quay)\b")

(defn- base-grover-vector [s]
  [(count s)
   (long (reduce + 0 (map int s)))
   (int (first s))
   (int (last s))])

(defn grover-vector
  "Deterministic 4-dim integer vector for test/stub embedders.
   Short strings (the documented contract): [char-count char-sum first last].
   Longer topic-bearing exchanges get orthogonal axes so live-seal drift
   can fire in fixtures (wine vs regatta). Empty string → [0 0 0 0]."
  [text]
  (let [s (or text "")]
    (cond
      (zero? (count s)) [0 0 0 0]
      (< (count s) 20)  (base-grover-vector s)
      (re-find WINE_TOPIC s)    [1 0 0 0]
      (re-find REGATTA_TOPIC s) [0 1 0 0]
      :else (base-grover-vector s))))

(deftype GroverEmbedder [model]
  protocol/Embedder
  (embed [_ texts]
    (mapv grover-vector texts)))

(defn make-grover
  "Test/stub Embedder. `model` is retained for diagnostics only."
  ([] (make-grover "mini-embed"))
  ([model] (->GroverEmbedder (or model "mini-embed"))))

;; endregion ^^^^^ Grover deterministic vectors ^^^^^

;; region ----- Config resolution -----

(defn- ->id [value]
  (cond
    (keyword? value) (name value)
    (string? value)  value
    (nil? value)     nil
    :else            (str value)))

(defn- simulated-target [provider-id]
  (when (and (string? provider-id) (str/starts-with? provider-id "grover:"))
    (subs provider-id (count "grover:"))))

(defn- provider-embedder [cfg embedding]
  (let [provider-id (->id (:provider embedding))
        model       (or (:model embedding) "")]
    (cond
      (nil? provider-id)
      nil

      (= "grover" provider-id)
      (make-grover model)

      (simulated-target provider-id)
      (let [target (simulated-target provider-id)
            pcfg   (or (providers/grover-defaults target)
                       {:api               "ollama"
                        :base-url          "http://localhost:11434"
                        :auth              "none"
                        :simulate-provider target})]
        (ollama/make provider-id (assoc pcfg :model model)))

      :else
      (let [pcfg (merge (or (resolve/resolve-provider cfg provider-id) {})
                        {:model model})
            api  (->id (or (:api pcfg) "ollama"))]
        (case api
          "ollama" (ollama/make provider-id pcfg)
          (ollama/make provider-id (cond-> pcfg
                                     (nil? (:base-url pcfg))
                                     (assoc :base-url "http://localhost:11434"))))))))

(defn resolve-embedder
  "Return an Embedder for `cfg`, or nil when `:embedding` is absent/blank.
   Does not throw on missing config — callers degrade with a helpful message."
  [cfg]
  (when-let [embedding (:embedding cfg)]
    (when (map? embedding)
      (let [source (keyword (->id (:source embedding)))]
        (case source
          :provider (provider-embedder cfg embedding)
          nil)))))

(defn embed-texts
  "Resolve embedder from `cfg` and embed `texts`. Returns
   `{:vectors [...]}` or `{:error :no-embedding :message ...}`."
  [cfg texts]
  (if-let [embedder (resolve-embedder cfg)]
    {:vectors (protocol/embed embedder (mapv str texts))}
    {:error   :no-embedding
     :message "no embedding configured — set :embedding in config/isaac.edn"}))

;; endregion ^^^^^ Config resolution ^^^^^
