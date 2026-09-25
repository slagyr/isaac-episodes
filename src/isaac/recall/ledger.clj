(ns isaac.recall.ledger
  "Optional per-crew EDN-lines research record of recalls. Never interrupts recall."
  (:require
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.recall.index :as index]
    [isaac.tool.memory :as memory]))

(def CANDIDATE_LIMIT 20)
(defonce ^:private warned? (atom false))

(defn path [root crew]
  (str (index/recall-dir root crew) "/ledger.ednl"))

(defn- candidate [hit]
  {:scene-id (:scene-id hit)
   :episode  (:episode-id hit)
   :score    (:score hit)
   :gist     (:gist-text hit)})

(defn append!
  "Record the scored hits in rank order, independently of the shorter prompt shortlist."
  [fs* root crew cfg {:keys [hits floor] :as recall}]
  (when (true? (get-in cfg [:recall :ledger]))
    (try
      (let [dest (path root crew)
            candidates (->> hits
                            (filter #(or (zero? (double (or floor 0)))
                                         (>= (max (double (or (:text %) 0))
                                                  (double (or (:gist %) 0))) (double floor))
                                         (>= (double (or (:lex %) 0)) 0.5)))
                            (sort-by (juxt (comp - :score) :scene-id))
                            (take CANDIDATE_LIMIT)
                            (mapv candidate))
            entry (-> (select-keys recall [:kind :session :thread :lineage :query :floor :top :injected])
                      (assoc :ts (str (or (memory/now) (java.time.Instant/now)))
                             :candidates candidates
                             :injected (vec (or (:injected recall) []))))]
        (fs/mkdirs fs* (index/recall-dir root crew))
        (fs/spit fs* dest (str (pr-str entry) "\n") :append true)
        nil)
      (catch Exception e
        (when (compare-and-set! warned? false true)
          (log/warn :recall.ledger/write-failed :error (.getMessage e)))
        nil))))
