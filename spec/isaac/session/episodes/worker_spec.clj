(ns isaac.session.episodes.worker-spec
  (:require
    [isaac.foundation.config.loader :as loader]
    [isaac.session.episodes.worker :as sut]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)))

(describe "isaac.session.episodes.worker"

  (with mem (fs/mem-fs))
  (with root "/isaac-root")

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:fs @mem :root @root}
      (example)))

  (it "uses the live config snapshot on a tick instead of reloading"
    (let [loads (atom 0)
          cfg   {:crew {"cordelia" {:observers [:episodes]}}}]
      (with-redefs [loader/snapshot     (fn [_] cfg)
                    loader/load-config! (fn [& _]
                                          (swap! loads inc)
                                          {})]
        (sut/tick! {:now  (Instant/parse "2026-03-01T10:10:00Z")
                    :fs   @mem
                    :root @root}))
      (should= 0 @loads)))

  (it "loads config from the isaac root when the snapshot is empty"
    (let [loads (atom 0)
          cfg   {:crew {"cordelia" {:observers [:episodes]}}}]
      (with-redefs [loader/snapshot     (fn [_] nil)
                    loader/load-config! (fn [& _]
                                          (swap! loads inc)
                                          cfg)]
        (sut/tick! {:now  (Instant/parse "2026-03-01T10:10:00Z")
                    :fs   @mem
                    :root @root}))
      (should= 1 @loads)))
  )
