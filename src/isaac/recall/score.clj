(ns isaac.recall.score
  "Hybrid retrieval scoring: cosine, IDF lexical, recency, blended parts, match floor.")

(def default-weights
  {:text 1.0 :gist 1.0 :lex 1.0 :recency 0.5})

(def default-floor 0.47)

(def rare-term-lex 0.5)

(defn float-array?
  "True when v is a primitive float[]. SCI-safe (no reflective alength)."
  [v]
  (instance? (Class/forName "[F") v))

(def VECTOR_SCALE
  "Stored vectors are unit vectors quantized to ints at this scale
   (cosine error ≤ ~1e-4). Ints parse and multiply through compiled
   core fns; the scale divides out once per dot."
  10000.0)

(defn int-array?
  "True when v is a primitive int[]. SCI-safe."
  [v]
  (instance? (Class/forName "[I") v))

(defn quantize-vector
  "Unit float vector → primitive int[] scaled by VECTOR_SCALE."
  [v]
  (int-array (map (fn [x] (Math/round (* VECTOR_SCALE (double x))))
                  (or (seq v) []))))

(defn normalize-vector
  "Unit-normalize a numeric vector into a primitive float array. Zero → zeros."
  [v]
  (let [src (vec (or v []))
        n   (count src)
        xs  (float-array n)]
    (if (zero? n)
      xs
      (let [acc (loop [i 0 s 0.0]
                  (if (< i n)
                    (let [x (float (nth src i))]
                      (aset xs i x)
                      (recur (inc i) (+ s (* x x))))
                    s))
            norm (Math/sqrt acc)]
        (if (zero? norm)
          xs
          (do
            (dotimes [j n]
              (aset xs j (float (/ (nth xs j) norm))))
            xs))))))

(defn dot
  "Dot product of two numeric vectors (primitive arrays or seqs).
   Length mismatch / empty → 0.0. The per-element work rides compiled
   core fns (reduce/map/*) — interpreted loops here cost seconds at
   corpus scale (the 0.1.30 38s-load lesson)."
  [a b]
  (let [n (count a)]
    (if (or (zero? n) (not= n (count b)))
      0.0
      (double (reduce + 0.0 (map * a b))))))

(defn- unit-scale [v]
  (if (int-array? v) VECTOR_SCALE 1.0))

(defn cosine
  "Cosine similarity. Unit float arrays and/or quantized int arrays
   reduce to a scaled dot; generic seqs get the full normalized form.
   Empty/zero vectors → 0.0."
  [a b]
  (if (or (float-array? a) (int-array? a) (float-array? b) (int-array? b))
    (let [d (/ (dot a b) (* (unit-scale a) (unit-scale b)))]
      (cond
        (Double/isNaN d) 0.0
        (> d 1.0)        1.0
        (< d -1.0)       -1.0
        :else            d))
    (let [a (vec a)
          b (vec b)]
      (if (or (empty? a) (empty? b) (not= (count a) (count b)))
        0.0
        (let [dot (reduce + 0.0 (map * a b))
              na  (Math/sqrt (reduce + 0.0 (map #(* % %) a)))
              nb  (Math/sqrt (reduce + 0.0 (map #(* % %) b)))]
          (if (or (zero? na) (zero? nb))
            0.0
            (/ dot (* na nb))))))))

(defn recency
  "Additive recency channel: 0.5^(age-days / half-life). Age 0 → 1.0."
  [age-days half-life]
  (let [half (double (or half-life 30.0))]
    (if (or (zero? half) (neg? half))
      0.0
      (Math/pow 0.5 (/ (double (or age-days 0.0)) half)))))

(defn tokenize
  "Lowercased runs of alphanumerics with internal hyphens/dots kept intact.
   Lowercases the whole string once (compiled) rather than per token —
   per-token interop costs ~1s per query at corpus scale."
  [s]
  (->> (re-seq #"[a-z0-9]+(?:[-.][a-z0-9]+)*" (.toLowerCase ^String (str (or s ""))))
       distinct
       vec))

(defn token-set
  "Coerce a haystack (string or prebuilt token set) to a token set.
   Callers scoring many scenes build each scene's set ONCE per query."
  [s]
  (if (set? s) s (set (tokenize s))))

(defn idf
  "idf(t) = ln(1 + N/(df(t)+1))."
  [n df]
  (Math/log (+ 1.0 (/ (double n) (+ (double (or df 0)) 1.0)))))

(defn document-frequency
  "Live df: count of scenes whose token set contains each query term.
   `scenes` are haystack strings or prebuilt token sets."
  [scenes query-terms]
  (let [haystacks (mapv token-set scenes)]
    (into {}
          (map (fn [t]
                 [t (count (filter #(% t) haystacks))])
               query-terms))))

(defn lexical
  "IDF-weighted term overlap when :df/:n provided; else unweighted fraction.
   lex = Σ idf(matched query terms) / Σ idf(all query terms).
   `scene` is a haystack string or a prebuilt token set."
  ([query scene]
   (lexical query scene nil))
  ([query scene {:keys [df n]}]
   (let [q (tokenize query)]
     (if (empty? q)
       0.0
       (let [haystack (token-set scene)]
         (if (and df n)
           (let [denom (reduce + 0.0 (map #(idf n (get df % 0)) q))]
             (if (zero? denom)
               0.0
               (let [matched (filter haystack q)
                     numer   (reduce + 0.0 (map #(idf n (get df % 0)) matched))]
                 (double (/ numer denom)))))
           (let [hits (count (filter haystack q))]
             (double (/ hits (count q))))))))))

(defn matched-terms
  "Query terms found in the scene, in query order.
   `scene` is a haystack string or a prebuilt token set."
  [query scene]
  (let [haystack (token-set scene)]
    (vec (filter haystack (tokenize query)))))

(defn blend
  "Weighted sum of channels, normalized by Σw. Zero-weight total → 0.0.
   Channel keys on `scores`: :text :gist :lex :rec
   Channel keys on `weights`: :text :gist :lex :recency"
  [scores weights]
  (let [pairs [[:text :text]
               [:gist :gist]
               [:lex  :lex]
               [:rec  :recency]]
        weighted (map (fn [[sk wk]]
                        (let [w (double (or (get weights wk) 0.0))
                              s (double (or (get scores sk) 0.0))]
                          [(* w s) w]))
                      pairs)
        total-w (reduce + 0.0 (map second weighted))
        total-s (reduce + 0.0 (map first weighted))]
    (if (zero? total-w)
      0.0
      (/ total-s total-w))))

(defn- as-number [v]
  (cond
    (number? v) (double v)
    (string? v) (try (Double/parseDouble v) (catch Exception _ nil))
    :else       nil))

(defn resolve-weights
  "defaults → :recall config → CLI flag overrides (flags win)."
  [cfg flags]
  (let [cfg-w (or (get-in cfg [:recall :weights]) {})
        from-cfg (into {}
                       (keep (fn [[k dest]]
                               (when-let [v (as-number (get cfg-w k))]
                                 [dest v]))
                             {:text :text :gist :gist :lex :lex :recency :recency}))
        from-flags (into {}
                         (keep (fn [k]
                                 (when-let [v (as-number (get flags k))]
                                   [k v]))
                               [:text :gist :lex :recency]))]
    (merge default-weights from-cfg from-flags)))

(defn resolve-floor
  "defaults (0.47) → :recall {:floor-cos} → CLI :floor-cos. 0 disables."
  [cfg flags]
  (let [from-cfg (as-number (get-in cfg [:recall :floor-cos]))
        from-flag (as-number (get flags :floor-cos))]
    (cond
      (some? from-flag) from-flag
      (some? from-cfg)  from-cfg
      :else             default-floor)))

(defn match?
  "True when best-cos ≥ floor-cos OR lex ≥ 0.5 (rare-term anchor). Floor 0 disables."
  [{:keys [best-cos lex]} floor]
  (let [floor    (double (or floor 0.0))
        best-cos (double (or best-cos 0.0))
        lex      (double (or lex 0.0))]
    (or (zero? floor)
        (>= best-cos floor)
        (>= lex rare-term-lex))))
