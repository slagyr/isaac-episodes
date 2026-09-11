Feature: Episodes — migrate-session
  `isaac episodes migrate-session <session-id>` materializes an existing
  session as a closed episode: a directory under ~/.isaac/episodes/<crew>/
  holding episode.edn plus one immutable markdown scene file
  (<scene-id>.md: YAML frontmatter + distilled text body) per scene, with
  gists written by the configured gist model (:episodes {:gist-model ...}).
  Session files remain untouched and authoritative. Ids are timestamped
  (<yyyy-MM-dd-HHmm>-<chaos>), taken from the session's own message times,
  not migration time. The segmentation LLM speaks span-local ordinals in
  line format (`1-2: gist`); sealed scene records store message ids.

  Background:
    Given an Isaac root at "isaac-state"
    And the isaac EDN file "config/providers/grover.edn" exists with:
      | path | value  |
      | api  | grover |
      | auth | none   |

  # ----- Help -----

  # isaac-qxvl: gains close + list rows
  Scenario: episodes command is registered and has help
    When isaac is run with "help episodes"
    Then the stdout matches:
      | pattern                                                                    |
      | Usage: isaac episodes \[subcommand\] \[options\]                           |
      | Subcommands:                                                               |
      | migrate-session <session-id>\s+Materialize a session as a closed episode   |
      | close\s+Close open episodes now \(seal scenes\)                            |
      | list\s+List a crew's episodes                                              |
    And the exit code is 0

  # ----- Migration -----

  Scenario: migrating a session materializes a closed episode
    Given the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the following sessions exist:
      | name          | crew     |
      | quiet-regatta | cordelia |
    And session "quiet-regatta" has transcript:
      | type    | message.role | message.content                      |
      | message | user         | What wine pairs with roast pheasant? |
      | message | assistant    | A light pinot noir.                  |
      | message | user         | Now, about the regatta schedule.     |
      | message | assistant    | The first race is Saturday at dawn.  |
    And the following model responses are queued:
      | model | type | content                                                                 |
      | gist  | text | 1-2: Wine pairing for pheasant\n3-4: Regatta scheduling                 |
    When isaac is run with "episodes migrate-session quiet-regatta"
    Then the exit code is 0
    And the stdout matches:
      | pattern                                     |
      | migrating quiet-regatta -> episode          |
      | crew cordelia, 1 span, 4 messages           |
      | span 1/1: 4 messages -> 2 scenes \(\d+\.\ds, 25 in, 12 out\) |
      | \s+1-2: Wine pairing for pheasant           |
      | \s+3-4: Regatta scheduling                  |
      | migrated: 1 span, 2 scenes \(\d+\.\ds, 25 in, 12 out\) |
    And an episode exists for crew "cordelia" matching:
      | key           | value                          |
      | id            | #"\d{17}" |
      | migrated-from | quiet-regatta                  |
    And that episode has scenes matching:
      | gist                      | text                    |
      | Wine pairing for pheasant | #"(?s)pinot noir"       |
      | Regatta scheduling        | #"(?s)race is Saturday" |

  Scenario: noisy preamble and fences around boundary lines still parse
    Given the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the following sessions exist:
      | name          | crew     |
      | chatty-model  | cordelia |
    And session "chatty-model" has transcript:
      | type    | message.role | message.content                      |
      | message | user         | What wine pairs with roast pheasant? |
      | message | assistant    | A light pinot noir.                  |
      | message | user         | Now, about the regatta schedule.     |
      | message | assistant    | The first race is Saturday at dawn.  |
    And the following model responses are queued:
      | model | type | content                                                                                                                                      |
      | gist  | text | Sure — here are the scenes:\n```\n1-2: Wine pairing for pheasant\n\n3-4: Regatta scheduling\n```\nHope that helps!                            |
    When isaac is run with "episodes migrate-session chatty-model"
    Then the exit code is 0
    And an episode exists for crew "cordelia" matching:
      | key           | value        |
      | migrated-from | chatty-model |
    And that episode has scenes matching:
      | gist                      | text                    |
      | Wine pairing for pheasant | #"(?s)pinot noir"       |
      | Regatta scheduling        | #"(?s)race is Saturday" |

  Scenario: scene text is distilled — tool markers kept, payloads dropped
    Given the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the following sessions exist:
      | name        | crew     |
      | tidy-larder | cordelia |
    And session "tidy-larder" has transcript:
      | type    | message.role | message.toolCallId | message.content                                                                         |
      | message | user         |                    | What is in the fridge?                                                                  |
      | message | assistant    |                    | [{"type":"toolCall","id":"call_1","name":"fs__read","arguments":{"filePath":"fridge.txt"}}] |
      | message | toolResult   | call_1             | 1 sad lemon, mass of unidentified cheese, Hieronymus's emergency lettuce (DO NOT TOUCH) |
      | message | assistant    |                    | One sad lemon and some cheese. Leave the lettuce alone.                                 |
    And the following model responses are queued:
      | model | type | content                         |
      | gist  | text | 1-4: Fridge inventory check     |
    When isaac is run with "episodes migrate-session tidy-larder"
    Then the exit code is 0
    And an episode exists for crew "cordelia" matching:
      | key           | value       |
      | migrated-from | tidy-larder |
    And that episode has scenes matching:
      | gist                   | text                                                               |
      | Fridge inventory check | #"(?s)What is in the fridge.*\(tool fs__read.*Leave the lettuce alone" |
    And scene 1 of that episode does not contain "emergency lettuce"

  # ----- Compaction spans -----

  Scenario: compaction bounds the spans and its summary rides the next span's prompt
    Given the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the following sessions exist:
      | name          | crew     |
      | packed-galley | cordelia |
    And session "packed-galley" has transcript:
      | type       | summary                             | message.role | message.content                     |
      | message    |                                     | user         | How much hardtack for the voyage?   |
      | message    |                                     | assistant    | Forty pounds should do.             |
      | compaction | They planned the voyage provisions. |              |                                     |
      | message    |                                     | user         | Now the watch rotation.             |
      | message    |                                     | assistant    | Four-hour watches, dogged evenings. |
    And the following model responses are queued:
      | model | type | content                      |
      | gist  | text | 1-2: Provisioning hardtack   |
      | gist  | text | 1-2: Watch rotation          |
    When isaac is run with "episodes migrate-session packed-galley"
    Then the exit code is 0
    And that episode has scenes matching:
      | gist                  | text                   |
      | Provisioning hardtack | #"(?s)Forty pounds"    |
      | Watch rotation        | #"(?s)dogged evenings" |
    And the last LLM request matches:
      | key      | value                                |
      | messages | #".*planned the voyage provisions.*" |

  # ----- Idempotency -----

  Scenario: re-run is a no-op; --force re-migrates in place
    Given the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the following sessions exist:
      | name        | crew     |
      | calm-lagoon | cordelia |
    And session "calm-lagoon" has transcript:
      | type    | message.role | message.content          |
      | message | user         | Chart the reef passage.  |
      | message | assistant    | Marked; keep to leeward. |
    And the following model responses are queued:
      | model | type | content                        |
      | gist  | text | 1-2: Reef passage charting     |
    When isaac is run with "episodes migrate-session calm-lagoon"
    Then the exit code is 0
    When isaac is run with "episodes migrate-session calm-lagoon"
    Then the stdout contains "already migrated"
    And the exit code is 0
    And crew "cordelia" has 1 episode
    Given the following model responses are queued:
      | model | type | content                       |
      | gist  | text | 1-2: Leeward reef passage     |
    When isaac is run with "episodes migrate-session calm-lagoon --force"
    Then the exit code is 0
    And crew "cordelia" has 1 episode
    And that episode has scenes matching:
      | gist                 | text                   |
      | Leeward reef passage | #"(?s)keep to leeward" |

  # ----- Failure and resume -----

  Scenario: bad segmentation output — one retry, span flagged with raw, re-run resumes
    Given the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the following sessions exist:
      | name         | crew     |
      | foggy-strait | cordelia |
    And session "foggy-strait" has transcript:
      | type       | summary                | message.role | message.content                  |
      | message    |                        | user         | Signal the lighthouse keeper.    |
      | message    |                        | assistant    | Two long flashes sent.           |
      | compaction | Lighthouse signalling. |              |                                  |
      | message    |                        | user         | Log the fog bank position.       |
      | message    |                        | assistant    | Logged at the northern approach. |
    And the following model responses are queued:
      | model | type | content                    |
      | gist  | text | this is not a scene line   |
      | gist  | text | still no boundaries here   |
      | gist  | text | 1-2: Fog bank logging      |
    When isaac is run with "episodes migrate-session foggy-strait"
    Then the stdout contains "retrying span"
    And the stderr contains "span 1"
    And the stderr contains "flagged"
    And the exit code is 1
    And an episode exists for crew "cordelia" matching:
      | key    | value   |
      | status | partial |
    And that episode has scenes matching:
      | gist             | text                     |
      | Fog bank logging | #"(?s)northern approach" |
    And that episode has flagged spans matching:
      | span | raw                        |
      | 1    | #"(?s)still no boundaries" |
    And the stderr contains "flagged spans: [1]"
    Given the following model responses are queued:
      | model | type | content                        |
      | gist  | text | 1-2: Lighthouse signalling     |
    When isaac is run with "episodes migrate-session foggy-strait"
    Then the stdout contains "resumed"
    And the exit code is 0
    And an episode exists for crew "cordelia" matching:
      | key    | value  |
      | status | closed |
    And that episode has scenes matching:
      | gist                  | text                     |
      | Lighthouse signalling | #"(?s)Two long flashes"  |
      | Fog bank logging      | #"(?s)northern approach" |

  # ----- Provider errors -----

  Scenario: provider chat error aborts without flagging or retry
    Given the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the following sessions exist:
      | name        | crew     |
      | dry-powder  | cordelia |
    And session "dry-powder" has transcript:
      | type    | message.role | message.content   |
      | message | user         | Ready the powder. |
      | message | assistant    | Powder ready.     |
    And the following model responses are queued:
      | model | type  | content       |
      | gist  | error | auth-missing  |
      | gist  | text  | 1-2: must not be consumed |
    When isaac is run with "episodes migrate-session dry-powder"
    Then the stderr contains "grover"
    And the stderr contains "auth-missing"
    And the stderr does not contain "unparseable"
    And the stderr does not contain "flagged"
    And the exit code is 1
    And crew "cordelia" has 0 episodes

  # ----- Errors -----

  Scenario: unknown session id fails helpfully
    When isaac is run with "episodes migrate-session ghost-ship"
    Then the stderr contains "unknown session"
    And the stderr contains "ghost-ship"
    And the stderr does not contain "Exception"
    And the exit code is 1

  # ----- Recall-worthiness at seal (isaac-xl6h) -----

    Scenario: tilde-marked scenes seal as routine
    Given the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the following sessions exist:
      | name         | crew     |
      | tidy-rigging | cordelia |
    And session "tidy-rigging" has transcript:
      | type    | message.role | message.content                       |
      | message | user         | Load the rigging checklist skill.     |
      | message | assistant    | Checklist skill loaded.               |
      | message | user         | Why does the mainstay keep fraying?   |
      | message | assistant    | The chafe guard is mounted backwards. |
    And the following model responses are queued:
      | model | type | content                                                                                                    |
      | gist  | text | 1-2: ~ Loading the rigging checklist skill\n3-4: Diagnosed mainstay fraying: chafe guard mounted backwards |
    When isaac is run with "episodes migrate-session tidy-rigging"
    Then the exit code is 0
    And that episode has scenes matching:
      | gist                                                      | routine |
      | Loading the rigging checklist skill                       | true    |
      | Diagnosed mainstay fraying: chafe guard mounted backwards |         |

    Scenario: marker-only scenes are auto-marked routine
    Given the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the following sessions exist:
      | name        | crew     |
      | quiet-bilge | cordelia |
    And session "quiet-bilge" has transcript:
      | type    | message.role | message.toolCallId | message.content                                                                           |
      | message | user         |                    | Check the bilge pump status.                                                              |
      | message | assistant    |                    | [{"type":"toolCall","id":"call_1","name":"exec__run","arguments":{"command":"pump --status"}}] |
      | message | toolResult   | call_1             | pump nominal, 12 liters cleared                                                           |
      | message | assistant    |                    | Bilge pump is nominal.                                                                    |
    And the following model responses are queued:
      | model | type | content                                                                     |
      | gist  | text | 1-1: Bilge pump status request\n2-3: Pump tooling\n4-4: Pump nominal report |
    When isaac is run with "episodes migrate-session quiet-bilge"
    Then the exit code is 0
    And that episode has scenes matching:
      | gist                      | routine |
      | Bilge pump status request |         |
      | Pump tooling              | true    |
      | Pump nominal report       |         |

    # isaac-bh17: prompt gains (cont ...) continuation-mark instructions
  Scenario: segmentation prompt instructs routine marking and what-not-how gists
    Given the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the following sessions exist:
      | name        | crew     |
      | calm-strait | cordelia |
    And session "calm-strait" has transcript:
      | type    | message.role | message.content          |
      | message | user         | Chart the reef passage.  |
      | message | assistant    | Marked; keep to leeward. |
    And the following model responses are queued:
      | model | type | content                    |
      | gist  | text | 1-2: Reef passage charting |
    When isaac is run with "episodes migrate-session calm-strait"
    Then the exit code is 0
    And the last LLM request matches:
      | key      | value                                                                                   |
      | messages | #"(?s)(?=.*routine)(?=.*~)(?=.*evidence, not the subject)(?=.*what was accomplished)(?=.*\(cont )(?=.*resumes).*" |
