(ns recall.live-tools-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.config.config-steps :as config-steps]
            [isaac.episodes.episode-steps :as episode-steps]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Recall — live tools"

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

  (it "recall__search surfaces memory mid-episode"
    ;; Given default Grover setup  (recall/live_tools.feature:11)
    (g/with-step* "Given default Grover setup" "recall/live_tools.feature" 11 (fn [] (isaac.session.session-steps/default-grover-setup)))
    ;; Given the isaac EDN file "config/crew/cordelia.edn" exists with:  (recall/live_tools.feature:14)
    ;;   | path | value |  (recall/live_tools.feature:15)
    ;;   | model | echo |  (recall/live_tools.feature:16)
    ;;   | soul | You are Cordelia |  (recall/live_tools.feature:17)
    ;;   | session-policy | episodes |  (recall/live_tools.feature:18)
    (g/with-step* "Given the isaac EDN file \"config/crew/cordelia.edn\" exists with:" "recall/live_tools.feature" 14 (fn [] (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/cordelia.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["soul" "You are Cordelia"] ["session-policy" "episodes"]], :header-line 15, :row-lines [16 17 18]})))
    ;; And the crew "cordelia" allows tools: recall/search  (recall/live_tools.feature:19)
    (g/with-step* "And the crew \"cordelia\" allows tools: recall/search" "recall/live_tools.feature" 19 (fn [] (isaac.session.session-steps/crew-tool-allow "cordelia" "recall/search")))
    ;; And config file "isaac.edn" containing:  (recall/live_tools.feature:20)
    (g/with-step* "And config file \"isaac.edn\" containing:" "recall/live_tools.feature" 20 (fn [] (isaac.config.config-steps/config-file-containing "isaac.edn" "{:embedding {:source :provider :provider \"grover\" :model \"mini-embed\"}}")))
    ;; And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:  (recall/live_tools.feature:24)
    ;;   | id | started-at | ended-at | gist | text |  (recall/live_tools.feature:25)
    ;;   | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Wine pairing for pheasant | a light pinot noir suits roast pheasant |  (recall/live_tools.feature:26)
    (g/with-step* "And crew \"cordelia\" has a closed episode \"2026-03-01-1000-ab12\" with scenes:" "recall/live_tools.feature" 24 (fn [] (isaac.episodes.episode-steps/crew-has-closed-episode-with-scenes "cordelia" "2026-03-01-1000-ab12" {:headers ["id" "started-at" "ended-at" "gist" "text"], :rows [["2026-03-01-1000-s1x1" "2026-03-01T10:00:00" "2026-03-01T10:05:00" "Wine pairing for pheasant" "a light pinot noir suits roast pheasant"]], :header-line 25, :row-lines [26]})))
    ;; When isaac is run with "episodes index --crew cordelia"  (recall/live_tools.feature:27)
    (g/with-step* "When isaac is run with \"episodes index --crew cordelia\"" "recall/live_tools.feature" 27 (fn [] (isaac.foundation.cli-steps/isaac-run "episodes index --crew cordelia")))
    ;; Given the following model responses are queued:  (recall/live_tools.feature:28)
    ;;   | type | tool_call | arguments | content | model |  (recall/live_tools.feature:29)
    ;;   | toolCall | recall__search | {"query":"pheasant wine"} |  | echo |  (recall/live_tools.feature:30)
    ;;   | text |  |  | It was pinot noir. | echo |  (recall/live_tools.feature:31)
    (g/with-step* "Given the following model responses are queued:" "recall/live_tools.feature" 28 (fn [] (isaac.session.session-steps/responses-queued {:headers ["type" "tool_call" "arguments" "content" "model"], :rows [["toolCall" "recall__search" "{\"query\":\"pheasant wine\"}" "" "echo"] ["text" "" "" "It was pinot noir." "echo"]], :header-line 29, :row-lines [30 31]})))
    ;; When isaac is run with "prompt -m 'Remember that wine talk?' --session bistro-chat --crew cordelia"  (recall/live_tools.feature:32)
    (g/with-step* "When isaac is run with \"prompt -m 'Remember that wine talk?' --session bistro-chat --crew cordelia\"" "recall/live_tools.feature" 32 (fn [] (isaac.foundation.cli-steps/isaac-run "prompt -m 'Remember that wine talk?' --session bistro-chat --crew cordelia")))
    ;; Then the stdout contains "It was pinot noir."  (recall/live_tools.feature:33)
    (g/with-step* "Then the stdout contains \"It was pinot noir.\"" "recall/live_tools.feature" 33 (fn [] (isaac.foundation.cli-steps/stdout-contains "It was pinot noir.")))
    ;; And the exit code is 0  (recall/live_tools.feature:34)
    (g/with-step* "And the exit code is 0" "recall/live_tools.feature" 34 (fn [] (isaac.foundation.cli-steps/exit-code-is "0")))
    ;; And that episode's backing session has transcript matching:  (recall/live_tools.feature:35)
    ;;   | type | message.role | message.content |  (recall/live_tools.feature:36)
    ;;   | message | user | Remember that wine talk? |  (recall/live_tools.feature:37)
    ;;   | message | assistant | #"(?s)recall__search" |  (recall/live_tools.feature:38)
    ;;   | message | toolResult | #"(?s)\[2026-03-01-1000-s1x1 · 2026-03-01\] Wine pairing for pheasant" |  (recall/live_tools.feature:39)
    ;;   | message | assistant | It was pinot noir. |  (recall/live_tools.feature:40)
    (g/with-step* "And that episode's backing session has transcript matching:" "recall/live_tools.feature" 35 (fn [] (isaac.episodes.episode-steps/that-episode-backing-session-has-transcript-matching {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Remember that wine talk?"] ["message" "assistant" "#\"(?s)recall__search\""] ["message" "toolResult" "#\"(?s)\\[2026-03-01-1000-s1x1 · 2026-03-01\\] Wine pairing for pheasant\""] ["message" "assistant" "It was pinot noir."]], :header-line 36, :row-lines [37 38 39 40]})))
    ;; And that episode has recalled scenes:  (recall/live_tools.feature:41)
    ;;   | scene-id | origin-episode |  (recall/live_tools.feature:42)
    ;;   | 2026-03-01-1000-s1x1 | 2026-03-01-1000-ab12 |  (recall/live_tools.feature:43)
    (g/with-step* "And that episode has recalled scenes:" "recall/live_tools.feature" 41 (fn [] (isaac.episodes.episode-steps/that-episode-has-recalled-scenes {:headers ["scene-id" "origin-episode"], :rows [["2026-03-01-1000-s1x1" "2026-03-01-1000-ab12"]], :header-line 42, :row-lines [43]}))))

  (it "recall__scene fetches distilled text by id; unknown ids fail helpfully"
    ;; Given default Grover setup  (recall/live_tools.feature:11)
    (g/with-step* "Given default Grover setup" "recall/live_tools.feature" 11 (fn [] (isaac.session.session-steps/default-grover-setup)))
    ;; Given the isaac EDN file "config/crew/cordelia.edn" exists with:  (recall/live_tools.feature:46)
    ;;   | path | value |  (recall/live_tools.feature:47)
    ;;   | model | echo |  (recall/live_tools.feature:48)
    ;;   | soul | You are Cordelia |  (recall/live_tools.feature:49)
    ;;   | session-policy | episodes |  (recall/live_tools.feature:50)
    (g/with-step* "Given the isaac EDN file \"config/crew/cordelia.edn\" exists with:" "recall/live_tools.feature" 46 (fn [] (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/cordelia.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["soul" "You are Cordelia"] ["session-policy" "episodes"]], :header-line 47, :row-lines [48 49 50]})))
    ;; And the crew "cordelia" allows tools: recall/scene  (recall/live_tools.feature:51)
    (g/with-step* "And the crew \"cordelia\" allows tools: recall/scene" "recall/live_tools.feature" 51 (fn [] (isaac.session.session-steps/crew-tool-allow "cordelia" "recall/scene")))
    ;; And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:  (recall/live_tools.feature:52)
    ;;   | id | started-at | ended-at | gist | text |  (recall/live_tools.feature:53)
    ;;   | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Wine pairing for pheasant | a light pinot noir suits roast pheasant |  (recall/live_tools.feature:54)
    (g/with-step* "And crew \"cordelia\" has a closed episode \"2026-03-01-1000-ab12\" with scenes:" "recall/live_tools.feature" 52 (fn [] (isaac.episodes.episode-steps/crew-has-closed-episode-with-scenes "cordelia" "2026-03-01-1000-ab12" {:headers ["id" "started-at" "ended-at" "gist" "text"], :rows [["2026-03-01-1000-s1x1" "2026-03-01T10:00:00" "2026-03-01T10:05:00" "Wine pairing for pheasant" "a light pinot noir suits roast pheasant"]], :header-line 53, :row-lines [54]})))
    ;; And the following model responses are queued:  (recall/live_tools.feature:55)
    ;;   | type | tool_call | arguments | content | model |  (recall/live_tools.feature:56)
    ;;   | toolCall | recall__scene | {"scene-id":"2026-03-01-1000-s1x1"} |  | echo |  (recall/live_tools.feature:57)
    ;;   | text |  |  | Fetched it. | echo |  (recall/live_tools.feature:58)
    ;;   | toolCall | recall__scene | {"scene-id":"2026-01-01-0000-none"} |  | echo |  (recall/live_tools.feature:59)
    ;;   | text |  |  | Nothing there. | echo |  (recall/live_tools.feature:60)
    (g/with-step* "And the following model responses are queued:" "recall/live_tools.feature" 55 (fn [] (isaac.session.session-steps/responses-queued {:headers ["type" "tool_call" "arguments" "content" "model"], :rows [["toolCall" "recall__scene" "{\"scene-id\":\"2026-03-01-1000-s1x1\"}" "" "echo"] ["text" "" "" "Fetched it." "echo"] ["toolCall" "recall__scene" "{\"scene-id\":\"2026-01-01-0000-none\"}" "" "echo"] ["text" "" "" "Nothing there." "echo"]], :header-line 56, :row-lines [57 58 59 60]})))
    ;; When isaac is run with "prompt -m 'Pull up that wine scene' --session bistro-chat --crew cordelia"  (recall/live_tools.feature:61)
    (g/with-step* "When isaac is run with \"prompt -m 'Pull up that wine scene' --session bistro-chat --crew cordelia\"" "recall/live_tools.feature" 61 (fn [] (isaac.foundation.cli-steps/isaac-run "prompt -m 'Pull up that wine scene' --session bistro-chat --crew cordelia")))
    ;; Then the stdout contains "Fetched it."  (recall/live_tools.feature:62)
    (g/with-step* "Then the stdout contains \"Fetched it.\"" "recall/live_tools.feature" 62 (fn [] (isaac.foundation.cli-steps/stdout-contains "Fetched it.")))
    ;; And that episode's backing session has transcript matching:  (recall/live_tools.feature:63)
    ;;   | type | message.role | message.content |  (recall/live_tools.feature:64)
    ;;   | message | user | Pull up that wine scene |  (recall/live_tools.feature:65)
    ;;   | message | assistant | #"(?s)recall__scene" |  (recall/live_tools.feature:66)
    ;;   | message | toolResult | #"(?s)pinot noir suits roast pheasant" |  (recall/live_tools.feature:67)
    ;;   | message | assistant | Fetched it. |  (recall/live_tools.feature:68)
    (g/with-step* "And that episode's backing session has transcript matching:" "recall/live_tools.feature" 63 (fn [] (isaac.episodes.episode-steps/that-episode-backing-session-has-transcript-matching {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Pull up that wine scene"] ["message" "assistant" "#\"(?s)recall__scene\""] ["message" "toolResult" "#\"(?s)pinot noir suits roast pheasant\""] ["message" "assistant" "Fetched it."]], :header-line 64, :row-lines [65 66 67 68]})))
    ;; When isaac is run with "prompt -m 'And the ghost scene' --session bistro-chat --crew cordelia"  (recall/live_tools.feature:69)
    (g/with-step* "When isaac is run with \"prompt -m 'And the ghost scene' --session bistro-chat --crew cordelia\"" "recall/live_tools.feature" 69 (fn [] (isaac.foundation.cli-steps/isaac-run "prompt -m 'And the ghost scene' --session bistro-chat --crew cordelia")))
    ;; Then the stdout contains "Nothing there."  (recall/live_tools.feature:70)
    (g/with-step* "Then the stdout contains \"Nothing there.\"" "recall/live_tools.feature" 70 (fn [] (isaac.foundation.cli-steps/stdout-contains "Nothing there.")))
    ;; And the exit code is 0  (recall/live_tools.feature:71)
    (g/with-step* "And the exit code is 0" "recall/live_tools.feature" 71 (fn [] (isaac.foundation.cli-steps/exit-code-is "0")))
    ;; And that episode's backing session has transcript matching:  (recall/live_tools.feature:72)
    ;;   | type | message.role | message.content |  (recall/live_tools.feature:73)
    ;;   | message | user | Pull up that wine scene |  (recall/live_tools.feature:74)
    ;;   | message | assistant | #"(?s)recall__scene" |  (recall/live_tools.feature:75)
    ;;   | message | toolResult | #"(?s)pinot noir suits roast pheasant" |  (recall/live_tools.feature:76)
    ;;   | message | assistant | Fetched it. |  (recall/live_tools.feature:77)
    ;;   | message | user | And the ghost scene |  (recall/live_tools.feature:78)
    ;;   | message | assistant | #"(?s)recall__scene" |  (recall/live_tools.feature:79)
    ;;   | message | toolResult | #"(?s)unknown scene" |  (recall/live_tools.feature:80)
    ;;   | message | assistant | Nothing there. |  (recall/live_tools.feature:81)
    (g/with-step* "And that episode's backing session has transcript matching:" "recall/live_tools.feature" 72 (fn [] (isaac.episodes.episode-steps/that-episode-backing-session-has-transcript-matching {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Pull up that wine scene"] ["message" "assistant" "#\"(?s)recall__scene\""] ["message" "toolResult" "#\"(?s)pinot noir suits roast pheasant\""] ["message" "assistant" "Fetched it."] ["message" "user" "And the ghost scene"] ["message" "assistant" "#\"(?s)recall__scene\""] ["message" "toolResult" "#\"(?s)unknown scene\""] ["message" "assistant" "Nothing there."]], :header-line 73, :row-lines [74 75 76 77 78 79 80 81]})))))
