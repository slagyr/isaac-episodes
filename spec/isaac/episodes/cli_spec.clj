(ns isaac.episodes.cli-spec
  (:require
    [clojure.string :as str]
    [isaac.episodes.cli :as sut]
    [isaac.episodes.lifecycle :as lifecycle]
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
