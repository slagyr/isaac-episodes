(ns isaac.session.episodes.policy-spec
  (:require
    [isaac.session.episodes.store :as episode-store]
    [isaac.foundation.fs :as fs]
    [isaac.agent.llm.api.grover :as grover]
    [isaac.agent.llm.provider :as llm-provider]
    [isaac.foundation.nexus :as nexus]
    [isaac.session.episodes.recall.inject :as recall-inject]
    [isaac.agent.session.policy :as policy]
    [isaac.session.episodes.policy :as episodes]
    [isaac.agent.session.store.memory :as memory-store]
    [isaac.agent.session.store.spi :as session-store]
    [speclj.core :refer :all]))

(describe "isaac.session.episodes.policy"

  (with mem (fs/mem-fs))
  (with root "/tmp-episodes-policy")
  (with ss (memory-store/create-store @root))
  (with pol (policy/create :episodes @ss))

  (around [example]
    (nexus/-with-nested-nexus {:fs            @mem
                               :sessions      {:store @ss}
                               :root          @root
                               :config        (atom {:episodes {:embedding {:api "grover" :model "mini-embed"}}})}
      (example)))

  (before
    (policy/register-factory! :episodes #'episodes/create)
    (grover/install-test-fixture!)
    (grover/reset-queue!)
    (fs/mkdirs @mem @root)
    (session-store/register-store! @ss))

  (it "answers no default session — naming belongs to the agent, not the policy"
    (should-be-nil (policy/default-session @pol "cordelia" {:cwd @root :origin {:kind :cli}})))

  (it "refuses to open a blank or nil session name instead of silently resolving to a stray session"
    (should-throw clojure.lang.ExceptionInfo
      (policy/open-session! @pol nil {:crew "cordelia" :cwd @root}))
    (should-throw clojure.lang.ExceptionInfo
      (policy/open-session! @pol "" {:crew "cordelia" :cwd @root})))

  (it "refuses to reopen a session id that already belongs to another crew"
    (policy/open-session! @pol "harbor-log" {:crew "cordelia" :cwd @root})
    (should-throw clojure.lang.ExceptionInfo
      (policy/open-session! @pol "harbor-log" {:crew "marvin" :cwd @root})))

  (it "still reopens the same session for the same crew (warm reopen is not a collision)"
    (let [first  (policy/open-session! @pol "harbor-log" {:crew "cordelia" :cwd @root})
          second (policy/open-session! @pol "harbor-log" {:crew "cordelia" :cwd @root})]
      (should= (:id first) (:id second))))

  (it "keeps the session id it is handed, episode id stays a timestamp"
    (let [entry (policy/open-session! @pol "harbor-log" {:crew "cordelia" :cwd @root})
          _     (policy/append-message! @pol "harbor-log" {:role "user" :content "Which way through the reef passage?"})
          eps   (episode-store/list-episodes @mem @root "cordelia")]
      (should= "harbor-log" (:id entry))
      (should= 1 (count eps))
      (should= "harbor-log" (:session-id (first eps)))
      (should-not= "harbor-log" (:id (first eps)))
      (should (re-matches #"\d{17}" (:id (first eps))))))

  (it "injects recall on the first user append of a newly opened session"
    (let [called (atom nil)]
      (with-redefs [recall-inject/inject-on-open! (fn [opts] (reset! called opts))]
        (policy/open-session! @pol "harbor-log" {:crew "cordelia" :cwd @root})
        (policy/append-message! @pol "harbor-log" {:role "user" :content "Which way through the reef passage?"})
        (should= :opened (:action @called))
        (should= "Which way through the reef passage?" (:query @called))
        (should= "cordelia" (:crew @called)))))

  (it "prefixes held recall only to the next user prompt and clears it"
    ;; The held block lives on the open episode record, not the agent's
    ;; session record (isaac-klcb), so seed it there once the episode
    ;; container exists.
    (policy/open-session! @pol "harbor-log" {:crew "cordelia" :cwd @root})
    (policy/append-message! @pol "harbor-log" {:role "assistant" :content "Ready"})
    (let [open? #(= :open (:status %))
          open  (first (filter open? (episode-store/list-episodes @mem @root "cordelia")))]
      (episode-store/write-episode! @mem @root
        (assoc open :pending-recall "[Recalled memory]\nPast voyages")
        (episode-store/list-scenes @mem @root "cordelia" (:id open)))
      (should= "[Recalled memory]\nPast voyages"
               (:pending-recall (episode-store/read-episode @mem @root "cordelia" (:id open)))))
    (policy/append-message! @pol "harbor-log" {:role "user" :content "Chart the reef"})
    (should= "[Recalled memory]\nPast voyages\n\nChart the reef"
             (get-in (last (session-store/get-transcript @ss "harbor-log")) [:message :content 0 :text]))
    (let [open (first (filter #(= :open (:status %)) (episode-store/list-episodes @mem @root "cordelia")))]
      (should-be-nil (:pending-recall open)))
    (policy/append-message! @pol "harbor-log" {:role "user" :content "Mark the buoys"})
    (should= "Mark the buoys"
             (get-in (last (session-store/get-transcript @ss "harbor-log")) [:message :content 0 :text])))

  (it "does not inject recall on a warm second user append"
    (let [calls (atom [])]
      (with-redefs [recall-inject/inject-on-open! (fn [opts] (swap! calls conj opts))]
        (policy/open-session! @pol "harbor-log" {:crew "cordelia" :cwd @root})
        (policy/append-message! @pol "harbor-log" {:role "user" :content "Chart the reef passage"})
        (policy/append-message! @pol "harbor-log" {:role "assistant" :content "Charted, keep west"})
        (reset! calls [])
        (policy/append-message! @pol "harbor-log" {:role "user" :content "Mark the buoys"})
        (should= [] @calls))))

  (it "preserves a warm episode when preparing the next turn"
    (policy/open-session! @pol "reef-chat" {:crew "cordelia" :cwd @root})
    (policy/append-message! @pol "reef-chat" {:role "user" :content "old question"})
    (policy/append-message! @pol "reef-chat" {:role "assistant" :content "old answer"})
    (session-store/update-session! @ss "reef-chat" {:last-input-tokens 165})
    (should= :warm (policy/prepare-turn! @pol "reef-chat" "next"))
    (should= 165 (:last-input-tokens (session-store/get-session @ss "reef-chat")))
    (should= 2 (count (filter #(= "message" (:type %)) (session-store/active-transcript @ss "reef-chat")))))

  (it "closes a cold episode, rotates the transcript, and preserves its history"
    (policy/open-session! @pol "reef-chat" {:crew "cordelia" :cwd @root})
    (policy/append-message! @pol "reef-chat" {:role "user" :content "old question"})
    (policy/append-message! @pol "reef-chat" {:role "assistant" :content "old answer"})
    (session-store/update-session! @ss "reef-chat" {:last-input-tokens 900 :tally-after-id "old"})
    (with-redefs [isaac.session.episodes.lifecycle/warm? (constantly false)
                  isaac.session.episodes.lifecycle/close-episode! (fn [_] {:status :closed})]
      (should= :chained (policy/prepare-turn! @pol "reef-chat" "new question")))
    (should= [] (session-store/active-transcript @ss "reef-chat"))
    (should= "old answer" (get-in (last (session-store/chronicle-transcript @ss "reef-chat")) [:message :content]))
    (should= 0 (:last-input-tokens (session-store/get-session @ss "reef-chat")))
    (should-be-nil (:tally-after-id (session-store/get-session @ss "reef-chat"))))

  (it "opens a successor container on the same session-id after compaction"
    (let [cfg      {:episodes {:gist-model :gist}}
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})]
      (grover/enqueue! [{:type "text" :content "1-2: Reef charting"}])
      (policy/open-session! @pol "reef-chat" {:crew "cordelia" :cwd @root})
      (policy/append-message! @pol "reef-chat" {:role "user" :content "Chart the reef passage"})
      (policy/append-message! @pol "reef-chat" {:role "assistant" :content "Charted, keep west"})
      (with-redefs [isaac.foundation.config.loader/snapshot (fn [_reason] cfg)
                    isaac.agent.config.resolve/resolve-crew-context
                    (fn [_cfg _crew _opts] {:provider provider :model "gist"})]
        (let [spliced (policy/splice-compaction! @pol "reef-chat" {:summary "Summary so far"})
              eps     (->> (episode-store/list-episodes @mem @root "cordelia")
                           vec)
              closed  (first (filter #(= :closed (:status %)) eps))
              open    (first (filter #(= :open (:status %)) eps))]
          (should= 2 (count eps))
          (should-not-be-nil closed)
          (should-not-be-nil open)
          (should= "reef-chat" (:session-id closed))
          (should= "reef-chat" (:session-id open))
          (should= (:id closed) (:parent-episode open))
          (should= (:id open) (:successor-container spliced))))))

  (it "keeps the closed episode's last-input-tokens from the session"
    (let [cfg      {:episodes {:gist-model :gist}}
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})]
      (grover/enqueue! [{:type "text" :content "1-2: Reef charting"}])
      (policy/open-session! @pol "reef-chat" {:crew "cordelia" :cwd @root})
      (policy/append-message! @pol "reef-chat" {:role "user" :content "Chart the reef passage"})
      (policy/append-message! @pol "reef-chat" {:role "assistant" :content "Charted, keep west"})
      (session-store/update-session! @ss "reef-chat" {:last-input-tokens 85})
      (with-redefs [isaac.foundation.config.loader/snapshot (fn [_reason] cfg)
                    isaac.agent.config.resolve/resolve-crew-context
                    (fn [_cfg _crew _opts] {:provider provider :model "gist"})]
        (policy/splice-compaction! @pol "reef-chat" {:summary "Summary so far"})
        (let [closed (->> (episode-store/list-episodes @mem @root "cordelia")
                          (filter #(= :closed (:status %)))
                          first)]
          (should= 85 (:last-input-tokens closed))))))

  (it "materializes a closed episode when compacting a session that has no open container"
    (let [cfg      {:episodes {:gist-model :gist}}
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})]
      (grover/enqueue! [{:type "text" :content "1-2: Reef charting"}])
      (policy/open-session! @pol "reef-chat" {:crew "cordelia" :cwd @root})
      (session-store/append-message! @ss "reef-chat" {:role "user" :content "old message one"})
      (session-store/append-message! @ss "reef-chat" {:role "assistant" :content "old response one"})
      (session-store/update-session! @ss "reef-chat" {:last-input-tokens 85})
      (with-redefs [isaac.foundation.config.loader/snapshot (fn [_reason] cfg)
                    isaac.agent.config.resolve/resolve-crew-context
                    (fn [_cfg _crew _opts] {:provider provider :model "gist"})]
        (policy/splice-compaction! @pol "reef-chat" {:summary "Summary so far"})
        (let [eps    (episode-store/list-episodes @mem @root "cordelia")
              closed (first (filter #(= :closed (:status %)) eps))
              open   (first (filter #(= :open (:status %)) eps))]
          (should= 2 (count eps))
          (should-not-be-nil closed)
          (should-not-be-nil open)
          (should= 85 (:last-input-tokens closed))
          (should= (:id closed) (:parent-episode open))))))

  (it "opens a successor container when the open episode is past TTL"
    (let [cfg {:episodes {:ttl-minutes 5}}
          t0  (java.time.Instant/parse "2026-03-01T10:00:00Z")
          t1  (java.time.Instant/parse "2026-03-01T10:30:00Z")]
      (with-redefs [isaac.foundation.config.loader/snapshot (fn [_reason] cfg)]
        (binding [isaac.agent.tool.memory/*now* t0]
          (policy/open-session! @pol "lantern-room" {:crew "cordelia" :cwd @root})
          (policy/append-message! @pol "lantern-room" {:role "user" :content "first"})
          (policy/append-message! @pol "lantern-room" {:role "assistant" :content "one"}))
        (binding [isaac.agent.tool.memory/*now* t1]
          (policy/append-message! @pol "lantern-room" {:role "user" :content "second"}))
        (let [eps    (episode-store/list-episodes @mem @root "cordelia")
              closed (first (filter #(= :closed (:status %)) eps))
              open   (first (filter #(= :open (:status %)) eps))]
          (should= 2 (count eps))
          (should-not-be-nil closed)
          (should-not-be-nil open)
          (should= "lantern-room" (:session-id closed))
          (should= "lantern-room" (:session-id open))
          (should= (:id closed) (:parent-episode open))))))

  (it "writes two sibling episode dirs under sessions/<crew>/<sid>/episodes after compaction of a store-appended transcript"
    (let [cfg      {:episodes {:gist-model :gist}}
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})]
      (grover/enqueue! [{:type "text" :content "1-4: Prior voyage"}])
      (policy/open-session! @pol "lantern-room" {:crew "cordelia" :cwd @root})
      (session-store/append-message! @ss "lantern-room" {:role "user" :content "old message one"})
      (session-store/append-message! @ss "lantern-room" {:role "assistant" :content "old response one"})
      (session-store/append-message! @ss "lantern-room" {:role "user" :content "old message two"})
      (session-store/append-message! @ss "lantern-room" {:role "assistant" :content "old response two"})
      (session-store/update-session! @ss "lantern-room" {:last-input-tokens 85})
      (with-redefs [isaac.foundation.config.loader/snapshot (fn [_reason] cfg)
                    isaac.agent.config.resolve/resolve-crew-context
                    (fn [_cfg _crew _opts] {:provider provider :model "gist"})]
        (policy/splice-compaction! @pol "lantern-room" {:summary "Full summary of prior"})
        (let [dir (str @root "/sessions/cordelia/lantern-room/episodes")
              kids (when (fs/exists? @mem dir) (fs/children @mem dir))]
          (should= 2 (count kids))))))

  (it "implements every method of SessionPolicy (guard against protocol drift, isaac-rmbz)"
    (let [proto-methods (->> (:sigs policy/SessionPolicy) vals (map (comp name :name)) set)
          declared      (->> (class @pol) .getDeclaredMethods (map #(.getName %)) set)
          missing       (remove #(contains? declared (clojure.lang.Compiler/munge %)) proto-methods)]
      (should= [] missing)))
  )
