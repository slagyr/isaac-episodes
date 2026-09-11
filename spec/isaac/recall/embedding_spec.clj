(ns isaac.recall.embedding-spec
  (:require
    [clojure.string :as str]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.http :as llm-http]
    [isaac.recall.embedding :as sut]
    [isaac.recall.embedding.cli :as cli]
    [isaac.recall.embedding.ollama :as ollama]
    [isaac.recall.embedding.protocol :as protocol]
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

  (context "GroverEmbedder"
    (it "embeds a batch in order"
      (let [e (sut/make-grover "mini-embed")]
        (should= [[5 532 104 111] [3 312 99 116]]
                 (protocol/embed e ["hello" "cat"]))))
    )

  (context "resolve-embedder"
    (it "returns nil when :embedding is absent"
      (should-be-nil (sut/resolve-embedder {})))

    (it "returns a grover embedder for provider grover"
      (let [e (sut/resolve-embedder {:embedding {:source :provider
                                                 :provider "grover"
                                                 :model "mini-embed"}})]
        (should-not-be-nil e)
        (should= [[5 532 104 111]] (protocol/embed e ["hello"]))))

    (it "embed-texts reports no-embedding when unconfigured"
      (let [r (sut/embed-texts {} ["hello"])]
        (should= :no-embedding (:error r))
        (should (re-find #":embedding" (:message r)))))
    )

  (context "ollama adapter via grover simulation"
    (before (grover/clear-provider-requests!)
            (llm-http/clear-outbound-requests!))

    (it "POSTs /api/embed and returns grover vectors under simulation"
      (let [e (ollama/make "grover:ollama"
                           {:base-url "http://localhost:11434"
                            :model "nomic-embed-text"
                            :simulate-provider "ollama"
                            :api-key "grover"})
            vectors (protocol/embed e ["hello"])]
        (should= [[5 532 104 111]] vectors)
        (let [req (grover/last-provider-request)]
          (should (str/ends-with? (:url req) "/api/embed"))
          (should= "nomic-embed-text" (get-in req [:body :model]))
          (should= ["hello"] (get-in req [:body :input])))))
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
