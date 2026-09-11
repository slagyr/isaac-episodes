(ns episodes.recall-logging-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.config.config-steps :as config-steps]
            [isaac.episodes.episode-steps :as episode-steps]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.log-steps :as log-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Recall is visible in the logs"

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

  (it "recall-at-open logs what it injected"
    ;; Given the isaac EDN file "config/crew/cordelia.edn" exists with:  (episodes/recall_logging.feature:10)
    ;;   | path | value |  (episodes/recall_logging.feature:11)
    ;;   | model | echo |  (episodes/recall_logging.feature:12)
    ;;   | soul | You are Cordelia |  (episodes/recall_logging.feature:13)
    ;;   | session-policy | episodes |  (episodes/recall_logging.feature:14)
    (g/with-step* "Given the isaac EDN file \"config/crew/cordelia.edn\" exists with:" "episodes/recall_logging.feature" 10 (fn [] (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/cordelia.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["soul" "You are Cordelia"] ["session-policy" "episodes"]], :header-line 11, :row-lines [12 13 14]})))
    ;; And config file "isaac.edn" containing:  (episodes/recall_logging.feature:15)
    (g/with-step* "And config file \"isaac.edn\" containing:" "episodes/recall_logging.feature" 15 (fn [] (isaac.config.config-steps/config-file-containing "isaac.edn" "{:embedding {:source :provider :provider \"grover\" :model \"mini-embed\"}}")))
    ;; And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:  (episodes/recall_logging.feature:19)
    ;;   | id | started-at | ended-at | gist | text |  (episodes/recall_logging.feature:20)
    ;;   | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Wine pairing for pheasant | a light pinot noir suits roast pheasant |  (episodes/recall_logging.feature:21)
    (g/with-step* "And crew \"cordelia\" has a closed episode \"2026-03-01-1000-ab12\" with scenes:" "episodes/recall_logging.feature" 19 (fn [] (isaac.episodes.episode-steps/crew-has-closed-episode-with-scenes "cordelia" "2026-03-01-1000-ab12" {:headers ["id" "started-at" "ended-at" "gist" "text"], :rows [["2026-03-01-1000-s1x1" "2026-03-01T10:00:00" "2026-03-01T10:05:00" "Wine pairing for pheasant" "a light pinot noir suits roast pheasant"]], :header-line 20, :row-lines [21]})))
    ;; When isaac is run with "episodes index --crew cordelia"  (episodes/recall_logging.feature:22)
    (g/with-step* "When isaac is run with \"episodes index --crew cordelia\"" "episodes/recall_logging.feature" 22 (fn [] (isaac.foundation.cli-steps/isaac-run "episodes index --crew cordelia")))
    ;; Given the following model responses are queued:  (episodes/recall_logging.feature:25)
    ;;   | type | content | model |  (episodes/recall_logging.feature:26)
    ;;   | text | Pinot noir, as ever. | echo |  (episodes/recall_logging.feature:27)
    (g/with-step* "Given the following model responses are queued:" "episodes/recall_logging.feature" 25 (fn [] (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Pinot noir, as ever." "echo"]], :header-line 26, :row-lines [27]})))
    ;; When isaac is run with "prompt -m 'What wine pairs with pheasant?' --session supper-chat --crew cordelia"  (episodes/recall_logging.feature:28)
    (g/with-step* "When isaac is run with \"prompt -m 'What wine pairs with pheasant?' --session supper-chat --crew cordelia\"" "episodes/recall_logging.feature" 28 (fn [] (isaac.foundation.cli-steps/isaac-run "prompt -m 'What wine pairs with pheasant?' --session supper-chat --crew cordelia")))
    ;; Then the exit code is 0  (episodes/recall_logging.feature:29)
    (g/with-step* "Then the exit code is 0" "episodes/recall_logging.feature" 29 (fn [] (isaac.foundation.cli-steps/exit-code-is "0")))
    ;; And the log has entries matching:  (episodes/recall_logging.feature:30)
    ;;   | level | event | crew | thread | search | lineage | top | floor |  (episodes/recall_logging.feature:31)
    ;;   | :info | :episodes/recalled | cordelia | supper-chat | 1 | 0 | #"0\.[0-9]+" | 0.47 |  (episodes/recall_logging.feature:32)
    (g/with-step* "And the log has entries matching:" "episodes/recall_logging.feature" 30 (fn [] (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "crew" "thread" "search" "lineage" "top" "floor"], :rows [[":info" ":episodes/recalled" "cordelia" "supper-chat" "1" "0" "#\"0\\.[0-9]+\"" "0.47"]], :header-line 31, :row-lines [32]}))))

  (it "a query that clears nothing logs the best score it saw"
    ;; Given the isaac EDN file "config/crew/cordelia.edn" exists with:  (episodes/recall_logging.feature:10)
    ;;   | path | value |  (episodes/recall_logging.feature:11)
    ;;   | model | echo |  (episodes/recall_logging.feature:12)
    ;;   | soul | You are Cordelia |  (episodes/recall_logging.feature:13)
    ;;   | session-policy | episodes |  (episodes/recall_logging.feature:14)
    (g/with-step* "Given the isaac EDN file \"config/crew/cordelia.edn\" exists with:" "episodes/recall_logging.feature" 10 (fn [] (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/cordelia.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["soul" "You are Cordelia"] ["session-policy" "episodes"]], :header-line 11, :row-lines [12 13 14]})))
    ;; And config file "isaac.edn" containing:  (episodes/recall_logging.feature:15)
    (g/with-step* "And config file \"isaac.edn\" containing:" "episodes/recall_logging.feature" 15 (fn [] (isaac.config.config-steps/config-file-containing "isaac.edn" "{:embedding {:source :provider :provider \"grover\" :model \"mini-embed\"}}")))
    ;; And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:  (episodes/recall_logging.feature:19)
    ;;   | id | started-at | ended-at | gist | text |  (episodes/recall_logging.feature:20)
    ;;   | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Wine pairing for pheasant | a light pinot noir suits roast pheasant |  (episodes/recall_logging.feature:21)
    (g/with-step* "And crew \"cordelia\" has a closed episode \"2026-03-01-1000-ab12\" with scenes:" "episodes/recall_logging.feature" 19 (fn [] (isaac.episodes.episode-steps/crew-has-closed-episode-with-scenes "cordelia" "2026-03-01-1000-ab12" {:headers ["id" "started-at" "ended-at" "gist" "text"], :rows [["2026-03-01-1000-s1x1" "2026-03-01T10:00:00" "2026-03-01T10:05:00" "Wine pairing for pheasant" "a light pinot noir suits roast pheasant"]], :header-line 20, :row-lines [21]})))
    ;; When isaac is run with "episodes index --crew cordelia"  (episodes/recall_logging.feature:22)
    (g/with-step* "When isaac is run with \"episodes index --crew cordelia\"" "episodes/recall_logging.feature" 22 (fn [] (isaac.foundation.cli-steps/isaac-run "episodes index --crew cordelia")))
    ;; Given the following model responses are queued:  (episodes/recall_logging.feature:35)
    ;;   | type | content | model |  (episodes/recall_logging.feature:36)
    ;;   | text | Nothing there. | echo |  (episodes/recall_logging.feature:37)
    (g/with-step* "Given the following model responses are queued:" "episodes/recall_logging.feature" 35 (fn [] (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Nothing there." "echo"]], :header-line 36, :row-lines [37]})))
    ;; When isaac is run with "prompt -m 'How do I rotate the ship logs?' --session logs-chat --crew cordelia"  (episodes/recall_logging.feature:38)
    (g/with-step* "When isaac is run with \"prompt -m 'How do I rotate the ship logs?' --session logs-chat --crew cordelia\"" "episodes/recall_logging.feature" 38 (fn [] (isaac.foundation.cli-steps/isaac-run "prompt -m 'How do I rotate the ship logs?' --session logs-chat --crew cordelia")))
    ;; Then the exit code is 0  (episodes/recall_logging.feature:39)
    (g/with-step* "Then the exit code is 0" "episodes/recall_logging.feature" 39 (fn [] (isaac.foundation.cli-steps/exit-code-is "0")))
    ;; And the log has entries matching:  (episodes/recall_logging.feature:40)
    ;;   | level | event | crew | thread | best | floor |  (episodes/recall_logging.feature:41)
    ;;   | :info | :episodes/recall-empty | cordelia | logs-chat | #"0\.[0-9]+" | 0.47 |  (episodes/recall_logging.feature:42)
    (g/with-step* "And the log has entries matching:" "episodes/recall_logging.feature" 40 (fn [] (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "crew" "thread" "best" "floor"], :rows [[":info" ":episodes/recall-empty" "cordelia" "logs-chat" "#\"0\\.[0-9]+\"" "0.47"]], :header-line 41, :row-lines [42]}))))

  (it "the recall tool logs the scene it fetched"
    ;; Given the isaac EDN file "config/crew/cordelia.edn" exists with:  (episodes/recall_logging.feature:10)
    ;;   | path | value |  (episodes/recall_logging.feature:11)
    ;;   | model | echo |  (episodes/recall_logging.feature:12)
    ;;   | soul | You are Cordelia |  (episodes/recall_logging.feature:13)
    ;;   | session-policy | episodes |  (episodes/recall_logging.feature:14)
    (g/with-step* "Given the isaac EDN file \"config/crew/cordelia.edn\" exists with:" "episodes/recall_logging.feature" 10 (fn [] (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/cordelia.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["soul" "You are Cordelia"] ["session-policy" "episodes"]], :header-line 11, :row-lines [12 13 14]})))
    ;; And config file "isaac.edn" containing:  (episodes/recall_logging.feature:15)
    (g/with-step* "And config file \"isaac.edn\" containing:" "episodes/recall_logging.feature" 15 (fn [] (isaac.config.config-steps/config-file-containing "isaac.edn" "{:embedding {:source :provider :provider \"grover\" :model \"mini-embed\"}}")))
    ;; And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:  (episodes/recall_logging.feature:19)
    ;;   | id | started-at | ended-at | gist | text |  (episodes/recall_logging.feature:20)
    ;;   | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Wine pairing for pheasant | a light pinot noir suits roast pheasant |  (episodes/recall_logging.feature:21)
    (g/with-step* "And crew \"cordelia\" has a closed episode \"2026-03-01-1000-ab12\" with scenes:" "episodes/recall_logging.feature" 19 (fn [] (isaac.episodes.episode-steps/crew-has-closed-episode-with-scenes "cordelia" "2026-03-01-1000-ab12" {:headers ["id" "started-at" "ended-at" "gist" "text"], :rows [["2026-03-01-1000-s1x1" "2026-03-01T10:00:00" "2026-03-01T10:05:00" "Wine pairing for pheasant" "a light pinot noir suits roast pheasant"]], :header-line 20, :row-lines [21]})))
    ;; When isaac is run with "episodes index --crew cordelia"  (episodes/recall_logging.feature:22)
    (g/with-step* "When isaac is run with \"episodes index --crew cordelia\"" "episodes/recall_logging.feature" 22 (fn [] (isaac.foundation.cli-steps/isaac-run "episodes index --crew cordelia")))
    ;; Given the built-in tools are registered  (episodes/recall_logging.feature:45)
    (g/with-step* "Given the built-in tools are registered" "episodes/recall_logging.feature" 45 (fn [] (isaac.tool.tools-steps/builtin-tools-registered)))
    ;; And the crew "cordelia" allows tools: "recall/*"  (episodes/recall_logging.feature:46)
    (g/with-step* "And the crew \"cordelia\" allows tools: \"recall/*\"" "episodes/recall_logging.feature" 46 (fn [] (isaac.session.session-steps/crew-tool-allow "cordelia" "recall/*")))
    ;; And the following model responses are queued:  (episodes/recall_logging.feature:47)
    ;;   | type | tool_call | arguments | content | model |  (episodes/recall_logging.feature:48)
    ;;   | tool_call | recall__scene | {"scene-id": "2026-03-01-1000-s1x1"} |  | echo |  (episodes/recall_logging.feature:49)
    ;;   | text |  |  | Pinot noir, as ever. | echo |  (episodes/recall_logging.feature:50)
    (g/with-step* "And the following model responses are queued:" "episodes/recall_logging.feature" 47 (fn [] (isaac.session.session-steps/responses-queued {:headers ["type" "tool_call" "arguments" "content" "model"], :rows [["tool_call" "recall__scene" "{\"scene-id\": \"2026-03-01-1000-s1x1\"}" "" "echo"] ["text" "" "" "Pinot noir, as ever." "echo"]], :header-line 48, :row-lines [49 50]})))
    ;; When isaac is run with "prompt -m 'What wine pairs with pheasant?' --session supper-chat --crew cordelia"  (episodes/recall_logging.feature:51)
    (g/with-step* "When isaac is run with \"prompt -m 'What wine pairs with pheasant?' --session supper-chat --crew cordelia\"" "episodes/recall_logging.feature" 51 (fn [] (isaac.foundation.cli-steps/isaac-run "prompt -m 'What wine pairs with pheasant?' --session supper-chat --crew cordelia")))
    ;; Then the exit code is 0  (episodes/recall_logging.feature:52)
    (g/with-step* "Then the exit code is 0" "episodes/recall_logging.feature" 52 (fn [] (isaac.foundation.cli-steps/exit-code-is "0")))
    ;; And the log has entries matching:  (episodes/recall_logging.feature:53)
    ;;   | level | event | crew | scene | episode |  (episodes/recall_logging.feature:54)
    ;;   | :info | :recall/scene | cordelia | 2026-03-01-1000-s1x1 | 2026-03-01-1000-ab12 |  (episodes/recall_logging.feature:55)
    (g/with-step* "And the log has entries matching:" "episodes/recall_logging.feature" 53 (fn [] (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "crew" "scene" "episode"], :rows [[":info" ":recall/scene" "cordelia" "2026-03-01-1000-s1x1" "2026-03-01-1000-ab12"]], :header-line 54, :row-lines [55]})))))
