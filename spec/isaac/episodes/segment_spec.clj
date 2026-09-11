(ns isaac.episodes.segment-spec
  (:require
    [isaac.episodes.segment :as sut]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.provider :as llm-provider]
    [isaac.nexus :as nexus]
    [isaac.fs :as fs]
    [speclj.core :refer :all]))

(describe "isaac.episodes.segment"

  (context "parse-scenes (line format)"
    (it "reads boundary lines"
      (should= [{:start 1 :end 2 :gist "wine pairing for pheasant"}
                {:start 3 :end 4 :gist "regatta schedule"}]
               (sut/parse-scenes
                 (str "1-2: wine pairing for pheasant\n"
                      "3-4: regatta schedule\n"))))

    (it "accepts bare single ordinal as N-N"
      (should= [{:start 7 :end 7 :gist "solo note"}]
               (sut/parse-scenes "7: solo note")))

    (it "ignores preamble, fences, and blank lines"
      (should= [{:start 1 :end 2 :gist "wine pairing"}
                {:start 3 :end 4 :gist "regatta"}]
               (sut/parse-scenes
                 (str "Sure, here are the scenes:\n"
                      "```\n"
                      "1-2: wine pairing\n"
                      "\n"
                      "3-4: regatta\n"
                      "```\n"
                      "Hope that helps!\n"))))

    (it "returns empty when no boundary lines match"
      (should= [] (sut/parse-scenes "this is not a scene line at all")))

    (it "resolves an open-ended final boundary (end/present/last) to n"
      (should= [{:start 1 :end 2 :gist "wine"}
                {:start 3 :end 5 :gist "wrap up"}]
               (sut/parse-scenes "1-2: wine\n3-present: wrap up" 5))
      (should= [{:start 1 :end 4 :gist "all of it"}]
               (sut/parse-scenes "1-end: all of it" 4))
      (should= [{:start 2 :end 6 :gist "tail"}]
               (sut/parse-scenes "2-last: tail" 6)))

    (it "drops open-ended boundaries when n is unknown"
      (should= [] (sut/parse-scenes "3-present: wrap up")))

    (it "strips a leading ~ from the gist and marks routine?"
      (should= [{:start 1 :end 2 :gist "Loading the rigging checklist skill" :routine? true}
                {:start 3 :end 4 :gist "Diagnosed mainstay fraying: chafe guard mounted backwards"}]
               (sut/parse-scenes
                 (str "1-2: ~ Loading the rigging checklist skill\n"
                      "3-4: Diagnosed mainstay fraying: chafe guard mounted backwards\n"))))

    (it "reads an optional (cont a-b) mark after the ordinal"
      (should= [{:start 1 :end 2 :gist "Wine pairing for pheasant"}
                {:start 3 :end 4 :gist "Regatta scheduling"}
                {:start 5 :end 6 :gist "Dessert wine pairing" :continues-ordinals [1 2]}
                {:start 7 :end 8 :gist "Harbor anchorage"}]
               (sut/parse-scenes
                 (str "1-2: Wine pairing for pheasant\n"
                      "3-4: Regatta scheduling\n"
                      "5-6: (cont 1-2) Dessert wine pairing\n"
                      "7-8: Harbor anchorage\n"))))

    (it "keeps (cont) when the gist is also routine-marked"
      (should= [{:start 5 :end 6 :gist "Loading more wine notes" :routine? true :continues-ordinals [1 2]}]
               (sut/parse-scenes "5-6: (cont 1-2) ~ Loading more wine notes")))
    )

  (context "validate-tiling"
    (it "accepts exact 1..N cover"
      (should (sut/valid-tiling? 4 [{:start 1 :end 2} {:start 3 :end 4}])))

    (it "rejects gaps"
      (should-not (sut/valid-tiling? 4 [{:start 1 :end 1} {:start 3 :end 4}])))

    (it "rejects overlaps"
      (should-not (sut/valid-tiling? 3 [{:start 1 :end 2} {:start 2 :end 3}])))

    (it "rejects out of range"
      (should-not (sut/valid-tiling? 2 [{:start 1 :end 3}])))
    )

  (context "resolve-ordinals"
    (it "maps start/end ordinals onto message ids"
      (let [msgs [{:id "a"} {:id "b"} {:id "c"} {:id "d"}]
            scenes [{:start 1 :end 2 :gist "Wine"} {:start 3 :end 4 :gist "Regatta"}]]
        (should= [{:start-id "a" :end-id "b" :gist "Wine" :start-ord 1 :end-ord 2}
                  {:start-id "c" :end-id "d" :gist "Regatta" :start-ord 3 :end-ord 4}]
                 (sut/resolve-ordinals msgs scenes))))

    (it "carries routine? onto resolved scenes"
      (let [msgs [{:id "a"} {:id "b"} {:id "c"} {:id "d"}]
            scenes [{:start 1 :end 2 :gist "Loading skill" :routine? true}
                    {:start 3 :end 4 :gist "Diagnosed fraying"}]]
        (should= [{:start-id "a" :end-id "b" :gist "Loading skill" :start-ord 1 :end-ord 2 :routine? true}
                  {:start-id "c" :end-id "d" :gist "Diagnosed fraying" :start-ord 3 :end-ord 4}]
                 (sut/resolve-ordinals msgs scenes))))

    (it "carries continues-ordinals onto resolved scenes"
      (let [msgs [{:id "a"} {:id "b"} {:id "c"} {:id "d"} {:id "e"} {:id "f"}]
            scenes [{:start 1 :end 2 :gist "Wine"}
                    {:start 3 :end 4 :gist "Regatta"}
                    {:start 5 :end 6 :gist "Dessert" :continues-ordinals [1 2]}]]
        (should= [{:start-id "a" :end-id "b" :gist "Wine" :start-ord 1 :end-ord 2}
                  {:start-id "c" :end-id "d" :gist "Regatta" :start-ord 3 :end-ord 4}
                  {:start-id "e" :end-id "f" :gist "Dessert" :start-ord 5 :end-ord 6 :continues-ordinals [1 2]}]
                 (sut/resolve-ordinals msgs scenes))))
    )

  (context "compaction-spans"
    (it "splits on compaction boundaries and carries summary to the next span"
      (let [tx [{:type "session"}
                {:type "message" :id "1" :timestamp "t1" :message {:role "user" :content "a"}}
                {:type "message" :id "2" :timestamp "t2" :message {:role "assistant" :content "b"}}
                {:type "compaction" :summary "They planned the voyage provisions."}
                {:type "message" :id "3" :timestamp "t3" :message {:role "user" :content "c"}}
                {:type "message" :id "4" :timestamp "t4" :message {:role "assistant" :content "d"}}]
            spans (sut/compaction-spans tx)]
        (should= 2 (count spans))
        (should= ["1" "2"] (mapv :id (:messages (first spans))))
        (should-be-nil (:preceding-summary (first spans)))
        (should= ["3" "4"] (mapv :id (:messages (second spans))))
        (should= "They planned the voyage provisions."
                 (:preceding-summary (second spans)))))

    (it "yields one span when there is no compaction"
      (let [tx [{:type "message" :id "1" :message {:role "user" :content "a"}}
                {:type "message" :id "2" :message {:role "assistant" :content "b"}}]
            spans (sut/compaction-spans tx)]
        (should= 1 (count spans))
        (should= 2 (count (:messages (first spans))))))
    )

  (context "response-usage"
    (it "reads ollama final-chunk counts"
      (should= {:in 25 :out 12}
               (#'sut/response-usage {:prompt_eval_count 25 :eval_count 12})))

    (it "reads normalized kebab usage (responses API)"
      (should= {:in 7 :out 3}
               (#'sut/response-usage {:usage {:input-tokens 7 :output-tokens 3}})))

    (it "reads snake_case usage shapes"
      (should= {:in 5 :out 2}
               (#'sut/response-usage {:usage {:input_tokens 5 :output_tokens 2}}))))

  (context "stream-scene-lines!"
    (it "accumulates ollama-shaped message content deltas"
      (let [acc (atom "") buf (atom "")]
        (with-out-str
          (#'sut/stream-scene-lines! acc buf {:message {:content "1-2: topic\n"}}))
        (should= "1-2: topic\n" @acc)))

    (it "accumulates responses-API delta text chunks"
      (let [acc (atom "") buf (atom "")]
        (with-out-str
          (#'sut/stream-scene-lines! acc buf {:delta {:text "1-2: topic\n"}}))
        (should= "1-2: topic\n" @acc))))

  (context "segment-span!"
    (before
      (grover/install-test-fixture!)
      (grover/reset-queue!))

    (around [example]
      (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
        (example)))

    (it "returns ok for valid line-format output"
      (let [provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
            msgs [{:id "a" :role "user" :text "q" :dropped? false}
                  {:id "b" :role "assistant" :text "a" :dropped? false}]
            _ (grover/enqueue! [{:type "text" :content "1-2: topic"}])
            result (sut/segment-span! provider "gist" msgs nil)]
        (should (:ok result))
        (should= "a" (:start-id (first (:ok result))))
        (should= {:in 25 :out 12} (:usage result))))

    (it "surfaces provider errors without retry or flag"
      (let [provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
            msgs [{:id "a" :role "user" :text "q" :dropped? false}
                  {:id "b" :role "assistant" :text "a" :dropped? false}]
            _ (grover/enqueue! [{:type "error" :content "auth-missing"}
                                {:type "text" :content "1-2: should not be consumed"}])
            result (sut/segment-span! provider "gist" msgs nil)]
        (should= :provider-error (:error result))
        (should= "grover" (:provider result))
        (should= :llm-error (:error-key result))
        ;; second queued response must remain (no retry)
        (should= 1 (count @@#'grover/queue))))

    (it "retries once then flags with raw text"
      (let [provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
            msgs [{:id "a" :role "user" :text "q" :dropped? false}
                  {:id "b" :role "assistant" :text "a" :dropped? false}]
            _ (grover/enqueue! [{:type "text" :content "garbage"}
                                {:type "text" :content "still garbage"}])
            result (sut/segment-span! provider "gist" msgs nil)]
        (should= :flagged (:error result))
        (should= "still garbage" (:raw result))))
    )

  (context "seal-scenes"
    (it "writes :routine true when the gist was tilde-marked"
      (let [msgs [{:id "a" :timestamp "t1" :text "Load the skill." :dropped? false}
                  {:id "b" :timestamp "t2" :text "Loaded." :dropped? false}
                  {:id "c" :timestamp "t3" :text "Why fray?" :dropped? false}
                  {:id "d" :timestamp "t4" :text "Chafe guard backwards." :dropped? false}]
            resolved [{:start-id "a" :end-id "b" :gist "Loading the skill" :start-ord 1 :end-ord 2 :routine? true}
                      {:start-id "c" :end-id "d" :gist "Diagnosed fraying" :start-ord 3 :end-ord 4}]
            scenes (sut/seal-scenes msgs resolved :migrate)]
        (should= true (:routine (first scenes)))
        (should-not (contains? (second scenes) :routine))
        (should= "Loading the skill" (:gist (first scenes)))))

    (it "resolves in-batch (cont) ordinals to the target scene id"
      (let [msgs [{:id "a" :timestamp "t1" :text "Wine?" :dropped? false}
                  {:id "b" :timestamp "t2" :text "Pinot." :dropped? false}
                  {:id "c" :timestamp "t3" :text "Regatta?" :dropped? false}
                  {:id "d" :timestamp "t4" :text "Saturday." :dropped? false}
                  {:id "e" :timestamp "t5" :text "Dessert?" :dropped? false}
                  {:id "f" :timestamp "t6" :text "Late harvest." :dropped? false}]
            resolved [{:start-id "a" :end-id "b" :gist "Wine pairing" :start-ord 1 :end-ord 2}
                      {:start-id "c" :end-id "d" :gist "Regatta" :start-ord 3 :end-ord 4}
                      {:start-id "e" :end-id "f" :gist "Dessert wine" :start-ord 5 :end-ord 6 :continues-ordinals [1 2]}]
            scenes (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "ab12")]
                     (sut/seal-scenes msgs resolved :live))]
        (should= (:id (first scenes)) (:continues (nth scenes 2)))
        (should-not (contains? (first scenes) :continues))
        (should-not (contains? (second scenes) :continues))))

    (it "drops a (cont) that points at the still-open trailing scene"
      (let [msgs [{:id "a" :timestamp "t1" :text "Wine?" :dropped? false}
                  {:id "b" :timestamp "t2" :text "Pinot." :dropped? false}
                  {:id "c" :timestamp "t3" :text "Dessert?" :dropped? false}
                  {:id "d" :timestamp "t4" :text "Late harvest." :dropped? false}]
            resolved [{:start-id "a" :end-id "b" :gist "Wine pairing" :start-ord 1 :end-ord 2}
                      {:start-id "c" :end-id "d" :gist "Dessert wine" :start-ord 3 :end-ord 4 :continues-ordinals [1 2]}]
            scenes (sut/seal-scenes msgs resolved :live {:leave-open 1})]
        (should= 1 (count scenes))
        (should= "Wine pairing" (:gist (first scenes)))
        (should-not (contains? (first scenes) :continues))))

    (it "bumps colliding scene ids by 1ms so siblings stay unique"
      (let [msgs [{:id "a" :timestamp "2026-03-01T10:00:00" :text "Wine?" :dropped? false}
                  {:id "b" :timestamp "2026-03-01T10:00:00" :text "Pinot." :dropped? false}
                  {:id "c" :timestamp "2026-03-01T10:00:00" :text "Regatta?" :dropped? false}
                  {:id "d" :timestamp "2026-03-01T10:00:00" :text "Saturday." :dropped? false}]
            resolved [{:start-id "a" :end-id "b" :gist "Wine pairing" :start-ord 1 :end-ord 2}
                      {:start-id "c" :end-id "d" :gist "Regatta" :start-ord 3 :end-ord 4}]
            scenes (sut/seal-scenes msgs resolved :migrate)]
        (should= 2 (count scenes))
        (should= "20260301100000000" (:id (first scenes)))
        (should= "20260301100000001" (:id (second scenes)))
        (should= "Wine pairing" (:gist (first scenes)))
        (should= "Regatta" (:gist (second scenes)))))

    (it "bumps against already-used sibling ids from a prior span"
      (let [msgs [{:id "c" :timestamp "2026-03-01T10:00:00" :text "Regatta?" :dropped? false}
                  {:id "d" :timestamp "2026-03-01T10:00:00" :text "Saturday." :dropped? false}]
            resolved [{:start-id "c" :end-id "d" :gist "Regatta" :start-ord 1 :end-ord 2}]
            scenes (sut/seal-scenes msgs resolved :compaction
                                    {:used-ids #{"20260301100000000"}})]
        (should= "20260301100000001" (:id (first scenes)))
        (should= "Regatta" (:gist (first scenes)))))

    (it "auto-marks a markers-only slice as routine without LLM judgment"
      (let [msgs [{:id "a" :timestamp "t1" :text "Check the pump." :dropped? false}
                  {:id "b" :timestamp "t2" :text "(tool exec command=pump --status)" :dropped? false}
                  {:id "c" :timestamp "t3" :text nil :dropped? true}
                  {:id "d" :timestamp "t4" :text "Pump is nominal." :dropped? false}]
            resolved [{:start-id "a" :end-id "a" :gist "Bilge pump status request" :start-ord 1 :end-ord 1}
                      {:start-id "b" :end-id "c" :gist "Pump tooling" :start-ord 2 :end-ord 3}
                      {:start-id "d" :end-id "d" :gist "Pump nominal report" :start-ord 4 :end-ord 4}]
            scenes (sut/seal-scenes msgs resolved :migrate)]
        (should-not (contains? (first scenes) :routine))
        (should= true (:routine (second scenes)))
        (should-not (contains? (nth scenes 2) :routine))))
    )
  )
