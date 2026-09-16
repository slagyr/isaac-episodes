(ns isaac.recall.embedding.embeddings
  "OpenAI-compatible embeddings API — POST {base-url}/embeddings."
  (:require
    [clojure.string :as str]
    [isaac.llm.http :as llm-http]
    [isaac.recall.embedding.api :as api]))

(def ^:private default-timeout 120000)
(def ^:private default-base-url "https://api.openai.com/v1")

(defn- trim-slash [s]
  (str/replace s #"/+$" ""))

(defn- http-opts [cfg]
  (cond-> {:timeout (or (:timeout cfg) default-timeout)}
    (:session-key cfg)       (assoc :session-key (:session-key cfg))
    (:simulate-provider cfg) (assoc :simulate-provider (:simulate-provider cfg))))

(defn- headers [cfg]
  (cond-> {"Content-Type" "application/json"}
    (:api-key cfg) (assoc "Authorization" (str "Bearer " (:api-key cfg)))))

(defn- ->vectors [response]
  (if-let [data (:data response)]
    (mapv :embedding data)
    (or (:embeddings response) [])))

(defn embed-request!
  "POST /embeddings and return data[].embedding in response order."
  [cfg texts]
  (let [url  (str (trim-slash (or (:base-url cfg) default-base-url)) "/embeddings")
        body {:model (:model cfg)
              :input (mapv str texts)}
        resp (llm-http/post-json! url (headers cfg) body (http-opts cfg))]
    (if (:error resp)
      (throw (ex-info (or (:message resp) "embedding request failed")
                      (merge {:type :embedding/http-error} resp)))
      (->vectors resp))))

(defmethod api/embed :embeddings [embedding-cfg texts]
  (embed-request! embedding-cfg texts))
