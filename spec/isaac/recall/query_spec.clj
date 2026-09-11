(ns isaac.recall.query-spec
  (:require
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.recall.index :as index]
    [isaac.recall.query :as sut]
    [speclj.core :refer [before describe it should should-be-nil should= with]]))

(def ^:private root "/tmp-recall-query")

(defn- write-closed! [fs* crew episode-id scenes]
  (store/write-episode!
    fs* root
    {:id         episode-id
     :crew       crew
     :status     :closed
     :scene-ids  (mapv :id scenes)
     :started-at (:started-at (first scenes))
     :ended-at   (:ended-at (last scenes))}
    scenes))

(def ^:private cfg
  {:embedding {:source :provider :provider "grover" :model "mini-embed"}})

(describe "isaac.recall.query"

  (describe "heap-delta"
    (it "reports used-heap growth across the index read"
      (should= 1024 (sut/heap-delta 4096 5120)))

    (it "floors at zero when a GC shrank the heap mid-read"
      (should= 0 (sut/heap-delta 5120 4096))))

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (with mem (fs/mem-fs))

  (before
    (fs/mkdirs @mem root))

  (it "errors when the index file is missing"
    (let [r (sut/query @mem root "cordelia" "wine" cfg {})]
      (should= :no-index (:error r))
      (should (re-find #"no index for crew cordelia" (:message r)))
      (should (re-find #"isaac episodes index" (:message r)))))

  (it "errors when zero rows match the configured model"
    (write-closed! @mem "cordelia" "ep1"
                   [{:id "s1" :gist "wine" :text "pinot"
                     :started-at "2026-03-01T10:00:00"
                     :ended-at "2026-03-01T10:05:00"}])
    (index/index-crew! @mem root "cordelia" cfg {})
    (let [r (sut/query @mem root "cordelia" "wine"
                       {:embedding {:source :provider :provider "grover" :model "maxi-embed"}}
                       {})]
      (should= :no-rows (:error r))
      (should (re-find #"no rows for model maxi-embed" (:message r)))))

  (it "warns about stale rows when mixed models sit in the index"
    (write-closed! @mem "cordelia" "ep1"
                   [{:id "s1" :gist "wine" :text "pinot"
                     :started-at "2026-03-01T10:00:00"
                     :ended-at "2026-03-01T10:05:00"}])
    (index/index-crew! @mem root "cordelia" cfg {})
    (index/index-crew! @mem root "cordelia"
                       {:embedding {:source :provider :provider "grover" :model "maxi-embed"}}
                       {})
    (let [r (sut/query @mem root "cordelia" "wine"
                       {:embedding {:source :provider :provider "grover" :model "maxi-embed"}}
                       {})]
      (should-be-nil (:error r))
      (should (re-find #"2 stale rows \(mini-embed\)" (:warning r)))
      (should (re-find #"--rebuild" (:warning r)))))

  (it "ranks the matching scene first and reports channel scores"
    (write-closed! @mem "cordelia" "ep1"
                   [{:id "2026-03-01-1000-s1x1"
                     :gist "wine" :text "wine"
                     :started-at "2026-03-01T10:00:00"
                     :ended-at "2026-03-01T10:05:00"}
                    {:id "2026-03-01-1006-s2x2"
                     :gist "race" :text "dawn"
                     :started-at "2026-03-01T10:06:00"
                     :ended-at "2026-03-01T10:09:00"}])
    (index/index-crew! @mem root "cordelia" cfg {})
    (let [r (sut/query @mem root "cordelia" "wine" cfg {})
          hits (:hits r)]
      (should-be-nil (:error r))
      (should= 2 (count hits))
      (should= "2026-03-01-1000-s1x1" (:scene-id (first hits)))
      (should (< (Math/abs (- 1.0 (:text (first hits)))) 1.0e-4))
      (should (< (Math/abs (- 1.0 (:gist (first hits)))) 1.0e-4))
      (should= 1.0 (:lex (first hits)))
      (should= "wine" (:gist-text (first hits)))))

  (it "breaks ties by scene-id ascending when recency is zeroed"
    (write-closed! @mem "cordelia" "ep1"
                   [{:id "2026-01-10-1000-oldx"
                     :gist "grog" :text "grog"
                     :started-at "2026-01-10T11:00:00"
                     :ended-at "2026-01-10T12:00:00"}
                    {:id "2026-03-10-1100-newx"
                     :gist "grog" :text "grog"
                     :started-at "2026-03-10T10:00:00"
                     :ended-at "2026-03-10T11:00:00"}])
    (index/index-crew! @mem root "cordelia" cfg {})
    (let [r (sut/query @mem root "cordelia" "grog" cfg
                       {:weights {:recency 0}
                        :now     "2026-03-10T12:00:00"})]
      (should= ["2026-01-10-1000-oldx" "2026-03-10-1100-newx"]
               (mapv :scene-id (:hits r)))))

  (it "reports recency 0.25 for a scene two half-lives old"
    (write-closed! @mem "cordelia" "ep1"
                   [{:id "2026-01-10-1000-oldx"
                     :gist "grog" :text "grog"
                     :started-at "2026-01-10T11:00:00"
                     :ended-at "2026-01-10T12:00:00"}
                    {:id "2026-03-10-1100-newx"
                     :gist "grog" :text "grog"
                     :started-at "2026-03-10T10:00:00"
                     :ended-at "2026-03-10T11:00:00"}])
    (index/index-crew! @mem root "cordelia" cfg {})
    (let [r    (sut/query @mem root "cordelia" "grog" cfg
                          {:now "2026-03-10T12:00:00"})
          old  (first (filter #(= "2026-01-10-1000-oldx" (:scene-id %)) (:hits r)))]
      (should= 0.25 (:rec old))))

  (it "attaches matched query terms on hits that contain them"
    (write-closed! @mem "cordelia" "ep1"
                   [{:id "s1" :gist "wine" :text "wine"
                     :started-at "2026-03-01T10:00:00"
                     :ended-at "2026-03-01T10:05:00"}
                    {:id "s2" :gist "race" :text "dawn"
                     :started-at "2026-03-01T10:06:00"
                     :ended-at "2026-03-01T10:09:00"}])
    (index/index-crew! @mem root "cordelia" cfg {})
    (let [r    (sut/query @mem root "cordelia" "wine" cfg {})
          hit1 (first (:hits r))
          hit2 (second (:hits r))]
      (should= ["wine"] (:terms hit1))
      (should= [] (:terms hit2))))

  (it "serves lex on a rowless all-routine index and returns no hits for junk"
    (write-closed! @mem "cordelia" "ep1"
                   [{:id "s1" :gist "Watch drill" :text "drill log entry alpha-9k2f" :routine true
                     :started-at "2026-03-01T10:00:00" :ended-at "2026-03-01T10:05:00"}
                    {:id "s2" :gist "Test suite run" :text "routine harness sweep" :routine true
                     :started-at "2026-03-01T10:06:00" :ended-at "2026-03-01T10:09:00"}])
    (index/index-crew! @mem root "cordelia" cfg {})
    (let [junk (sut/query @mem root "cordelia" "pheasant" cfg {})
          hit  (sut/query @mem root "cordelia" "alpha-9k2f" cfg {})]
      (should-be-nil (:error junk))
      (should= [] (:hits junk))
      (should-be-nil (:error hit))
      (should= ["s1"] (mapv :scene-id (:hits hit)))
      (should= 1.0 (:lex (first (:hits hit))))
      (should= 0.0 (:text (first (:hits hit))))
      (should= 0.0 (:gist (first (:hits hit))))))

  (it "still errors :no-rows on model mismatch, not emptiness"
    (write-closed! @mem "cordelia" "ep1"
                   [{:id "s1" :gist "wine" :text "pinot"
                     :started-at "2026-03-01T10:00:00" :ended-at "2026-03-01T10:05:00"}])
    (index/index-crew! @mem root "cordelia" cfg {})
    (let [r (sut/query @mem root "cordelia" "wine"
                       {:embedding {:source :provider :provider "grover" :model "maxi-embed"}}
                       {})]
      (should= :no-rows (:error r))))

  (it "warns on a junk field and stays silent for a rare-term hit"
    (write-closed! @mem "cordelia" "ep1"
                   [{:id "s1" :gist "Reef charting" :text "soundings along the leeward passage"
                     :started-at "2026-03-01T10:00:00" :ended-at "2026-03-01T10:01:00"}
                    {:id "s2" :gist "Galley provisioning" :text "hardtack rations for the voyage"
                     :started-at "2026-03-01T10:02:00" :ended-at "2026-03-01T10:03:00"}
                    {:id "s3" :gist "Watch rotation" :text "night watch schedule dogged evenings"
                     :started-at "2026-03-01T10:04:00" :ended-at "2026-03-01T10:05:00"}
                    {:id "s4" :gist "Wine pairing" :text "a light pinot noir for the pheasant"
                     :started-at "2026-03-01T10:06:00" :ended-at "2026-03-01T10:07:00"}
                    {:id "s5" :gist "Signal flags" :text "two long flashes for the lighthouse"
                     :started-at "2026-03-01T10:08:00" :ended-at "2026-03-01T10:09:00"}])
    (index/index-crew! @mem root "cordelia" cfg {})
    (let [junk (sut/query @mem root "cordelia" "whoville" cfg
                          {:floor-cos 0.999})
          hit  (sut/query @mem root "cordelia" "lighthouse" cfg
                          {:weights {:lex 0}
                           :floor-cos 0.999})
          verbatim (sut/query @mem root "cordelia" "two long flashes for the lighthouse" cfg
                              {:floor-cos 0.999})]
      (should (re-find #"weak matches — nothing stands out \(best cos \d\.\d+\)" (:warning junk)))
      (should-be-nil (:error junk))
      (should= 5 (count (:hits junk)))
      (should-be-nil (:warning hit))
      (should (some #(= ["lighthouse"] (:terms %)) (:hits hit)))
      (should-be-nil (:warning verbatim))))
  )
