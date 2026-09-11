Feature: Episodes — index
  `isaac episodes index [--crew <crew>]` embeds sealed scenes into the
  per-crew retrieval index: <root>/episodes/<crew>/index.ednl, one row
  per (scene, kind, embedding-model) with kind "gist" or "text".
  Derived data — always rebuildable from the scene .md files.

  Model switches keep old rows deliberately: a stale index is a forcing
  step (recall hard-errors until `episodes index` runs), and `--rebuild`
  is the cleanup. Loudly broken beats quietly diminished.

  Background:
    Given an Isaac root at "isaac-state"
    And the isaac EDN file "config/providers/grover.edn" exists with:
      | path | value  |
      | api  | grover |
      | auth | none   |

  # ----- Help -----

    Scenario: index subcommand is registered and has help
    When isaac is run with "help episodes"
    Then the stdout matches:
      | pattern                                                                   |
      | Usage: isaac episodes \[subcommand\] \[options\]                          |
      | migrate-session <session-id>\s+Materialize a session as a closed episode |
      | index\s+Embed sealed scenes into the per-crew retrieval index             |
    And the exit code is 0

  # ----- Building the index -----

  # isaac-74ls: scenario text unchanged; the rows step re-grounds on the packed
  # store (read API only, normalized expected vectors, tolerance 1e-6).
    Scenario: indexing a crew embeds gist and text rows per scene
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist | text  |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | wine | pinot |
      | 2026-03-01-1006-s2x2 | 2026-03-01T10:06:00 | 2026-03-01T10:09:00 | race | dawn  |
    When isaac is run with "episodes index --crew cordelia"
    Then the stdout contains "4 new rows"
    And the exit code is 0
    And the index for crew "cordelia" has rows:
      | episode-id           | scene-id             | kind | model      | vector          |
      | 2026-03-01-1000-ab12 | 2026-03-01-1000-s1x1 | gist | mini-embed | [4 435 119 101] |
      | 2026-03-01-1000-ab12 | 2026-03-01-1000-s1x1 | text | mini-embed | [5 554 112 116] |
      | 2026-03-01-1000-ab12 | 2026-03-01-1006-s2x2 | gist | mini-embed | [4 411 114 101] |
      | 2026-03-01-1000-ab12 | 2026-03-01-1006-s2x2 | text | mini-embed | [4 426 100 110] |

  # ----- Idempotency -----

    Scenario: re-run adds nothing; --rebuild re-embeds from current scene files
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist | text  |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | wine | pinot |
    When isaac is run with "episodes index --crew cordelia"
    Then the stdout contains "2 new rows"
    When isaac is run with "episodes index --crew cordelia"
    Then the stdout contains "0 new rows"
    And the exit code is 0
    Given crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist | text |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | grog | rum  |
    When isaac is run with "episodes index --crew cordelia"
    Then the stdout contains "0 new rows"
    And the index for crew "cordelia" has rows:
      | episode-id           | scene-id             | kind | model      | vector          |
      | 2026-03-01-1000-ab12 | 2026-03-01-1000-s1x1 | gist | mini-embed | [4 435 119 101] |
      | 2026-03-01-1000-ab12 | 2026-03-01-1000-s1x1 | text | mini-embed | [5 554 112 116] |
    When isaac is run with "episodes index --crew cordelia --rebuild"
    Then the stdout contains "2 new rows"
    And the exit code is 0
    And the index for crew "cordelia" has rows:
      | episode-id           | scene-id             | kind | model      | vector          |
      | 2026-03-01-1000-ab12 | 2026-03-01-1000-s1x1 | gist | mini-embed | [4 431 103 103] |
      | 2026-03-01-1000-ab12 | 2026-03-01-1000-s1x1 | text | mini-embed | [3 340 114 109] |

  # ----- Optional capability -----

    Scenario: indexing without embedding config degrades helpfully
    Given crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist | text  |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | wine | pinot |
    When isaac is run with "episodes index --crew cordelia"
    Then the stderr contains "no embedding configured"
    And the stderr contains ":embedding"
    And the stderr does not contain "Exception"
    And the exit code is 1
    And no index exists for crew "cordelia"

  # ----- Model changes -----

    Scenario: switching embedding model embeds anew and keeps old rows
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist | text  |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | wine | pinot |
    When isaac is run with "episodes index --crew cordelia"
    Then the stdout contains "2 new rows"
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "maxi-embed"}}
      """
    When isaac is run with "episodes index --crew cordelia"
    Then the stdout contains "2 new rows"
    And the exit code is 0
    And the index for crew "cordelia" has rows:
      | episode-id           | scene-id             | kind | model      | vector          |
      | 2026-03-01-1000-ab12 | 2026-03-01-1000-s1x1 | gist | mini-embed | [4 435 119 101] |
      | 2026-03-01-1000-ab12 | 2026-03-01-1000-s1x1 | text | mini-embed | [5 554 112 116] |
      | 2026-03-01-1000-ab12 | 2026-03-01-1000-s1x1 | gist | maxi-embed | [4 435 119 101] |
      | 2026-03-01-1000-ab12 | 2026-03-01-1000-s1x1 | text | maxi-embed | [5 554 112 116] |

  # ----- Routine scenes (isaac-xl6h) -----

    Scenario: routine scenes earn no index rows
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist  | text  | routine |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | wine  | pinot |         |
      | 2026-03-01-1006-s2x2 | 2026-03-01T10:06:00 | 2026-03-01T10:09:00 | drill | rig   | true    |
    When isaac is run with "episodes index --crew cordelia"
    Then the stdout contains "2 new rows, 1 routine scene skipped"
    And the exit code is 0
    And the index for crew "cordelia" has rows:
      | episode-id           | scene-id             | kind | model      | vector          |
      | 2026-03-01-1000-ab12 | 2026-03-01-1000-s1x1 | gist | mini-embed | [4 435 119 101] |
      | 2026-03-01-1000-ab12 | 2026-03-01-1000-s1x1 | text | mini-embed | [5 554 112 116] |
