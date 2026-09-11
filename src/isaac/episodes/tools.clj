(ns isaac.episodes.tools
  (:require
    [isaac.recall.tools :as recall]))

(defn search-tool-factory [_]
  {:description "Rank sealed episode scenes for a query. Returns gist lines with scene ids."
   :parameters  {:type "object" :properties {"query" {:type "string"}} :required ["query"]}
   :handler     #'recall/search-tool})

(defn scene-tool-factory [_]
  {:description "Fetch one sealed scene's distilled text by scene id."
   :parameters  {:type "object" :properties {"scene-id" {:type "string"}} :required ["scene-id"]}
   :handler     #'recall/scene-tool})
