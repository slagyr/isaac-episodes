Feature: Recall — query
  `isaac recall <query>` ranks a crew's indexed scenes against a query by
  blending weighted channels: cosine over text vectors, cosine over gist
  vectors, lexical term overlap, and recency (half-life decay, default 30
  days). Weights resolve defaults -> :recall config -> CLI flags, are
  "parts" multipliers, and the blended score is normalized by their sum.
  Ties break by scene-id ascending. Output shows the per-channel
  breakdown — the retrieval-quality checkpoint needs to see WHY a scene
  ranked. A stale index is a forcing step: recall hard-errors when zero
  rows match the configured embedding model.

  Background:
    Given an Isaac root at "isaac-state"
    And the isaac EDN file "config/providers/grover.edn" exists with:
      | path | value  |
      | api  | grover |
      | auth | none   |

  # ----- Help -----

    Scenario: recall is registered and has help
    When isaac is run with "help recall"
    Then the stdout matches:
      | pattern                                      |
      | Usage: isaac recall \[options\] <query>      |
      | Rank a crew's indexed scenes against a query |
      | --crew\s+                                    |
      | -n, --top\s+                                 |
      | --w-text\s+                                  |
      | --w-gist\s+                                  |
      | --w-lex\s+                                   |
      | --w-recency\s+                               |
      | --half-life\s+                               |
    And the exit code is 0

  # ----- Hybrid ranking -----

    Scenario: ranked hits with per-channel score breakdown
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist | text |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | wine | wine |
      | 2026-03-01-1006-s2x2 | 2026-03-01T10:06:00 | 2026-03-01T10:09:00 | race | dawn |
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall wine --crew cordelia"
    Then the stdout matches:
      | pattern                                                                                          |
      | recall "wine" \(crew cordelia, model mini-embed, 2 scenes\)                                      |
      | 1\. 2026-03-01-1000-s1x1\s+score \d\.\d+\s+text 1\.0\d*\s+gist 1\.0\d*\s+lex 1\.0\d*\s+rec \d\.\d+\s+terms \[wine\] |
      | \s+wine                                                                                          |
      | 2\. 2026-03-01-1006-s2x2\s+score \d\.\d+\s+text \d\.\d+\s+gist \d\.\d+\s+lex 0\.0\d*\s+rec \d\.\d+ |
      | \s+race                                                                                          |
      | timing: index \d+ms                                                                              |
      | scenes \d+ms                                                                                     |
      | embed \d+ms                                                                                      |
      | score \d+ms                                                                                      |
      | index: 4 rows, \d+\.\d MB file, ~\d+\.\d MB heap                                                 |
    And the exit code is 0

  # ----- Lexical channel -----

    Scenario: exact identifiers surface via the lexical channel with embeddings zeroed
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist                       | text                                         |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Fixing the reef chart bean | resolved chart-7x2b by redrawing the passage |
      | 2026-03-01-1006-s2x2 | 2026-03-01T10:06:00 | 2026-03-01T10:09:00 | Wine pairing for pheasant  | a light pinot noir                           |
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall chart-7x2b --crew cordelia --w-text 0 --w-gist 0"
    Then the stdout matches:
      | pattern                               |
      | 1\. 2026-03-01-1000-s1x1\s+.*lex 1\.0 |
      | 2\. 2026-03-01-1006-s2x2\s+.*lex 0\.0 |
    And the exit code is 0

  # ----- Weight precedence -----

    Scenario: weights resolve defaults, then :recall config, then CLI flags
    Given the current time is "2026-03-10T12:00:00"
    And config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-01-10-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist   | text   |
      | 2026-01-10-1000-s1x1 | 2026-01-10T11:00:00 | 2026-01-10T12:00:00 | harbor | harbor |
      | 2026-03-10-1100-s2x2 | 2026-03-10T10:00:00 | 2026-03-10T11:00:00 | supper | grouse |
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall harbor --crew cordelia"
    Then the stdout matches:
      | pattern                  |
      | 1\. 2026-01-10-1000-s1x1 |
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}
       :recall {:weights {:recency 8}}}
      """
    When isaac is run with "recall harbor --crew cordelia"
    Then the stdout matches:
      | pattern                  |
      | 1\. 2026-03-10-1100-s2x2 |
    When isaac is run with "recall harbor --crew cordelia --w-recency 1"
    Then the stdout matches:
      | pattern                  |
      | 1\. 2026-01-10-1000-s1x1 |
    And the exit code is 0

  # ----- Recency -----

    Scenario: recency favors fresh scenes; --w-recency 0 neutralizes; --half-life reshapes
    Given the current time is "2026-03-10T12:00:00"
    And config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-01-10-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist | text |
      | 2026-01-10-1000-oldx | 2026-01-10T11:00:00 | 2026-01-10T12:00:00 | grog | grog |
      | 2026-03-10-1100-newx | 2026-03-10T10:00:00 | 2026-03-10T11:00:00 | grog | grog |
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall grog --crew cordelia"
    Then the stdout matches:
      | pattern                                |
      | 1\. 2026-03-10-1100-newx               |
      | 2\. 2026-01-10-1000-oldx\s+.*rec 0\.25 |
    When isaac is run with "recall grog --crew cordelia --w-recency 0"
    Then the stdout matches:
      | pattern                  |
      | 1\. 2026-01-10-1000-oldx |
      | 2\. 2026-03-10-1100-newx |
    When isaac is run with "recall grog --crew cordelia --half-life 60"
    Then the stdout matches:
      | pattern                               |
      | 1\. 2026-03-10-1100-newx              |
      | 2\. 2026-01-10-1000-oldx\s+.*rec 0\.5 |
    And the exit code is 0

  # ----- Missing index and model drift -----

    Scenario: missing index and model drift fail loudly; mixed rows warn
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist | text  |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | wine | pinot |
    When isaac is run with "recall wine --crew cordelia"
    Then the stderr contains "no index for crew cordelia"
    And the stderr contains "isaac episodes index"
    And the stderr does not contain "Exception"
    And the exit code is 1
    When isaac is run with "episodes index --crew cordelia"
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "maxi-embed"}}
      """
    When isaac is run with "recall wine --crew cordelia"
    Then the stderr contains "no rows for model maxi-embed"
    And the stderr contains "isaac episodes index"
    And the exit code is 1
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall wine --crew cordelia"
    Then the stderr contains "2 stale rows (mini-embed)"
    And the stderr contains "--rebuild"
    And the stdout matches:
      | pattern                  |
      | 1\. 2026-03-01-1000-s1x1 |
    And the exit code is 0

  # ----- IDF lexical weighting (isaac-74ls) -----

    Scenario: rare terms outweigh common terms in the lexical channel
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist                | text                                                   |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Reef chart repair   | resolved chart-7x2b test failures in the passage suite |
      | 2026-03-01-1006-s2x2 | 2026-03-01T10:06:00 | 2026-03-01T10:09:00 | Galley provisioning | hardtack test rations for the voyage                   |
      | 2026-03-01-1010-s3x3 | 2026-03-01T10:10:00 | 2026-03-01T10:12:00 | Watch rotation      | night watch test schedule dogged evenings              |
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall chart-7x2b test --crew cordelia --w-text 0 --w-gist 0 --w-recency 0"
    Then the stdout matches:
      | pattern                                                              |
      | 1\. 2026-03-01-1000-s1x1\s+.*lex 1\.0\d*.*terms \[chart-7x2b test\]  |
      | 2\. 2026-03-01-1006-s2x2\s+.*lex 0\.379\d*.*terms \[test\]           |
      | 3\. 2026-03-01-1010-s3x3\s+.*lex 0\.379\d*.*terms \[test\]           |
    And the exit code is 0

    Scenario: unknown query terms dilute the lexical score honestly
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist                | text                                      |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Galley provisioning | hardtack test rations for the voyage      |
      | 2026-03-01-1006-s2x2 | 2026-03-01T10:06:00 | 2026-03-01T10:09:00 | Watch rotation      | night watch test schedule dogged evenings |
      | 2026-03-01-1010-s3x3 | 2026-03-01T10:10:00 | 2026-03-01T10:12:00 | Reef charting       | test soundings along the leeward passage  |
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall whoville test --crew cordelia --w-text 0 --w-gist 0 --w-recency 0"
    Then the stdout matches:
      | pattern                                                    |
      | 1\. 2026-03-01-1000-s1x1\s+.*lex 0\.287\d*.*terms \[test\] |
      | 3\. 2026-03-01-1010-s3x3\s+.*lex 0\.287\d*.*terms \[test\] |
    And the exit code is 0

  # ----- Match floor (isaac-74ls, cosine form isaac-l1kz) -----

    Scenario: junk queries warn that nothing stands out; real matches stay silent
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist                | text                                 |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:01:00 | Reef charting       | soundings along the leeward passage  |
      | 2026-03-01-1002-s2x2 | 2026-03-01T10:02:00 | 2026-03-01T10:03:00 | Galley provisioning | hardtack rations for the voyage      |
      | 2026-03-01-1004-s3x3 | 2026-03-01T10:04:00 | 2026-03-01T10:05:00 | Watch rotation      | night watch schedule dogged evenings |
      | 2026-03-01-1006-s4x4 | 2026-03-01T10:06:00 | 2026-03-01T10:07:00 | Wine pairing        | a light pinot noir for the pheasant  |
      | 2026-03-01-1008-s5x5 | 2026-03-01T10:08:00 | 2026-03-01T10:09:00 | Signal flags        | two long flashes for the lighthouse  |
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall grog --crew cordelia --floor-cos 0.999"
    Then the stderr contains "weak matches — nothing stands out (best cos 0.9"
    And the exit code is 0
    When isaac is run with "recall \"two long flashes for the lighthouse\" --crew cordelia --floor-cos 0.999"
    Then the stderr does not contain "weak matches"
    When isaac is run with "recall lighthouse --crew cordelia --floor-cos 0.999 --w-lex 0"
    Then the stderr does not contain "weak matches"
    And the stdout matches:
      | pattern                    |
      | terms \[lighthouse\]       |
      | 2026-03-01-1008-s5x5       |
    And the exit code is 0

    Scenario: floor resolves defaults, then :recall config, then CLI flag; 0 disables
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist                | text                                |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:01:00 | Reef charting       | soundings along the leeward passage |
      | 2026-03-01-1002-s2x2 | 2026-03-01T10:02:00 | 2026-03-01T10:03:00 | Galley provisioning | hardtack rations for the voyage     |
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall grog --crew cordelia"
    Then the stderr does not contain "weak matches"
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}
       :recall {:floor-cos 0.999}}
      """
    When isaac is run with "recall grog --crew cordelia"
    Then the stderr contains "weak matches"
    When isaac is run with "recall grog --crew cordelia --floor-cos 0"
    Then the stderr does not contain "weak matches"
    And the exit code is 0

  # ----- Routine scenes (isaac-xl6h) -----

    Scenario: routine scenes surface via exact terms only
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist                      | text                                           | routine |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Wine pairing for pheasant | a light pinot noir                             |         |
      | 2026-03-01-1006-s2x2 | 2026-03-01T10:06:00 | 2026-03-01T10:09:00 | Test suite run            | pump-7q3z assertion failed during the dawn run | true    |
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall pump-7q3z --crew cordelia --w-text 0 --w-gist 0"
    Then the stdout matches:
      | pattern                                                                                        |
      | 1\. 2026-03-01-1006-s2x2\s+.*text 0\.0\d*\s+gist 0\.0\d*\s+lex 1\.0\d*.*terms \[pump-7q3z\] |
    And the exit code is 0
    When isaac is run with "recall wine --crew cordelia"
    Then the stdout matches:
      | pattern                  |
      | 1\. 2026-03-01-1000-s1x1 |
    And the stdout does not contain "2026-03-01-1006-s2x2"
    And the exit code is 0

    Scenario: zero-signal scenes never rank; a rowless index still serves lex
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist           | text                       | routine |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Watch drill    | drill log entry alpha-9k2f | true    |
      | 2026-03-01-1006-s2x2 | 2026-03-01T10:06:00 | 2026-03-01T10:09:00 | Test suite run | routine harness sweep      | true    |
    When isaac is run with "episodes index --crew cordelia"
    Then the stdout contains "0 new rows, 2 routine scenes skipped"
    When isaac is run with "recall pheasant --crew cordelia"
    Then the stdout contains "no hits"
    And the stdout does not contain "1. "
    And the exit code is 0
    When isaac is run with "recall alpha-9k2f --crew cordelia"
    Then the stdout matches:
      | pattern                                                        |
      | 1\. 2026-03-01-1000-s1x1\s+.*lex 1\.0\d*.*terms \[alpha-9k2f\] |
    And the exit code is 0
