(ns isaac.session.episodes.module
  (:require
    [isaac.foundation.module.protocol :as module]
    [isaac.session.episodes.context]
    [isaac.session.episodes.observer]
    [isaac.session.episodes.recall.embedding]
    [isaac.session.episodes.recall.embedding.embeddings]
    [isaac.session.episodes.recall.embedding.ollama]))

(defn create-module []
  (module/module))
