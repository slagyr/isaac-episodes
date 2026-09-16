(ns isaac.recall.embedding
  "Optional episodes-owned embedding API. Dispatches directly on
   `[:episodes :embedding :api]`; no chat-provider catalog or adapter object."
  (:require
    [isaac.recall.embedding.api :as api]))

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
  "Deterministic 4-dim integer vector for the built-in test API.
   Short strings: [char-count char-sum first last]. Longer topic-bearing
   exchanges get orthogonal axes so live-seal drift can fire in fixtures."
  [text]
  (let [s (or text "")]
    (cond
      (zero? (count s))        [0 0 0 0]
      (< (count s) 20)         (base-grover-vector s)
      (re-find WINE_TOPIC s)   [1 0 0 0]
      (re-find REGATTA_TOPIC s) [0 1 0 0]
      :else                    (base-grover-vector s))))

(def embed api/embed)

(defmethod api/embed :grover [_embedding-cfg texts]
  (mapv grover-vector texts))

(defn embed-texts
  "Embed texts from `[:episodes :embedding]`. Returns `{:vectors [...]}` or
   a no-embedding error when the optional capability is not configured."
  [cfg texts]
  (if-let [embedding-cfg (get-in cfg [:episodes :embedding])]
    {:vectors (embed embedding-cfg (mapv str texts))}
    {:error   :no-embedding
     :message "no embedding configured — set :episodes {:embedding {...}} in config/isaac.edn"}))
