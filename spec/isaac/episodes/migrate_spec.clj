(ns isaac.episodes.migrate-spec
  (:require
    [isaac.episodes.migrate :as sut]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.provider :as llm-provider]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "isaac.episodes.migrate"

  (before
    (grover/install-test-fixture!)
    (grover/reset-queue!))

  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "rebuilds the gist provider with :root so oauth token resolution works"
    (let [p  (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          p2 (#'sut/provider-with-root p "/isaac-root")]
      (should= "/isaac-root" (:root (api/config p2)))
      (should= "grover" (api/display-name p2))))

  (it "migrates a simple two-scene session (line format)"
    (let [mem (fs/instance)
          root "/isaac-root"
          session {:id "quiet-regatta" :crew "cordelia"}
          transcript [{:type "message" :id "m1" :timestamp "2026-03-01T10:00:00"
                       :message {:role "user" :content "What wine pairs with roast pheasant?"}}
                      {:type "message" :id "m2" :timestamp "2026-03-01T10:00:01"
                       :message {:role "assistant" :content "A light pinot noir."}}
                      {:type "message" :id "m3" :timestamp "2026-03-01T10:01:00"
                       :message {:role "user" :content "Now, about the regatta schedule."}}
                      {:type "message" :id "m4" :timestamp "2026-03-01T10:01:01"
                       :message {:role "assistant" :content "The first race is Saturday at dawn."}}]
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          _ (grover/enqueue! [{:type "text"
                               :content "1-2: Wine pairing for pheasant\n3-4: Regatta scheduling"}])
          result (sut/migrate-session!
                   {:fs mem :root root :session session :transcript transcript
                    :provider provider :model "gist" :force? false})]
      (should= :closed (:status result))
      (should= 0 (:exit result))
      (let [ep (store/find-by-migrated-from mem root "cordelia" "quiet-regatta")
            scenes (store/list-scenes mem root "cordelia" (:id ep))]
        (should= "quiet-regatta" (:migrated-from ep))
        (should= 2 (count scenes))
        (should= "Wine pairing for pheasant" (:gist (first scenes)))
        (should (re-find #"pinot noir" (:text (first scenes)))))))

  (it "migrates compaction-bounded spans in order"
    (let [mem (fs/instance)
          root "/isaac-root"
          session {:id "packed-galley" :crew "cordelia"}
          transcript [{:type "message" :id "m1" :timestamp "2026-03-01T10:00:00"
                       :message {:role "user" :content "How much hardtack for the voyage?"}}
                      {:type "message" :id "m2" :timestamp "2026-03-01T10:00:01"
                       :message {:role "assistant" :content "Forty pounds should do."}}
                      {:type "compaction" :summary "They planned the voyage provisions."}
                      {:type "message" :id "m3" :timestamp "2026-03-01T10:01:00"
                       :message {:role "user" :content "Now the watch rotation."}}
                      {:type "message" :id "m4" :timestamp "2026-03-01T10:01:01"
                       :message {:role "assistant" :content "Four-hour watches, dogged evenings."}}]
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          _ (grover/enqueue!
              [{:type "text" :content "1-2: Provisioning hardtack"}
               {:type "text" :content "1-2: Watch rotation"}])
          result (sut/migrate-session!
                   {:fs mem :root root :session session :transcript transcript
                    :provider provider :model "gist"})
          ep (store/find-by-migrated-from mem root "cordelia" "packed-galley")
          scenes (store/list-scenes mem root "cordelia" (:id ep))]
      (should= 0 (:exit result))
      (should= 2 (count scenes))
      (should= ["Provisioning hardtack" "Watch rotation"] (mapv :gist scenes))))

  (it "no-ops when already migrated"
    (let [mem (fs/instance)
          root "/isaac-root"
          session {:id "calm-lagoon" :crew "cordelia"}
          transcript [{:type "message" :id "m1" :timestamp "2026-03-01T10:00:00"
                       :message {:role "user" :content "Chart the reef passage."}}
                      {:type "message" :id "m2" :timestamp "2026-03-01T10:00:01"
                       :message {:role "assistant" :content "Marked; keep to leeward."}}]
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          _ (grover/enqueue! [{:type "text"
                               :content "1-2: Reef passage charting"}])
          first (sut/migrate-session!
                  {:fs mem :root root :session session :transcript transcript
                   :provider provider :model "gist"})
          second (sut/migrate-session!
                   {:fs mem :root root :session session :transcript transcript
                    :provider provider :model "gist"})]
      (should= 0 (:exit first))
      (should= 0 (:exit second))
      (should= :already-migrated (:status second))
      (should= 1 (count (store/list-episodes mem root "cordelia")))))

  (it "aborts on provider error without writing episode"
    (let [mem (fs/instance)
          root "/isaac-root"
          session {:id "dry-powder" :crew "cordelia"}
          transcript [{:type "message" :id "m1" :timestamp "2026-03-01T10:00:00"
                       :message {:role "user" :content "Ready the powder."}}
                      {:type "message" :id "m2" :timestamp "2026-03-01T10:00:01"
                       :message {:role "assistant" :content "Powder ready."}}]
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          _ (grover/enqueue! [{:type "error" :content "auth-missing"}
                              {:type "text" :content "1-2: must not be consumed"}])
          r (sut/migrate-session!
              {:fs mem :root root :session session :transcript transcript
               :provider provider :model "gist"})]
      (should= 1 (:exit r))
      (should= :error (:status r))
      (should (re-find #"auth-missing" (:message r)))
      (should (re-find #"grover" (:message r)))
      (should-not (re-find #"unparseable" (:message r)))
      (should= 0 (count (store/list-episodes mem root "cordelia")))
      ;; no retry — second queued response remains
      (should= 1 (count @@#'grover/queue))))

  (it "persists :raw on flagged spans with 1-based numbers"
    (let [mem (fs/instance)
          root "/isaac-root"
          session {:id "foggy" :crew "cordelia"}
          transcript [{:type "message" :id "m1" :timestamp "2026-03-01T10:00:00"
                       :message {:role "user" :content "a"}}
                      {:type "message" :id "m2" :timestamp "2026-03-01T10:00:01"
                       :message {:role "assistant" :content "b"}}]
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          _ (grover/enqueue! [{:type "text" :content "nope"}
                              {:type "text" :content "still nope"}])
          result (sut/migrate-session!
                   {:fs mem :root root :session session :transcript transcript
                    :provider provider :model "gist"})
          ep (:episode result)]
      (should= :partial (:status result))
      (should= 1 (:exit result))
      (should= [{:span 1 :raw "still nope"}] (:flagged-spans ep))
      (should (re-find #"flagged spans: \[1\]" (:message result)))))

  (it "skips a span already sealed onto a live episode id that is not :migrated-from"
    (let [mem (fs/instance)
          root "/isaac-root"
          session {:id "reef-chat" :crew "cordelia"}
          transcript [{:type "message" :id "m1" :timestamp "2026-03-01T10:00:00"
                       :message {:role "user" :content "Chart the reef passage."}}
                      {:type "message" :id "m2" :timestamp "2026-03-01T10:00:01"
                       :message {:role "assistant" :content "Charted, keep west."}}]
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          live-id "2026-03-01-1000-h7us"
          sealed {:id "s1" :start-id "m1" :end-id "m2"
                  :gist "Reef passage charted" :text "Charted, keep west."
                  :seal-reason :idle}]
      (store/write-episode! mem root {:id live-id :crew "cordelia" :status :open
                                      :thread "reef-chat" :session-id "reef-chat"
                                      :migrated-from live-id
                                      :scene-ids ["s1"]}
                            [sealed])
      (let [result (sut/migrate-session!
                     {:fs mem :root root :session session :transcript transcript
                      :provider provider :model "gist" :force? false
                      :episode-id live-id})
            ep (:episode result)
            scenes (store/list-scenes mem root "cordelia" live-id)]
        (should= :closed (:status result))
        (should= 0 (:exit result))
        (should= live-id (:id ep))
        (should= 1 (count scenes))
        (should= "Reef passage charted" (:gist (first scenes))))))
  )
