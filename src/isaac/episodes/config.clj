(ns isaac.episodes.config
  (:require
    [clojure.string :as str]
    [isaac.config.schema-base :as schema-base]))

(defn check-embedding-provider [{:keys [config]}]
  (let [embedding (:embedding config)]
    (if-not (and (map? embedding)
                 (= "provider" (schema-base/->id (:source embedding)))
                 (some? (:provider embedding)))
      {:errors []}
      (let [providers (requiring-resolve 'isaac.llm.providers/known-providers)
            template  (requiring-resolve 'isaac.llm.providers/template)
            provider  (schema-base/->id (:provider embedding))
            templates (set (map schema-base/->id (providers)))
            user-ids  (set (map schema-base/->id (keys (:providers config))))
            ok?       (or (= "grover" provider)
                          (contains? templates provider)
                          (contains? user-ids provider)
                          (and (str/starts-with? provider "grover:")
                               (template (subs provider 7))))]
        {:errors (if ok? [] [{:key "embedding.provider"
                              :value "references undefined provider"
                              :bad-value provider}])}))))
