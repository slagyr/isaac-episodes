(ns isaac.episodes.crew-spec
  (:require
    [isaac.config.loader :as loader]
    [isaac.episodes.crew :as sut]
    [speclj.core :refer :all]))

(def cfg {:defaults {:frequencies {:crew "ops"}}})

(describe "isaac.episodes.crew"

  (it "uses the entity's own crew when named"
    (should= "tars" (sut/resolve-id "tars" cfg)))

  (it "falls back to the operator's default crew"
    (should= "ops" (sut/resolve-id nil cfg)))

  (it "treats a blank crew as unnamed"
    (should= "ops" (sut/resolve-id "  " cfg)))

  (it "reads the live config when no cfg is passed"
    (with-redefs [loader/snapshot (fn [_] cfg)]
      (should= "ops" (sut/resolve-id nil))
      (should= "ops" (sut/resolve-id nil nil))))

  (it "is nil — never \"main\" — when nothing names a crew"
    (with-redefs [loader/snapshot (fn [_] nil)]
      (should-be-nil (sut/resolve-id nil))
      (should-be-nil (sut/resolve-id nil {})))))
