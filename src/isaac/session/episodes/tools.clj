(ns isaac.session.episodes.tools
  (:require
    [isaac.session.episodes.recall.tools :as recall]))

(defn search-tool-factory [_]
  {:description "Rank sealed episode scenes for a query. Returns gist lines with scene ids."
   :builtin?    true
   :parameters  {:type "object" :properties {"query" {:type "string"}} :required ["query"]}
   :handler     #'recall/search-tool})

(defn scene-tool-factory [_]
  {:description "Fetch one sealed scene's distilled text by scene id."
   :builtin?    true
   :parameters  {:type "object" :properties {"scene-id" {:type "string"}} :required ["scene-id"]}
   :handler     #'recall/scene-tool})
