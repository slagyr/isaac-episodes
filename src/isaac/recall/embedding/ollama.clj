(ns isaac.recall.embedding.ollama
  "Ollama Embedder adapter — POST {base-url}/api/embed with {model, input}.

   Reuses isaac.llm.http so grover:ollama simulation and outbound request
   capture share the chat provider path."
  (:require
    [isaac.llm.http :as llm-http]
    [isaac.recall.embedding.protocol :as protocol]))

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
  "POST /api/embed. `texts` is a sequential of strings (ollama batch shape).
   Returns embedding vectors or throws ex-info on transport/API error."
  [cfg model texts]
  (let [base (or (:base-url cfg) default-base-url)
        url  (str base "/api/embed")
        body {:model model
              :input (mapv str texts)}
        resp (llm-http/post-json! url default-headers body (http-opts cfg))]
    (if (:error resp)
      (throw (ex-info (or (:message resp) "embedding request failed")
                      (merge {:type :embedding/http-error} resp)))
      (->vectors resp))))

(deftype OllamaEmbedder [provider-name cfg]
  protocol/Embedder
  (embed [_ texts]
    (embed-request! cfg (or (:model cfg) "") texts)))

(defn make
  "Construct an Ollama-shaped Embedder. `cfg` should carry :base-url, :model,
   and optionally :simulate-provider (grover path)."
  [provider-name cfg]
  (->OllamaEmbedder provider-name cfg))
