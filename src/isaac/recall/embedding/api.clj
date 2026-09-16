(ns isaac.recall.embedding.api)

(defmulti embed
  "Embed `texts` with an episodes embedding config map."
  (fn [embedding-cfg _texts]
    (some-> (:api embedding-cfg) name keyword)))

(defmethod embed :default [embedding-cfg _texts]
  (throw (ex-info (str "unknown embedding api: " (:api embedding-cfg))
                  {:type :embedding/unknown-api
                   :api  (:api embedding-cfg)})))
