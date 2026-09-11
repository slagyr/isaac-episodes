(ns isaac.recall.tools-spec
  (:require
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.recall.index :as index]
    [isaac.recall.tools :as sut]
    [speclj.core :refer [around before describe it should should-not should= with]]))

(def ^:private root "/tmp-recall-tools")

(def ^:private wine-scene
  {:id         "2026-03-01-1000-s1x1"
   :started-at "2026-03-01T10:00:00"
   :ended-at   "2026-03-01T10:05:00"
   :gist       "Wine pairing for pheasant"
   :text       "a light pinot noir suits roast pheasant"})

(def ^:private embed-cfg
  {:embedding {:source :provider :provider "grover" :model "mini-embed"}})

(describe "isaac.recall.tools"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (with mem (fs/mem-fs))

  (around [example]
    (nexus/-with-nested-nexus {:fs @mem :root root :config (atom embed-cfg)}
      (example)))

  (before
    (fs/mkdirs @mem root)
    (store/write-episode!
      @mem root
      {:id         "2026-03-01-1000-ab12"
       :crew       "cordelia"
       :status     :closed
       :scene-ids  [(:id wine-scene)]
       :started-at (:started-at wine-scene)
       :ended-at   (:ended-at wine-scene)}
      [wine-scene])
    (index/index-crew! @mem root "cordelia" embed-cfg {}))

  (it "logs :recall/scene with crew, scene, episode, and chars when the scene is found"
    (log/capture-logs
      (let [result (sut/scene-tool {"scene-id" "2026-03-01-1000-s1x1"
                                    "fs"       @mem
                                    "state_dir" root
                                    "crew"     "cordelia"})
            entry  (first (filter #(= :recall/scene (:event %)) @log/captured-logs))]
        (should= "a light pinot noir suits roast pheasant" (:result result))
        (should-not (nil? entry))
        (should= :info (:level entry))
        (should= "cordelia" (:crew entry))
        (should= "2026-03-01-1000-s1x1" (:scene entry))
        (should= "2026-03-01-1000-ab12" (:episode entry))
        (should= (count "a light pinot noir suits roast pheasant") (:chars entry)))))

  (it "logs :recall/scene-missing at warn when the scene is unknown"
    (log/capture-logs
      (let [result (sut/scene-tool {"scene-id" "no-such-scene"
                                    "fs"       @mem
                                    "state_dir" root
                                    "crew"     "cordelia"})
            entry  (first (filter #(= :recall/scene-missing (:event %)) @log/captured-logs))]
        (should (:isError result))
        (should-not (nil? entry))
        (should= :warn (:level entry))
        (should= "cordelia" (:crew entry))
        (should= "no-such-scene" (:scene entry)))))

  (it "logs :recall/search with crew, query-chars, hits, top, and floor"
    (log/capture-logs
      (let [result (sut/search-tool {"query"     "What wine pairs with pheasant?"
                                    "fs"        @mem
                                    "state_dir" root
                                    "crew"      "cordelia"})
            entry  (first (filter #(= :recall/search (:event %)) @log/captured-logs))]
        (should-not (nil? (:result result)))
        (should-not (nil? entry))
        (should= :info (:level entry))
        (should= "cordelia" (:crew entry))
        (should= (count "What wine pairs with pheasant?") (:query-chars entry))
        (should (pos? (long (:hits entry))))
        (should (number? (:top entry)))
        (should= 0.47 (:floor entry)))))
  )
