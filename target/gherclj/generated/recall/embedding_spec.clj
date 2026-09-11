(ns recall.embedding-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.config.config-steps :as config-steps]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.llm.providers-steps :as providers-steps]))

(describe "Embedding Seam"

  (around [it]
    (binding [g/*state* (atom {})]
      (lifecycle/run-before-feature-hooks!)
      (try
        (it)
        (finally
          (lifecycle/run-after-feature-hooks!)))))

  (around [it]
    (binding [g/*state* (atom @g/*state*)]
      (lifecycle/run-before-scenario-hooks!)
      (try
        (it)
        (finally
          (lifecycle/run-after-scenario-hooks!)))))

  (it "embed is registered and has help"
    ;; Given an Isaac root at "isaac-state"  (recall/embedding.feature:14)
    (g/with-step* "Given an Isaac root at \"isaac-state\"" "recall/embedding.feature" 14 (fn [] (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")))
    ;; When isaac is run with "help embed"  (recall/embedding.feature:19)
    (g/with-step* "When isaac is run with \"help embed\"" "recall/embedding.feature" 19 (fn [] (isaac.foundation.cli-steps/isaac-run "help embed")))
    ;; Then the stdout matches:  (recall/embedding.feature:20)
    ;;   | pattern |  (recall/embedding.feature:21)
    ;;   | Usage: isaac embed \[options\] \[text \.\.\.\] |  (recall/embedding.feature:22)
    ;;   | Embed text with the configured embedding provider |  (recall/embedding.feature:23)
    ;;   | Arguments: |  (recall/embedding.feature:24)
    ;;   | text\s+Text to embed \(one vector per argument\) |  (recall/embedding.feature:25)
    (g/with-step* "Then the stdout matches:" "recall/embedding.feature" 20 (fn [] (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["Usage: isaac embed \\[options\\] \\[text \\.\\.\\.\\]"] ["Embed text with the configured embedding provider"] ["Arguments:"] ["text\\s+Text to embed \\(one vector per argument\\)"]], :header-line 21, :row-lines [22 23 24 25]})))
    ;; And the exit code is 0  (recall/embedding.feature:26)
    (g/with-step* "And the exit code is 0" "recall/embedding.feature" 26 (fn [] (isaac.foundation.cli-steps/exit-code-is "0"))))

  (it "embedding unconfigured is a legal tier, not an error"
    ;; Given an Isaac root at "isaac-state"  (recall/embedding.feature:14)
    (g/with-step* "Given an Isaac root at \"isaac-state\"" "recall/embedding.feature" 14 (fn [] (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")))
    ;; When isaac is run with "config validate"  (recall/embedding.feature:31)
    (g/with-step* "When isaac is run with \"config validate\"" "recall/embedding.feature" 31 (fn [] (isaac.foundation.cli-steps/isaac-run "config validate")))
    ;; Then the exit code is 0  (recall/embedding.feature:32)
    (g/with-step* "Then the exit code is 0" "recall/embedding.feature" 32 (fn [] (isaac.foundation.cli-steps/exit-code-is "0")))
    ;; When isaac is run with "embed hello"  (recall/embedding.feature:33)
    (g/with-step* "When isaac is run with \"embed hello\"" "recall/embedding.feature" 33 (fn [] (isaac.foundation.cli-steps/isaac-run "embed hello")))
    ;; Then the stderr contains "no embedding configured"  (recall/embedding.feature:34)
    (g/with-step* "Then the stderr contains \"no embedding configured\"" "recall/embedding.feature" 34 (fn [] (isaac.foundation.cli-steps/stderr-contains "no embedding configured")))
    ;; And the stderr contains ":embedding"  (recall/embedding.feature:35)
    (g/with-step* "And the stderr contains \":embedding\"" "recall/embedding.feature" 35 (fn [] (isaac.foundation.cli-steps/stderr-contains ":embedding")))
    ;; And the stderr does not contain "Exception"  (recall/embedding.feature:36)
    (g/with-step* "And the stderr does not contain \"Exception\"" "recall/embedding.feature" 36 (fn [] (isaac.foundation.cli-steps/stderr-does-not-contain "Exception")))
    ;; And the exit code is 1  (recall/embedding.feature:37)
    (g/with-step* "And the exit code is 1" "recall/embedding.feature" 37 (fn [] (isaac.foundation.cli-steps/exit-code-is "1"))))

  (it "embed through a provider-backed embedder"
    ;; Given an Isaac root at "isaac-state"  (recall/embedding.feature:14)
    (g/with-step* "Given an Isaac root at \"isaac-state\"" "recall/embedding.feature" 14 (fn [] (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")))
    ;; Given config file "isaac.edn" containing:  (recall/embedding.feature:42)
    (g/with-step* "Given config file \"isaac.edn\" containing:" "recall/embedding.feature" 42 (fn [] (isaac.config.config-steps/config-file-containing "isaac.edn" "{:embedding {:source :provider :provider \"grover\" :model \"mini-embed\"}}")))
    ;; When isaac is run with "embed hello"  (recall/embedding.feature:46)
    (g/with-step* "When isaac is run with \"embed hello\"" "recall/embedding.feature" 46 (fn [] (isaac.foundation.cli-steps/isaac-run "embed hello")))
    ;; Then the stdout matches:  (recall/embedding.feature:47)
    ;;   | pattern |  (recall/embedding.feature:48)
    ;;   | \[5 532 104 111\] |  (recall/embedding.feature:49)
    (g/with-step* "Then the stdout matches:" "recall/embedding.feature" 47 (fn [] (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["\\[5 532 104 111\\]"]], :header-line 48, :row-lines [49]})))
    ;; And the exit code is 0  (recall/embedding.feature:50)
    (g/with-step* "And the exit code is 0" "recall/embedding.feature" 50 (fn [] (isaac.foundation.cli-steps/exit-code-is "0"))))

  (it "batch embed yields one vector per input text, in order"
    ;; Given an Isaac root at "isaac-state"  (recall/embedding.feature:14)
    (g/with-step* "Given an Isaac root at \"isaac-state\"" "recall/embedding.feature" 14 (fn [] (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")))
    ;; Given config file "isaac.edn" containing:  (recall/embedding.feature:53)
    (g/with-step* "Given config file \"isaac.edn\" containing:" "recall/embedding.feature" 53 (fn [] (isaac.config.config-steps/config-file-containing "isaac.edn" "{:embedding {:source :provider :provider \"grover\" :model \"mini-embed\"}}")))
    ;; When isaac is run with "embed \"hi there\" cat \"hi there\""  (recall/embedding.feature:57)
    (g/with-step* "When isaac is run with \"embed \\\"hi there\\\" cat \\\"hi there\\\"\"" "recall/embedding.feature" 57 (fn [] (isaac.foundation.cli-steps/isaac-run "embed \\\"hi there\\\" cat \\\"hi there\\\"")))
    ;; Then the stdout lines match:  (recall/embedding.feature:58)
    ;;   | text |  (recall/embedding.feature:59)
    ;;   | [8 777 104 101] |  (recall/embedding.feature:60)
    ;;   | [3 312 99 116] |  (recall/embedding.feature:61)
    ;;   | [8 777 104 101] |  (recall/embedding.feature:62)
    (g/with-step* "Then the stdout lines match:" "recall/embedding.feature" 58 (fn [] (isaac.foundation.cli-steps/stdout-lines-match {:headers ["text"], :rows [["[8 777 104 101]"] ["[3 312 99 116]"] ["[8 777 104 101]"]], :header-line 59, :row-lines [60 61 62]})))
    ;; And the exit code is 0  (recall/embedding.feature:63)
    (g/with-step* "And the exit code is 0" "recall/embedding.feature" 63 (fn [] (isaac.foundation.cli-steps/exit-code-is "0"))))

  (it "embedding resolves provider config and hits the embed endpoint"
    ;; Given an Isaac root at "isaac-state"  (recall/embedding.feature:14)
    (g/with-step* "Given an Isaac root at \"isaac-state\"" "recall/embedding.feature" 14 (fn [] (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")))
    ;; Given config file "isaac.edn" containing:  (recall/embedding.feature:68)
    (g/with-step* "Given config file \"isaac.edn\" containing:" "recall/embedding.feature" 68 (fn [] (isaac.config.config-steps/config-file-containing "isaac.edn" "{:embedding {:source :provider :provider \"grover:ollama\" :model \"nomic-embed-text\"}}")))
    ;; When isaac is run with "embed hello"  (recall/embedding.feature:72)
    (g/with-step* "When isaac is run with \"embed hello\"" "recall/embedding.feature" 72 (fn [] (isaac.foundation.cli-steps/isaac-run "embed hello")))
    ;; Then the last outbound HTTP request matches:  (recall/embedding.feature:73)
    ;;   | key | value |  (recall/embedding.feature:74)
    ;;   | url | #".*/api/embed" |  (recall/embedding.feature:75)
    ;;   | body.model | nomic-embed-text |  (recall/embedding.feature:76)
    ;;   | body.input | ["hello"] |  (recall/embedding.feature:77)
    (g/with-step* "Then the last outbound HTTP request matches:" "recall/embedding.feature" 73 (fn [] (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["url" "#\".*/api/embed\""] ["body.model" "nomic-embed-text"] ["body.input" "[\"hello\"]"]], :header-line 74, :row-lines [75 76 77]})))
    ;; And the exit code is 0  (recall/embedding.feature:78)
    (g/with-step* "And the exit code is 0" "recall/embedding.feature" 78 (fn [] (isaac.foundation.cli-steps/exit-code-is "0"))))

  (it "config validation rejects an embedding config with an unknown provider"
    ;; Given an Isaac root at "isaac-state"  (recall/embedding.feature:14)
    (g/with-step* "Given an Isaac root at \"isaac-state\"" "recall/embedding.feature" 14 (fn [] (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")))
    ;; Given config file "isaac.edn" containing:  (recall/embedding.feature:83)
    (g/with-step* "Given config file \"isaac.edn\" containing:" "recall/embedding.feature" 83 (fn [] (isaac.config.config-steps/config-file-containing "isaac.edn" "{:embedding {:source :provider :provider \"nonesuch\" :model \"nomic-embed-text\"}}")))
    ;; When isaac is run with "config validate"  (recall/embedding.feature:87)
    (g/with-step* "When isaac is run with \"config validate\"" "recall/embedding.feature" 87 (fn [] (isaac.foundation.cli-steps/isaac-run "config validate")))
    ;; Then the stderr matches:  (recall/embedding.feature:88)
    ;;   | pattern |  (recall/embedding.feature:89)
    ;;   | embedding\.provider.*references undefined provider |  (recall/embedding.feature:90)
    ;;   | bad value: nonesuch |  (recall/embedding.feature:91)
    (g/with-step* "Then the stderr matches:" "recall/embedding.feature" 88 (fn [] (isaac.foundation.cli-steps/stderr-matches {:headers ["pattern"], :rows [["embedding\\.provider.*references undefined provider"] ["bad value: nonesuch"]], :header-line 89, :row-lines [90 91]})))
    ;; And the exit code is 1  (recall/embedding.feature:92)
    (g/with-step* "And the exit code is 1" "recall/embedding.feature" 92 (fn [] (isaac.foundation.cli-steps/exit-code-is "1"))))

  (it "config validation rejects an unknown embedding source"
    ;; Given an Isaac root at "isaac-state"  (recall/embedding.feature:14)
    (g/with-step* "Given an Isaac root at \"isaac-state\"" "recall/embedding.feature" 14 (fn [] (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")))
    ;; Given config file "isaac.edn" containing:  (recall/embedding.feature:95)
    (g/with-step* "Given config file \"isaac.edn\" containing:" "recall/embedding.feature" 95 (fn [] (isaac.config.config-steps/config-file-containing "isaac.edn" "{:embedding {:source :warp-drive :provider \"grover\" :model \"mini-embed\"}}")))
    ;; When isaac is run with "config validate"  (recall/embedding.feature:99)
    (g/with-step* "When isaac is run with \"config validate\"" "recall/embedding.feature" 99 (fn [] (isaac.foundation.cli-steps/isaac-run "config validate")))
    ;; Then the stderr matches:  (recall/embedding.feature:100)
    ;;   | pattern |  (recall/embedding.feature:101)
    ;;   | embedding\.source |  (recall/embedding.feature:102)
    ;;   | bad value: warp-drive |  (recall/embedding.feature:103)
    ;;   | must be one of.*provider |  (recall/embedding.feature:104)
    (g/with-step* "Then the stderr matches:" "recall/embedding.feature" 100 (fn [] (isaac.foundation.cli-steps/stderr-matches {:headers ["pattern"], :rows [["embedding\\.source"] ["bad value: warp-drive"] ["must be one of.*provider"]], :header-line 101, :row-lines [102 103 104]})))
    ;; And the exit code is 1  (recall/embedding.feature:105)
    (g/with-step* "And the exit code is 1" "recall/embedding.feature" 105 (fn [] (isaac.foundation.cli-steps/exit-code-is "1")))))
