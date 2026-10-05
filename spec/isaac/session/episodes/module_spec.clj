(ns isaac.session.episodes.module-spec
  (:require
    [clojure.edn :as edn]
    [isaac.session.episodes.tools :as tools]
    [speclj.core :refer :all]))

(describe "episodes module manifest"
  (it "retains runtime session context for recall handlers"
    (should= true (:builtin? (tools/search-tool-factory {})))
    (should= true (:builtin? (tools/scene-tool-factory {}))))

  (it "declares the capped continuation setting for both global and crew episodes"
    (let [manifest (edn/read-string (slurp "resources/isaac-manifest.edn"))]
      (doseq [path [[:isaac.config/schema :episodes :schema :schema :recall :schema :continuation :schema :max-chars]
                    [:isaac.config/schema :crew :schema :value-spec :schema :episodes :schema
                     :recall :schema :continuation :schema :max-chars]]]
        (should= :int (get-in manifest (conj path :type)))
        (should (seq (get-in manifest (conj path :description)))))))

  (it "contributes episodes context mode, observer, tools, CLIs, and embedding APIs"
    (let [manifest (edn/read-string (slurp "resources/isaac-manifest.edn"))]
      (should (contains? (:isaac.agent/context-mode manifest) :episodes))
      (should= #{:episodes} (get-in manifest [:isaac.agent/context-mode :episodes :requires :observers]))
      (should (contains? (:isaac.agent/session-observer manifest) :episodes))
      (should= #{:recall/search :recall/scene} (set (keys (:isaac.agent/tools manifest))))
      (should= #{:embed :episodes :recall} (set (keys (:isaac/cli manifest))))
      (should= true (get-in manifest [:isaac/cli :embed :hosted]))
      (should= true (get-in manifest [:isaac/cli :episodes :hosted]))
      (should= true (get-in manifest [:isaac/cli :recall :hosted]))
      (should= #{:ollama :embeddings :grover}
               (set (keys (:isaac.session.episodes/embedding-api manifest))))
      (should-not (contains? (:isaac.config/schema manifest) :embedding))
      (should-not (contains? manifest :isaac.config/check)))))
