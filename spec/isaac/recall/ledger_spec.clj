(ns isaac.recall.ledger-spec
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.recall.ledger :as sut]
    [speclj.core :refer [before describe it should should= with]]))

(def root "/tmp-recall-ledger")
(def hits (mapv (fn [n] {:scene-id (str "s" n) :episode-id "e" :score (- 30 n)
                         :gist-text (str "gist " n) :text 0.9}) (range 25)))

(describe "recall ledger"
  (with mem (fs/mem-fs))
  (before (fs/mkdirs @mem root))

  (it "is off by default"
    (sut/append! @mem root "cordelia" {} {:kind :search :query "wine" :hits hits :floor 0.47})
    (should (not (fs/exists? @mem (sut/path root "cordelia")))))

  (it "appends a complete line for each recall and caps candidates at twenty"
    (doseq [kind [:inject :search]]
      (sut/append! @mem root "cordelia" {:recall {:ledger true}}
                   {:kind kind :session "bistro-chat" :thread "bistro-chat" :lineage []
                    :query "pheasant wine" :floor 0.47 :top 8 :hits hits :injected ["s0"]}))
    (let [entries (mapv edn/read-string (str/split-lines (fs/slurp @mem (sut/path root "cordelia"))))]
      (should= [:inject :search] (mapv :kind entries))
      (should= 20 (count (:candidates (first entries))))
      (should= {:scene-id "s0" :episode "e" :score 30 :gist "gist 0"}
               (first (:candidates (first entries))))
      (should= ["s0"] (:injected (first entries)))
      (should= "pheasant wine" (:query (first entries)))
      (should (string? (:ts (first entries))))))

  (it "survives a failed append and warns only once"
    (log/capture-logs
      (with-redefs [fs/spit (fn [& _] (throw (java.io.IOException. "read only")))]
        (dotimes [_ 2]
          (should= nil (sut/append! @mem root "cordelia" {:recall {:ledger true}}
                                    {:kind :search :query "wine" :hits [] :floor 0.47}))))
      (should= 1 (count (filter #(= :recall.ledger/write-failed (:event %)) @log/captured-logs))))))
