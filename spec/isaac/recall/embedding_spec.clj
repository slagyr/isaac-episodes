(ns isaac.recall.embedding-spec
  (:require
    [clojure.string :as str]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.http :as llm-http]
    [isaac.recall.embedding :as sut]
    [isaac.recall.embedding.cli :as cli]
    [isaac.recall.embedding.embeddings]
    [isaac.recall.embedding.ollama]
    [isaac.recall.score :as score]
    [speclj.core :refer :all]))

(describe "isaac.recall.embedding"

  (context "grover-vector"
    (it "maps hello to the documented 4-dim stub"
      (should= [5 532 104 111] (sut/grover-vector "hello")))

    (it "maps hi there"
      (should= [8 777 104 101] (sut/grover-vector "hi there")))

    (it "maps cat"
      (should= [3 312 99 116] (sut/grover-vector "cat")))

    (it "empty string is zeros"
      (should= [0 0 0 0] (sut/grover-vector "")))

    (it "distinguishes a wine exchange from a regatta exchange below the live-seal drift threshold"
      (let [wine    (sut/grover-vector (str "What wine pairs with pheasant?\nA light pinot noir."))
            regatta (sut/grover-vector (str "Now the regatta schedule\nFirst race is Saturday."))
            sim     (score/cosine
                      (score/quantize-vector (score/normalize-vector wine))
                      (score/quantize-vector (score/normalize-vector regatta)))]
        (should (< sim 0.999))))
    )

  (context "embedding api"
    (it "dispatches grover batches in order"
      (should= [[5 532 104 111] [3 312 99 116]]
               (sut/embed {:api "grover" :model "mini-embed"} ["hello" "cat"])))

    (it "reports no-embedding when episodes.embedding is unconfigured"
      (let [r (sut/embed-texts {} ["hello"])]
        (should= :no-embedding (:error r))
        (should (re-find #":episodes.*:embedding" (:message r)))))

    (it "rejects unknown apis instead of falling through to ollama"
      (should-throw Exception #"unknown embedding api: warp-drive"
        (sut/embed {:api "warp-drive" :model "mini-embed"} ["hello"])))
    )

  (context "ollama api via grover simulation"
    (before (grover/clear-provider-requests!)
            (llm-http/clear-outbound-requests!))

    (it "POSTs /api/embed and returns grover vectors under simulation"
      (let [vectors (sut/embed {:api "ollama"
                                :base-url "http://localhost:11434"
                                :model "nomic-embed-text"
                                :simulate-provider "ollama"}
                              ["hello"])
            req     (grover/last-provider-request)]
        (should= [[5 532 104 111]] vectors)
        (should (str/ends-with? (:url req) "/api/embed"))
        (should= "nomic-embed-text" (get-in req [:body :model]))
        (should= ["hello"] (get-in req [:body :input]))))
    )

  (context "embeddings api via grover simulation"
    (before (grover/clear-provider-requests!)
            (llm-http/clear-outbound-requests!))

    (it "POSTs /embeddings with bearer auth and returns data vectors"
      (let [vectors (sut/embed {:api "embeddings"
                                :base-url "https://api.openai.com/v1"
                                :api-key "sk-harbor-test"
                                :model "text-embedding-3-large"
                                :simulate-provider "openai"}
                              ["hello"])
            req     (grover/last-provider-request)]
        (should (vector? vectors))
        (should (str/ends-with? (:url req) "/embeddings"))
        (should= "Bearer sk-harbor-test" (get-in req [:headers "Authorization"]))
        (should= "text-embedding-3-large" (get-in req [:body :model]))
        (should= ["hello"] (get-in req [:body :input]))))
    )

  (context "cli vector formatting"

    (it "prints whole-number components as integers (grover stub contract)"
      (should= "[5 532 104 111]" (#'cli/format-vector [5 532 104 111]))
      (should= "[5 532]" (#'cli/format-vector [5.0 532.0])))

    (it "preserves float precision for real embeddings"
      (should= "[0.025516573 -0.21695295]"
               (#'cli/format-vector [0.025516573 -0.21695295])))
    )
  )
