(ns isaac.episodes.segment
  "Compaction-span windowing + LLM segmentation parse/validate/resolve.

   Segmentation LLM contract (line format):
     <first>-<last>: <gist>
   Bare `<n>:` is accepted as `<n>-<n>:`. Non-matching lines are ignored."
  (:require
    [clojure.string :as str]
    [isaac.drive.dispatch :as dispatch]
    [isaac.episodes.distill :as distill]
    [isaac.episodes.ids :as ids]
    [isaac.llm.api.protocol :as api]
    [isaac.logger :as log]))

(def DEFAULT_SIZE_CAP 80)

(def ^:private BOUNDARY_LINE
  #"(?i)^\s*(?:(\d+)\s*-\s*(\d+|end|present|last)|(\d+))\s*:\s*(?:\(cont\s+(\d+)\s*-\s*(\d+)\)\s*)?(.+?)\s*$")

(defn parse-scenes
  "Parse LLM text into a vector of {:start :end :gist} maps.

   Line format only — non-matching lines (preamble, fences, blanks) are
   ignored. An open-ended end (`end`/`present`/`last`) resolves to `n`
   when known; dropped otherwise (tiling still gates correctness).
   Returns [] when no boundary lines are found (caller treats empty as
   a parse failure against tiling)."
  ([text] (parse-scenes text nil))
  ([text n]
   (if-not (string? text)
     []
     (->> (str/split-lines text)
          (keep (fn [line]
                  (when-let [[_ a b solo cont-a cont-b gist] (re-matches BOUNDARY_LINE line)]
                    (let [start (Long/parseLong (or a solo))
                          end   (cond
                                  solo               start
                                  (re-matches #"\d+" b) (Long/parseLong b)
                                  :else              n)]
                      (when end
                        (let [gist     (str/trim gist)
                              routine? (str/starts-with? gist "~")
                              gist     (if routine?
                                         (str/trim (subs gist 1))
                                         gist)]
                          (cond-> {:start start
                                   :end   (long end)
                                   :gist  gist}
                            routine? (assoc :routine? true)
                            (and cont-a cont-b)
                            (assoc :continues-ordinals [(Long/parseLong cont-a)
                                                        (Long/parseLong cont-b)]))))))))
          vec))))

(defn valid-tiling?
  "True when scenes are sorted non-overlapping contiguous cover of 1..n."
  [n scenes]
  (boolean
    (and (pos? n)
         (seq scenes)
         (let [sorted (sort-by :start scenes)]
           (and (= 1 (:start (first sorted)))
                (= n (:end (last sorted)))
                (every? (fn [s]
                          (and (integer? (:start s))
                               (integer? (:end s))
                               (<= 1 (:start s) (:end s) n)))
                        sorted)
                (loop [expected 1
                       remaining sorted]
                  (if (empty? remaining)
                    true
                    (let [s (first remaining)]
                      (and (= expected (:start s))
                           (recur (inc (:end s)) (rest remaining)))))))))))

(defn resolve-ordinals
  "Map span-local :start/:end ordinals onto message :id values."
  [distilled-messages scenes]
  (let [ids (mapv :id distilled-messages)]
    (mapv (fn [s]
            (let [si (dec (:start s))
                  ei (dec (:end s))]
              (cond-> {:start-id  (nth ids si)
                       :end-id    (nth ids ei)
                       :gist      (:gist s)
                       :start-ord (:start s)
                       :end-ord   (:end s)}
                (:routine? s) (assoc :routine? true)
                (:continues-ordinals s) (assoc :continues-ordinals (:continues-ordinals s)))))
          (sort-by :start scenes))))

(defn- message-entry? [e]
  (= "message" (:type e)))

(defn compaction-spans
  "Split a transcript into compaction-bounded spans.

   Each span: {:messages [raw message entries...]
               :preceding-summary (string or nil)
               :index (0-based span index)}
   Compaction summaries ride the FOLLOWING span."
  ([transcript] (compaction-spans transcript DEFAULT_SIZE_CAP))
  ([transcript size-cap]
   (let [size-cap (or size-cap DEFAULT_SIZE_CAP)]
     (loop [remaining transcript
            current   []
            summary   nil
            spans     []
            idx       0]
       (if (empty? remaining)
         (cond-> spans
           (seq current)
           (conj {:messages current :preceding-summary summary :index idx}))
         (let [e (first remaining)]
           (cond
             (= "compaction" (:type e))
             (let [spans (cond-> spans
                           (seq current)
                           (conj {:messages current :preceding-summary summary :index idx}))
                   next-idx (if (seq current) (inc idx) idx)]
               (recur (rest remaining) [] (:summary e) spans next-idx))

             (message-entry? e)
             (let [current (conj current e)]
               (if (>= (count current) size-cap)
                 (recur (rest remaining) [] summary
                        (conj spans {:messages current :preceding-summary summary :index idx})
                        (inc idx))
                 (recur (rest remaining) current summary spans idx)))

             :else
             (recur (rest remaining) current summary spans idx))))))))

(defn- response-text [response]
  (or (get-in response [:message :content])
      (:content response)
      ""))

(defn- response-usage
  "Token counts from a chat response — ollama final-chunk shape first,
   generic :usage shapes as fallback."
  [response]
  (let [usage (or (:usage response) {})]
    {:in  (or (:prompt_eval_count response) (:input-tokens usage)
              (:input_tokens usage) (:prompt_tokens usage) 0)
     :out (or (:eval_count response) (:output-tokens usage)
              (:output_tokens usage) (:completion_tokens usage) 0)}))

(defn- sum-usage [a b]
  {:in  (+ (:in a 0) (:in b 0))
   :out (+ (:out a 0) (:out b 0))})

(defn- provider-error?
  "True when a chat response is a provider/API error map (not content)."
  [response]
  (boolean (and (map? response) (contains? response :error))))

(defn- provider-label [provider]
  (try
    (or (api/display-name provider) "provider")
    (catch Exception _ "provider")))

(defn- format-provider-error [provider response]
  (let [pname (provider-label provider)
        err   (or (:error response) :error)
        err-s (if (keyword? err) (clojure.core/name err) (str err))
        msg   (or (:message response) (:content response))]
    (if (str/blank? (str msg))
      (str pname ": " err-s)
      (str pname ": " err-s " — " msg))))

(def ^:private RAW_LOG_MAX 500)

(defn- truncate-raw [s]
  (let [s (str s)]
    (if (<= (count s) RAW_LOG_MAX)
      s
      (str (subs s 0 RAW_LOG_MAX) "…"))))

(defn- stream-scene-lines!
  "Streaming on-chunk handler: accumulate content deltas into acc*, and print
   each completed boundary line as it arrives (live scene visibility).
   Skips :done chunks — grover's final chunk repeats the full content.
   Reads ollama-shaped [:message :content] deltas and responses-API
   [:delta :text] chunks."
  [acc* line-buf* chunk]
  (when-not (:done chunk)
    (let [delta (or (get-in chunk [:message :content])
                    (get-in chunk [:delta :text])
                    "")]
      (when (seq delta)
        (swap! acc* str delta)
        (swap! line-buf* str delta)
        (loop []
          (let [s @line-buf*]
            (when-let [nl (str/index-of s "\n")]
              (let [line (subs s 0 nl)]
                (reset! line-buf* (subs s (inc nl)))
                (when (re-matches BOUNDARY_LINE line)
                  (println (str "    " (str/trim line)))))
              (recur))))))))

(defn segment-span!
  "Call the gist model once (with one retry on parse failure) to segment a span.
   Streams the response, printing each boundary line as the model writes it.

   Returns:
     {:ok scenes}
     {:error :flagged :raw text}
     {:error :provider-error :provider name :error-key k :message ...}"
  [provider model distilled-messages preceding-summary]
  (let [prompt (distill/format-span-prompt distilled-messages preceding-summary)
        request {:model model
                 :messages [{:role "user" :content prompt}]}
        attempt (fn []
                  (let [acc      (atom "")
                        line-buf (atom "")
                        response (dispatch/dispatch-chat-stream
                                   provider request
                                   (partial stream-scene-lines! acc line-buf))]
                    (if (provider-error? response)
                      {:error      :provider-error
                       :provider   (provider-label provider)
                       :error-key  (:error response)
                       :message    (format-provider-error provider response)
                       :response   response}
                      (let [tail   @line-buf
                            _      (when (re-matches BOUNDARY_LINE tail)
                                     (println (str "    " (str/trim tail))))
                            text   (let [a @acc]
                                     (if (seq a) a (response-text response)))
                            scenes (parse-scenes text (count distilled-messages))]
                        (if (and (seq scenes)
                                 (valid-tiling? (count distilled-messages) scenes))
                          {:ok    (resolve-ordinals distilled-messages scenes)
                           :raw   text
                           :usage (response-usage response)}
                          {:error :bad-parse :raw text
                           :usage (response-usage response)})))))
        first-try (attempt)]
    (cond
      (:ok first-try)
      first-try

      (= :provider-error (:error first-try))
      first-try

      :else
      (let [_ (println "    retrying span: unparseable segmentation output")
            second-try (attempt)
            usage (sum-usage (:usage first-try {}) (:usage second-try {}))]
        (cond
          (:ok second-try)
          (assoc second-try :usage usage)

          (= :provider-error (:error second-try))
          second-try

          :else
          (let [raw (or (:raw second-try) (:raw first-try) "")]
            (log/warn :episodes/segment-flagged
                      :raw (truncate-raw raw))
            {:error :flagged
             :raw   raw
             :usage usage}))))))

(defn- drop-marker-text?
  "True when distilled text is a tool-call marker (or blank)."
  [text]
  (or (str/blank? text)
      (re-matches #"\(tool [^)]*\)" (str/trim text))))

(defn- markers-only?
  "True when every distilled message in the slice is dropped or a tool marker."
  [slice]
  (every? (fn [m]
            (or (:dropped? m)
                (drop-marker-text? (:text m))))
          slice))

(defn- scene-covering-ord
  "First sealed scene whose span-local ordinals cover `ord`, or nil."
  [scenes-by-ord ord]
  (get scenes-by-ord ord))

(defn- resolve-continues
  "Map :continues-ordinals onto a target scene id within this batch.
   A (cont) pointing at a scene that is not in `sealable` is dropped
   with a warn log (the still-open trailing scene is the usual case)."
  [scene scenes-by-ord sealable]
  (if-let [[a _b] (:continues-ordinals scene)]
    (let [target (scene-covering-ord scenes-by-ord a)]
      (cond
        (and target (contains? sealable (:id target)))
        (assoc scene :continues (:id target))

        target
        (do
          (log/warn :episodes/cont-dropped
                    :scene (:id scene)
                    :target (:id target)
                    :reason :still-open)
          (dissoc scene :continues-ordinals))

        :else
        (do
          (log/warn :episodes/cont-dropped
                    :scene (:id scene)
                    :ordinals (:continues-ordinals scene)
                    :reason :unknown-target)
          (dissoc scene :continues-ordinals))))
    scene))

(defn seal-scenes
  "Build sealed scene records from resolved ordinal scenes + distilled msgs.
   Tilde-marked gists and marker-only slices seal with :routine true.
   Optional :continues-ordinals resolve to a scene id in this batch.
   `opts` may include `:leave-open` — that many trailing scenes stay
   unsealed (their ids are assigned so (cont) can detect them, then
   they are dropped from the return)."
  ([distilled-messages resolved-scenes seal-reason]
   (seal-scenes distilled-messages resolved-scenes seal-reason {}))
  ([distilled-messages resolved-scenes seal-reason {:keys [leave-open used-ids] :or {leave-open 0}}]
   (let [used*  (atom (set used-ids))
         drafts (mapv (fn [s]
                        (let [start-ord (:start-ord s)
                              end-ord   (:end-ord s)
                              slice     (subvec distilled-messages (dec start-ord) end-ord)
                              texts     (->> slice (keep :text) (str/join "\n"))
                              start-ts  (:timestamp (first slice))
                              end-ts    (:timestamp (last slice))
                              scene-id  (ids/unique-timestamped-id start-ts @used*)
                              routine?  (or (:routine? s) (markers-only? slice))]
                          (swap! used* conj scene-id)
                          (cond-> {:id          scene-id
                                   :start-id    (:start-id s)
                                   :end-id      (:end-id s)
                                   :start-ord   start-ord
                                   :end-ord     end-ord
                                   :started-at  start-ts
                                   :ended-at    end-ts
                                   :seal-reason seal-reason
                                   :text        texts
                                   :gist        (:gist s)}
                            routine? (assoc :routine true)
                            (:continues-ordinals s) (assoc :continues-ordinals (:continues-ordinals s)))))
                      resolved-scenes)
         n          (count drafts)
         open-n     (min (long leave-open) n)
         sealed     (vec (drop-last open-n drafts))
         sealable   (set (map :id sealed))
         by-ord     (into {}
                          (mapcat (fn [s]
                                    (map (fn [ord] [ord s])
                                         (range (:start-ord s) (inc (:end-ord s)))))
                                  drafts))
         sealed*    (mapv (fn [s]
                            (-> (resolve-continues s by-ord sealable)
                                (dissoc :continues-ordinals :start-ord :end-ord)))
                          sealed)]
     sealed*)))
