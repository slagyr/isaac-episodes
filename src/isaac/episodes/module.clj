(ns isaac.episodes.module
  (:require
    [isaac.module.protocol :as module]
    [isaac.recall.embedding]
    [isaac.recall.embedding.embeddings]
    [isaac.recall.embedding.ollama]
    [isaac.session.policy.episodes]))

(defn create-module []
  (module/module))
