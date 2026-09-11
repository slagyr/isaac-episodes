(ns isaac.recall.score-spec
  (:require
    [isaac.recall.score :as sut]
    [speclj.core :refer [context describe it should should= should-be-nil should-not]]))

(defn- approx [expected actual]
  (< (Math/abs (- (double expected) (double actual))) 1.0e-6))

(describe "isaac.recall.score"

  (context "cosine"
    (it "returns 1.0 for identical vectors"
      (should= 1.0 (sut/cosine [1.0 0.0] [1.0 0.0])))

    (it "returns 0.0 for orthogonal vectors"
      (should= 0.0 (sut/cosine [1.0 0.0] [0.0 1.0])))

    (it "returns 0.0 when either vector is empty or zero"
      (should= 0.0 (sut/cosine [] [1.0]))
      (should= 0.0 (sut/cosine [0.0 0.0] [1.0 2.0])))

    (it "scales quantized int vectors back to cosine"
      (let [u  (sut/normalize-vector [3.0 0.0 4.0])
            qv (sut/quantize-vector u)]
        (should (sut/int-array? qv))
        (should= [6000 0 8000] (vec qv))
        (should (approx 1.0 (sut/cosine u qv)))))

    (it "dots unit-normalized float arrays as cosine"
      (let [a (sut/normalize-vector [3.0 0.0 4.0])
            b (sut/normalize-vector [3.0 0.0 4.0])]
        (should (approx 1.0 (sut/dot a b)))
        (should (approx 1.0 (sut/cosine a b)))))
    )

  (context "normalize-vector"
    (it "unit-normalizes a 3-4-5 vector to 0.6/0.8"
      (let [v (vec (sut/normalize-vector [3.0 0.0 4.0]))]
        (should (approx 0.6 (nth v 0)))
        (should (approx 0.0 (nth v 1)))
        (should (approx 0.8 (nth v 2)))))

    (it "leaves a zero vector as zeros"
      (should= [0.0 0.0 0.0] (mapv double (sut/normalize-vector [0 0 0]))))
    )

  (context "recency"
    (it "is 1.0 at age 0"
      (should= 1.0 (sut/recency 0.0 30.0)))

    (it "is 0.25 at one half-life of 30 days over 60 days"
      (should= 0.25 (sut/recency 60.0 30.0)))

    (it "is 0.5 at one half-life"
      (should= 0.5 (sut/recency 30.0 30.0)))
    )

  (context "tokenize"
    (it "keeps internal hyphens and dots as one token"
      (should= ["chart-7x2b" "test"] (sut/tokenize "Chart-7x2b TEST")))

    (it "keeps dotted identifiers intact"
      (should= ["see" "index.edn"] (sut/tokenize "see index.edn")))
    )

  (context "idf"
    (it "is ln(1 + N/(df+1))"
      (should (approx (Math/log 1.75) (sut/idf 3 3)))
      (should (approx (Math/log 2.5) (sut/idf 3 1)))
      (should (approx (Math/log 4.0) (sut/idf 3 0))))
    )

  (context "lexical"
    (it "returns 1.0 when every query term appears in the scene"
      (should= 1.0 (sut/lexical "chart-7x2b" "resolved chart-7x2b by redrawing")))

    (it "returns 0.0 when no query term appears"
      (should= 0.0 (sut/lexical "chart-7x2b" "a light pinot noir")))

    (it "returns 0.5 when half the query terms appear"
      (should= 0.5 (sut/lexical "chart wine" "chart the reef")))

    (it "weights rare terms over common ones via live df"
      (let [scenes ["resolved chart-7x2b test failures in the passage suite"
                    "hardtack test rations for the voyage"
                    "night watch test schedule dogged evenings"]
            df     (sut/document-frequency scenes ["chart-7x2b" "test"])
            rare   (sut/lexical "chart-7x2b test" (first scenes) {:df df :n 3})
            common (sut/lexical "chart-7x2b test" (second scenes) {:df df :n 3})]
        (should= 3 (get df "test"))
        (should= 1 (get df "chart-7x2b"))
        (should (approx 1.0 rare))
        (should (approx (/ (sut/idf 3 3) (+ (sut/idf 3 3) (sut/idf 3 1))) common))))

    (it "dilutes unknown query terms via max idf in the denominator"
      (let [scenes ["hardtack test rations for the voyage"
                    "night watch test schedule dogged evenings"
                    "test soundings along the leeward passage"]
            df     (sut/document-frequency scenes ["whoville" "test"])
            score  (sut/lexical "whoville test" (first scenes) {:df df :n 3})]
        (should= 0 (get df "whoville"))
        (should (approx (/ (sut/idf 3 3) (+ (sut/idf 3 0) (sut/idf 3 3))) score))))

    (it "returns matched query terms in query order"
      (should= ["chart-7x2b" "test"]
               (sut/matched-terms "chart-7x2b test" "resolved chart-7x2b test failures")))
    )

  (context "blend"
    (it "normalizes the weighted sum by the weight total"
      (should= 0.25 (sut/blend {:text 1.0 :gist 0.0 :lex 0.0 :rec 0.0}
                               {:text 1.0 :gist 1.0 :lex 1.0 :recency 1.0})))

    (it "returns 0.0 when every weight is zero"
      (should= 0.0 (sut/blend {:text 1.0 :gist 1.0 :lex 1.0 :rec 1.0}
                              {:text 0.0 :gist 0.0 :lex 0.0 :rec 0.0})))
    )

  (context "default weights"
    (it "uses one part per channel except recency at half a part"
      (should= {:text 1.0 :gist 1.0 :lex 1.0 :recency 0.5}
               sut/default-weights))
    )

  (context "resolve-weights"
    (it "starts from hardcoded defaults"
      (should= {:text 1.0 :gist 1.0 :lex 1.0 :recency 0.5}
               (sut/resolve-weights {} {})))

    (it "overlays :recall config weights"
      (should= {:text 1.0 :gist 1.0 :lex 1.0 :recency 8.0}
               (sut/resolve-weights {:recall {:weights {:recency 8}}} {})))

    (it "lets CLI flags win over config"
      (should= {:text 1.0 :gist 1.0 :lex 1.0 :recency 1.0}
               (sut/resolve-weights {:recall {:weights {:recency 8}}}
                                    {:recency 1})))
    )

  (context "match floor"
    (it "defaults to 0.47"
      (should= 0.47 (sut/resolve-floor {} {})))

    (it "overlays :recall {:floor-cos} then CLI :floor-cos; 0 disables"
      (should= 0.0 (sut/resolve-floor {:recall {:floor-cos 0}} {}))
      (should= 0.999 (sut/resolve-floor {:recall {:floor-cos 0}} {:floor-cos 0.999})))

    (it "matches when best-cos meets the floor or lex is a rare-term anchor"
      (should (sut/match? {:best-cos 0.47 :lex 0.1} 0.47))
      (should (sut/match? {:best-cos 0.2 :lex 0.5} 0.47))
      (should-not (sut/match? {:best-cos 0.41 :lex 0.1} 0.47)))

    (it "evaluates raw cosines and lex, independent of channel weights"
      (should (sut/match? {:best-cos 1.0 :lex 0.0} 0.999))
      (should (sut/match? {:best-cos 0.2 :lex 0.5} 0.999)))

    (it "disables the floor at 0"
      (should (sut/match? {:best-cos 0.0 :lex 0.0} 0.0)))
    )
  )
