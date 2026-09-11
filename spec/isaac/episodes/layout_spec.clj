(ns isaac.episodes.layout-spec
  (:require
    [clojure.string :as str]
    [isaac.episodes.layout :as sut]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.recall.index :as recall-index]
    [isaac.session.store.impl-common :as impl]
    [speclj.core :refer :all]))

(def root "/layout-root")

(defn- spit-edn [fs* path m]
  (fs/mkdirs fs* (fs/parent path))
  (fs/spit fs* path (impl/write-edn m)))

(defn- spit-text [fs* path s]
  (fs/mkdirs fs* (fs/parent path))
  (fs/spit fs* path s))

(describe "isaac.episodes.layout migrate-layout"

  (with mem (fs/mem-fs))

  (around [example]
    (nexus/-with-nested-nexus {:fs @mem :root root}
      (example)))

  (it "dry-run prints the plan and leaves leftover trees in place"
    (let [fs* @mem]
      (spit-edn fs* (str root "/sessions/harbor-log/session.edn")
                {:id "harbor-log" :name "Harbor Log" :crew "main"})
      (spit-text fs* (str root "/sessions/harbor-log/current.ednl")
                 "{:type \"message\" :id \"m1\"}\n")
      (let [out (with-out-str
                  (should= 0 (sut/migrate-layout! {:fs fs* :root root :dry-run? true})))]
        (should (re-find #"sessions/harbor-log -> sessions/main/harbor-log \(chronicle\)" out))
        (should (re-find #"dry run: 0 moved" out)))
      (should (fs/exists? fs* (str root "/sessions/harbor-log/session.edn")))))

  (it "moves a chronicle leftover under its crew and stamps the policy"
    (let [fs* @mem]
      (spit-edn fs* (str root "/sessions/harbor-log/session.edn")
                {:id "harbor-log" :name "Harbor Log" :crew "main"})
      (spit-text fs* (str root "/sessions/harbor-log/current.ednl")
                 "{:type \"message\" :id \"m1\"}\n")
      (should= 0 (sut/migrate-layout! {:fs fs* :root root}))
      (let [edn (read-string (fs/slurp fs* (str root "/sessions/main/harbor-log/session.edn")))]
        (should= "main" (:crew edn))
        (should= :chronicle (:session-policy edn)))
      (should (fs/exists? fs* (str root "/sessions/main/harbor-log/current.ednl")))
      (should-not (fs/exists? fs* (str root "/sessions/harbor-log/session.edn")))))

  (it "folds a leftover episode backing session into the nested session tree"
    (let [fs* @mem]
      (spit-edn fs* (str root "/sessions/2026-03-01-1000-ab12/session.edn")
                {:id "2026-03-01-1000-ab12" :name "Lantern Room" :crew "cordelia"})
      (spit-text fs* (str root "/sessions/2026-03-01-1000-ab12/current.ednl")
                 "{:type \"message\" :id \"m2\"}\n")
      (spit-edn fs* (str root "/episodes/cordelia/2026-03-01-1000-ab12/episode.edn")
                {:id "2026-03-01-1000-ab12" :crew "cordelia" :status :closed
                 :thread "lantern-room" :scene-ids ["2026-03-01-1000-s1x1"]})
      (spit-text fs* (str root "/episodes/cordelia/2026-03-01-1000-ab12/2026-03-01-1000-s1x1.md")
                 "---\nid: 2026-03-01-1000-s1x1\ngist: wine\n---\n\npinot\n")
      (should= 0 (sut/migrate-layout! {:fs fs* :root root}))
      (let [sess (read-string (fs/slurp fs* (str root "/sessions/cordelia/lantern-room/session.edn")))
            ep   (read-string (fs/slurp fs* (str root "/sessions/cordelia/lantern-room/episodes/2026-03-01-1000-ab12/episode.edn")))]
        (should= "lantern-room" (:id sess))
        (should= "cordelia" (:crew sess))
        (should= :episodes (:session-policy sess))
        (should= :closed (:status ep))
        (should= "lantern-room" (:session-id ep)))
      (should (fs/exists? fs* (str root "/sessions/cordelia/lantern-room/episodes/2026-03-01-1000-ab12/current.ednl")))
      (should (fs/exists? fs* (str root "/sessions/cordelia/lantern-room/episodes/2026-03-01-1000-ab12/scenes/2026-03-01-1000-s1x1.md")))
      (should-not (fs/exists? fs* (str root "/sessions/2026-03-01-1000-ab12/session.edn")))))

  (it "is a no-op the second time"
    (let [fs* @mem]
      (spit-edn fs* (str root "/sessions/harbor-log/session.edn")
                {:id "harbor-log" :name "Harbor Log" :crew "main"})
      (sut/migrate-layout! {:fs fs* :root root})
      (let [out (with-out-str
                  (should= 0 (sut/migrate-layout! {:fs fs* :root root})))]
        (should (str/includes? out "nothing to migrate")))))
  )
