(ns isaac.episodes.module-spec
  (:require
    [clojure.edn :as edn]
    [speclj.core :refer :all]))

(describe "episodes module manifest"
  (it "contributes episodes policy, tools, CLIs, and embedding check"
    (let [manifest (edn/read-string (slurp "resources/isaac-manifest.edn"))]
      (should (contains? (:isaac.agent/session-policy manifest) :episodes))
      (should= #{:recall/search :recall/scene} (set (keys (:isaac.agent/tools manifest))))
      (should= #{:embed :episodes :recall} (set (keys (:isaac/cli manifest))))
      (should (contains? (:isaac.config/check manifest) :embedding-provider)))))
