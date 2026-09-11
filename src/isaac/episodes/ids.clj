(ns isaac.episodes.ids
  "Timestamped episode/scene ids: yyyyMMddHHmmssSSS (17 digits) minted from
   the clock at creation. The store bumps by 1 ms on a collision inside the
   same parent. Existing ids are kept as-is by migrate-layout."
  (:import
    (java.time Instant ZoneOffset)
    (java.time.format DateTimeFormatter)))

(def ^:private TS_FMT
  (DateTimeFormatter/ofPattern "yyyyMMddHHmmssSSS"))

(defn now-ms []
  (System/currentTimeMillis))

(defn chaos-suffix
  "Legacy no-op. Ids are 17 digits with no chaos suffix; kept so older specs
   that redef this var still load."
  ([] "")
  ([_n] ""))

(defn- parse-instant [ts]
  (cond
    (instance? Instant ts)
    ts

    (number? ts)
    (Instant/ofEpochMilli (long ts))

    (string? ts)
    (try
      (if (re-matches #"-?\d+" ts)
        (Instant/ofEpochMilli (Long/parseLong ts))
        ;; Accept "yyyy-MM-dd'T'HH:mm:ss" and full ISO-8601
        (let [normalized (if (re-find #"[zZ]|[+-]\d{2}:?\d{2}$" ts)
                           ts
                           (str ts "Z"))]
          (Instant/parse normalized)))
      (catch Exception _
        (try
          (-> (java.time.LocalDateTime/parse
                (subs ts 0 (min (count ts) 19))
                (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))
              (.toInstant ZoneOffset/UTC))
          (catch Exception _
            (Instant/ofEpochMilli (now-ms))))))

    :else
    (Instant/ofEpochMilli (now-ms))))

(defn timestamped-id
  "Build `yyyyMMddHHmmssSSS` (17 digits) from an Instant or message timestamp.
   Never minted from a message timestamp for episode/scene creation — callers
   pass the clock Instant. Collision bump is the store's job (same parent)."
  ([ts]
   (let [inst (parse-instant ts)]
     (.format TS_FMT (.atOffset inst ZoneOffset/UTC)))))

(defn unique-timestamped-id
  "Mint `timestamped-id` for `ts`, then bump by 1 ms until the id is not in
   `used` (a set of sibling ids under the same parent)."
  [ts used]
  (loop [inst (parse-instant ts)]
    (let [id (.format TS_FMT (.atOffset inst ZoneOffset/UTC))]
      (if (contains? used id)
        (recur (.plusMillis inst 1))
        id))))
