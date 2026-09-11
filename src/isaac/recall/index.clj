(ns isaac.recall.index
  "Per-crew retrieval index: <root>/sessions/<crew>/recall/index.edn + vectors.json.

   Metadata {:dims :model :scale :rows [{:session-id :episode-id :scene-id :kind :model} ...]}.
   Row order = vectors.json order. Vectors are unit-normalized then
   quantized to ints at score/VECTOR_SCALE — JSON ints load through
   compiled cheshire + int-array coercion (~100ms at corpus scale; every
   byte-level or per-element alternative measured seconds in bb).
   Legacy index.ednl / vectors.bin are ignored. Derived — always rebuildable."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.recall.embedding :as embedding]
    [isaac.recall.score :as score]
    [isaac.session.store.impl-common :as impl]))

(defn recall-dir [root crew]
  (str (impl/crew-sessions-dir root crew) "/recall"))

(defn index-path [root crew]
  (str (recall-dir root crew) "/index.edn"))

(defn vectors-path [root crew]
  (str (recall-dir root crew) "/vectors.json"))

(defn vectors-raw
  "Raw vectors.json contents, or nil when absent."
  [fs* root crew]
  (let [path (vectors-path root crew)]
    (when (fs/exists? fs* path)
      (fs/slurp fs* path))))

(defn- ->packed
  "Coerce a row vector to quantized ints (idempotent for int arrays)."
  [v]
  (cond
    (score/int-array? v)   v
    (score/float-array? v) (score/quantize-vector v)
    :else                  (score/quantize-vector (score/normalize-vector v))))

(defn write-index!
  "Overwrite packed index.edn + vectors.json. Vectors unit-normalized and
   quantized at write."
  [fs* root crew rows]
  (let [packed    (mapv (fn [row]
                          (assoc row :vector (->packed (:vector row))))
                        rows)
        dims      (if (seq packed)
                    (count (:vector (first packed)))
                    0)
        model     (or (:model (last packed)) "")
        meta      {:dims  dims
                   :model model
                   :scale (long score/VECTOR_SCALE)
                   :rows  (mapv #(select-keys % [:session-id :episode-id :scene-id :kind :model])
                                packed)}
        meta-path (index-path root crew)
        vec-path  (vectors-path root crew)]
    (fs/mkdirs fs* (or (fs/parent meta-path) "/"))
    (fs/spit fs* meta-path (impl/write-edn meta))
    (fs/spit fs* vec-path (json/generate-string (mapv (comp vec seq :vector) packed)))
    packed))

(defn read-index
  "Packed rows with :vector as a primitive int array, or [] when absent
   or unreadable (row/vector count mismatch, missing :scale — treated as
   no index so `episodes index` re-embeds). Legacy formats are ignored."
  [fs* root crew]
  (let [path (index-path root crew)]
    (if-not (fs/exists? fs* path)
      []
      (let [meta (edn/read-string (or (fs/slurp fs* path) "{}"))
            rows (vec (or (:rows meta) []))
            raw  (vectors-raw fs* root crew)
            vecs (when raw
                   (try (mapv int-array (json/parse-string raw))
                        (catch Exception _ nil)))]
        (if (or (nil? (:scale meta))
                (nil? vecs)
                (not= (count rows) (count vecs)))
          []
          (mapv (fn [row v] (assoc row :vector v)) rows vecs))))))

(defn row-key [row]
  [(:scene-id row) (:kind row) (:model row)])

(defn- configured-model [cfg]
  (get-in cfg [:embedding :model]))

(defn- scene-payloads [scene]
  [{:kind :gist :text (or (:gist scene) "")}
   {:kind :text :text (or (:text scene) "")}])

(defn- list-closed-scenes
  "All sealed scenes across closed/partial episodes for a crew.
   Returns [{:episode-id :scene ...}]."
  [fs* root crew]
  (mapcat (fn [ep]
            (let [eid (:id ep)
                  sid (or (:session-id ep) (:thread ep))]
              (map (fn [scene]
                     {:session-id sid :episode-id eid :scene scene})
                   (store/list-scenes fs* root crew eid))))
          (store/list-episodes fs* root crew)))

(def ^:private EMBED_BATCH 64)

(defn- embed-batched
  "Embed texts in EMBED_BATCH chunks — one corpus-sized request would blow
   the embedder's HTTP timeout. Prints progress when more than one batch.
   Returns a vector of vectors, or the error map from the failing batch."
  [cfg texts]
  (let [total (count texts)
        verbose? (> total EMBED_BATCH)]
    (loop [remaining texts
           acc []]
      (if (empty? remaining)
        acc
        (let [batch  (vec (take EMBED_BATCH remaining))
              result (embedding/embed-texts cfg batch)]
          (if (:error result)
            result
            (let [acc (into acc (:vectors result))]
              (when verbose?
                (println (str "  embedded " (count acc) "/" total)))
              (recur (drop EMBED_BATCH remaining) acc))))))))

(defn index-crew!
  "Embed sealed scenes into the per-crew packed index.

   Returns {:new N} on success, or {:error :no-embedding :message ...}.
   `--rebuild` (`:rebuild? true`) drops existing rows first."
  [fs* root crew cfg {:keys [rebuild?]}]
  (let [embedder-result (embedding/embed-texts cfg [""])]
    (if (:error embedder-result)
      embedder-result
      (let [model    (or (configured-model cfg) "")
            existing (if rebuild? [] (read-index fs* root crew))
            keyed    (into {} (map (juxt row-key identity) existing))
            pairs    (list-closed-scenes fs* root crew)
            skipped-routine (count (filter #(true? (get-in % [:scene :routine])) pairs))
            indexable (remove #(true? (get-in % [:scene :routine])) pairs)
            needed   (for [{:keys [session-id episode-id scene]} indexable
                           {:keys [kind text]} (scene-payloads scene)
                           :let [k [(:id scene) kind model]]
                           :when (not (contains? keyed k))]
                       {:session-id session-id
                        :episode-id episode-id
                        :scene-id   (:id scene)
                        :kind       kind
                        :model      model
                        :text       text})
            texts    (mapv :text needed)
            vectors  (when (seq texts) (embed-batched cfg texts))]
        (if (and (map? vectors) (:error vectors))
          vectors
          (let [fresh (mapv (fn [row v]
                              (-> row
                                  (dissoc :text)
                                  (assoc :vector (score/normalize-vector v))))
                            needed
                            (or vectors []))
                kept  (if rebuild? [] existing)
                rows  (vec (concat kept fresh))]
            (when (or rebuild? (seq fresh) (seq existing) (pos? skipped-routine))
              (write-index! fs* root crew rows))
            {:new (count fresh)
             :skipped-routine skipped-routine
             :rows rows}))))))
