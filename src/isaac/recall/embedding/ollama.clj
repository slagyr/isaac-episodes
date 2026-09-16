(ns isaac.recall.embedding.ollama
  "Ollama embedding API — POST {base-url}/api/embed with {model, input}."
  (:require
    [isaac.llm.http :as llm-http]
    [isaac.recall.embedding.api :as api]))

(def ^:private default-headers {"Content-Type" "application/json"})
(def ^:private default-timeout 120000)
(def ^:private default-base-url "http://localhost:11434")

(defn- http-opts [cfg]
  (cond-> {:timeout (or (:timeout cfg) default-timeout)}
    (:session-key cfg)            (assoc :session-key (:session-key cfg))
    (:simulate-provider cfg)      (assoc :simulate-provider (:simulate-provider cfg))
    (:stream-idle-timeout-ms cfg) (assoc :stream-idle-timeout-ms (:stream-idle-timeout-ms cfg))))

(defn- ->vectors [response]
  (or (:embeddings response)
      (some-> response :embedding vector)
      []))

(defn embed-request!
  "POST /api/embed and return one vector per text."
  [cfg texts]
  (let [base (or (:base-url cfg) default-base-url)
        url  (str base "/api/embed")
        body {:model (:model cfg)
              :input (mapv str texts)}
        resp (llm-http/post-json! url default-headers body (http-opts cfg))]
    (if (:error resp)
      (throw (ex-info (or (:message resp) "embedding request failed")
                      (merge {:type :embedding/http-error} resp)))
      (->vectors resp))))

(defmethod api/embed :ollama [embedding-cfg texts]
  (embed-request! embedding-cfg texts))
