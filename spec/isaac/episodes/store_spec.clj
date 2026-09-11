(ns isaac.episodes.store-spec
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.episodes.store :as sut]
    [isaac.fs :as fs]
    [speclj.core :refer :all]))

(describe "isaac.episodes.store"

  (with root "/tmp-episodes-root")
  (with mem (fs/mem-fs))

  (before
    ;; Fresh mem-fs each example — mem-fs is empty until mkdirs/spit.
    (fs/mkdirs @mem @root))

  (it "writes episode.edn and scene.md (YAML frontmatter + body) under crew/episode-id"
    (let [episode {:id "2026-01-02-0304-ab"
                   :crew "cordelia"
                   :migrated-from "quiet-regatta"
                   :status :closed
                   :scene-ids ["2026-01-02-0304-s1"]
                   :started-at "2026-01-02T03:04:05"
                   :ended-at "2026-01-02T03:05:00"}
          scene {:id "2026-01-02-0304-s1"
                 :gist "Wine"
                 :text "pinot noir"
                 :start-id "a"
                 :end-id "b"
                 :started-at "2026-01-02T03:04:05"
                 :ended-at "2026-01-02T03:04:30"
                 :seal-reason :migrate}
          mem (fs/mem-fs)]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root episode [scene])
      (let [ep-path (sut/episode-path @root "cordelia" "2026-01-02-0304-ab")
            edn-path (str ep-path "/episode.edn")
            sc-path (str ep-path "/2026-01-02-0304-s1.md")
            raw (fs/slurp mem sc-path)
            read-back (sut/read-scene mem @root "cordelia" "2026-01-02-0304-ab" "2026-01-02-0304-s1")]
        (should (fs/exists? mem edn-path))
        (should (fs/exists? mem sc-path))
        (should-not (fs/exists? mem (str ep-path "/2026-01-02-0304-s1.edn")))
        (should= "quiet-regatta" (:migrated-from (edn/read-string (fs/slurp mem edn-path))))
        (should (str/starts-with? raw "---\n"))
        (should (re-find #"(?m)^gist: " raw))
        (should (re-find #"pinot noir" raw))
        (should= "Wine" (:gist read-back))
        (should= "pinot noir" (:text read-back))
        (should= "a" (:start-id read-back))
        (should= "b" (:end-id read-back))
        (should= "2026-01-02-0304-s1" (:id read-back))
        (should= :migrate (:seal-reason read-back)))))

  (it "writes routine: true only when the scene is routine"
    (let [episode {:id "2026-01-02-0304-ab"
                   :crew "cordelia"
                   :status :closed
                   :scene-ids ["s-routine" "s-substantive"]}
          routine {:id "s-routine" :gist "Loading skill" :text "(tool exec)"
                   :start-id "a" :end-id "b"
                   :started-at "2026-01-02T03:04:05"
                   :ended-at "2026-01-02T03:04:10"
                   :seal-reason :migrate
                   :routine true}
          substantive {:id "s-substantive" :gist "Diagnosed fraying" :text "chafe guard"
                       :start-id "c" :end-id "d"
                       :started-at "2026-01-02T03:04:11"
                       :ended-at "2026-01-02T03:04:20"
                       :seal-reason :migrate}
          mem (fs/mem-fs)]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root episode [routine substantive])
      (let [r-raw (fs/slurp mem (str (sut/episode-path @root "cordelia" "2026-01-02-0304-ab") "/s-routine.md"))
            s-raw (fs/slurp mem (str (sut/episode-path @root "cordelia" "2026-01-02-0304-ab") "/s-substantive.md"))
            r-back (sut/read-scene mem @root "cordelia" "2026-01-02-0304-ab" "s-routine")
            s-back (sut/read-scene mem @root "cordelia" "2026-01-02-0304-ab" "s-substantive")]
        (should (re-find #"(?m)^routine: true$" r-raw))
        (should-not (re-find #"routine" s-raw))
        (should= true (:routine r-back))
        (should-not (contains? s-back :routine)))))

  (it "writes continues: <scene-id> only when the scene continues another"
    (let [episode {:id "2026-01-02-0304-ab"
                   :crew "cordelia"
                   :status :closed
                   :scene-ids ["s-wine" "s-dessert"]}
          wine {:id "s-wine" :gist "Wine pairing" :text "pinot"
                :start-id "a" :end-id "b"
                :started-at "2026-01-02T03:04:05"
                :ended-at "2026-01-02T03:04:10"
                :seal-reason :live}
          dessert {:id "s-dessert" :gist "Dessert wine" :text "late harvest"
                   :start-id "c" :end-id "d"
                   :started-at "2026-01-02T03:04:11"
                   :ended-at "2026-01-02T03:04:20"
                   :seal-reason :live
                   :continues "s-wine"}
          mem (fs/mem-fs)]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root episode [wine dessert])
      (let [w-raw (fs/slurp mem (str (sut/episode-path @root "cordelia" "2026-01-02-0304-ab") "/s-wine.md"))
            d-raw (fs/slurp mem (str (sut/episode-path @root "cordelia" "2026-01-02-0304-ab") "/s-dessert.md"))
            w-back (sut/read-scene mem @root "cordelia" "2026-01-02-0304-ab" "s-wine")
            d-back (sut/read-scene mem @root "cordelia" "2026-01-02-0304-ab" "s-dessert")]
        (should-not (re-find #"continues" w-raw))
        (should (re-find #"(?m)^continues: s-wine$" d-raw))
        (should-not (contains? w-back :continues))
        (should= "s-wine" (:continues d-back)))))

  (it "finds episode by migrated-from session id"
    (let [mem (fs/mem-fs)
          episode {:id "ep1" :crew "cordelia" :migrated-from "calm-lagoon"
                   :status :closed :scene-ids []}]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root episode [])
      (let [found (sut/find-by-migrated-from mem @root "cordelia" "calm-lagoon")]
        (should= "ep1" (:id found)))))

  (it "lists episodes for a crew sorted by id"
    (let [mem (fs/mem-fs)]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root {:id "a-ep" :crew "cordelia" :migrated-from "s1"
                                     :status :closed :scene-ids []} [])
      (sut/write-episode! mem @root {:id "b-ep" :crew "cordelia" :migrated-from "s2"
                                     :status :closed :scene-ids []} [])
      (should= ["a-ep" "b-ep"] (mapv :id (sut/list-episodes mem @root "cordelia")))))

  (it "replaces scenes on rewrite (force) — removes prior .md and legacy .edn"
    (let [mem (fs/mem-fs)
          ep {:id "epx" :crew "cordelia" :migrated-from "x" :status :closed
              :scene-ids ["old"]}
          old {:id "old" :gist "old" :text "old"}
          new {:id "new" :gist "new" :text "new"}
          dir (sut/episode-path @root "cordelia" "epx")]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root ep [old])
      ;; Stale legacy .edn left beside the new format must also be purged.
      (fs/spit mem (str dir "/stale.edn") "{:id \"stale\"}")
      (sut/write-episode! mem @root (assoc ep :scene-ids ["new"]) [new] {:replace-scenes? true})
      (should-not (fs/exists? mem (str dir "/old.md")))
      (should-not (fs/exists? mem (str dir "/old.edn")))
      (should-not (fs/exists? mem (str dir "/stale.edn")))
      (should (fs/exists? mem (str dir "/new.md")))
      (should= "new" (:gist (sut/read-scene mem @root "cordelia" "epx" "new")))))

  (it "list-scene-ids returns .md basenames"
    (let [mem (fs/mem-fs)
          ep {:id "epy" :crew "cordelia" :migrated-from "y" :status :closed
              :scene-ids ["s-a" "s-b"]}
          scenes [{:id "s-a" :gist "A" :text "alpha"}
                  {:id "s-b" :gist "B" :text "beta"}]]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root ep scenes)
      (should= ["s-a" "s-b"] (sut/list-scene-ids mem @root "cordelia" "epy"))
      (should= ["A" "B"] (mapv :gist (sut/list-scenes mem @root "cordelia" "epy")))))

  (it "finds the open episode on a thread"
    (let [mem (fs/mem-fs)]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root {:id "closed-ep" :crew "cordelia" :status :closed
                                     :thread "reef-chat" :scene-ids []} [])
      (sut/write-episode! mem @root {:id "open-ep" :crew "cordelia" :status :open
                                     :thread "reef-chat" :scene-ids []} [])
      (sut/write-episode! mem @root {:id "other-thread" :crew "cordelia" :status :open
                                     :thread "galley" :scene-ids []} [])
      (should= "open-ep" (:id (sut/find-open-on-thread mem @root "cordelia" "reef-chat")))
      (should-be-nil (sut/find-open-on-thread mem @root "cordelia" "missing"))))

  (it "keeps a thread-only episode under the legacy episodes/<crew>/<eid>/ tree"
    (let [mem (fs/mem-fs)
          ep  {:id "live-seal" :crew "cordelia" :status :open
               :thread "supper-chat" :scene-ids []}]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root ep [])
      (should (fs/exists? mem (str (sut/episode-path @root "cordelia" "live-seal") "/episode.edn")))
      (should-not (fs/exists? mem (sut/nested-episode-edn-path @root "cordelia" "supper-chat" "live-seal")))))

  (it "reads a nested episode even when the parent session has no session.edn"
    (let [mem (fs/mem-fs)
          ep  {:id "20260301100000000" :crew "cordelia" :status :open
               :thread "reef-chat" :session-id "reef-chat" :scene-ids []}]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root ep [])
      (let [read-back (sut/read-episode mem @root "cordelia" "20260301100000000")]
        (should= "reef-chat" (:thread read-back))
        (should= "reef-chat" (:session-id read-back))
        (should= :open (:status read-back)))
      (should= "20260301100000000"
               (:id (sut/find-open-on-thread mem @root "cordelia" "reef-chat")))))

  (it "deletes the episode directory so the record is gone"
    (let [mem (fs/mem-fs)
          ep  {:id "20260301100000000" :crew "cordelia" :status :open
               :thread "reef-chat" :scene-ids []}]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root ep [])
      (should (fs/exists? mem (str (sut/episode-path @root "cordelia" "20260301100000000") "/episode.edn")))
      (sut/delete-episode! mem @root "cordelia" "20260301100000000")
      (should-not (fs/exists? mem (str (sut/episode-path @root "cordelia" "20260301100000000") "/episode.edn")))
      (should-be-nil (sut/read-episode mem @root "cordelia" "20260301100000000"))
      (should= [] (sut/list-episodes mem @root "cordelia"))))
  )
