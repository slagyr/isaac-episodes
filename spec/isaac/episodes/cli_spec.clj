(ns isaac.episodes.cli-spec
  (:require
    [clojure.string :as str]
    [isaac.cli.host :as host]
    [isaac.cli.registry :as registry]
    [isaac.config.api :as config-api]
    [isaac.episodes.cli :as sut]
    [isaac.episodes.lifecycle :as lifecycle]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "isaac.episodes.cli close"

  (it "reports closed, deleted, and failed outcomes instead of a bare closed 0"
    (with-redefs [sut/install! (fn [_] {:root "/r" :fs :fs :cfg {} :store :ss})
                  lifecycle/close-open-episodes!
                  (fn [_]
                    {:closed  0
                     :results [{:status :deleted :episode-id "empty-ep"}
                               {:status :error :episode-id "bad-ep" :message "unknown backing session"}]})]
      (let [out (with-out-str
                  (should= 0 (#'sut/run-close {:root "/r"} "cordelia")))]
        (should-not (re-find #"^closed 0 episodes$" (str/trim out)))
        (should-contain "deleted empty-ep" out)
        (should-contain "failed bad-ep: unknown backing session" out))))

  (it "still reports a successful close count when every episode closed"
    (with-redefs [sut/install! (fn [_] {:root "/r" :fs :fs :cfg {} :store :ss})
                  lifecycle/close-open-episodes!
                  (fn [_]
                    {:closed  1
                     :results [{:status :closed :episode {:id "live-ep"}}]})]
      (let [out (with-out-str
                  (should= 0 (#'sut/run-close {:root "/r"} "cordelia")))]
        (should-contain "closed 1 episode" out))))
  )

(describe "isaac.episodes.cli host"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "runs episodes --help via the embedded host without mutating ambient runtime"
    (registry/register! {:name "episodes" :hosted true :run-fn sut/run})
    (let [before-nexus (nexus/necho)
          before-memo  (config-api/process-memo-snapshot)
          out          (java.io.StringWriter.)
          err          (java.io.StringWriter.)
          exit         (host/run-embedded {:argv ["episodes" "--help"]
                                           :in   (java.io.StringReader. "")
                                           :out  out
                                           :err  err
                                           :env  {}
                                           :cwd  "/test/isaac"
                                           :root "/test/isaac"})]
      (should= 0 exit)
      (should-contain "Usage: isaac episodes" (str out))
      (should= before-nexus (nexus/necho))
      (should= before-memo (config-api/process-memo-snapshot))))
  )
