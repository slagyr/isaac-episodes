(ns isaac.session.policy.episodes-spec
  (:require
    [isaac.episodes.store :as episode-store]
    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.provider :as llm-provider]
    [isaac.nexus :as nexus]
    [isaac.recall.inject :as recall-inject]
    [isaac.session.policy :as policy]
    [isaac.session.store.memory :as memory-store]
    [isaac.session.store.spi :as session-store]
    [speclj.core :refer :all]))

(describe "isaac.session.policy.episodes"

  (with mem (fs/mem-fs))
  (with root "/tmp-episodes-policy")
  (with ss (memory-store/create-store @root))
  (with pol (policy/create :episodes @ss))

  (around [example]
    (nexus/-with-nested-nexus {:fs            @mem
                               :sessions      {:store @ss}
                               :root          @root
                               :config        (atom {:embedding {:source :provider :provider "grover" :model "mini-embed"}})}
      (example)))

  (before
    (grover/install-test-fixture!)
    (grover/reset-queue!)
    (fs/mkdirs @mem @root)
    (session-store/register-store! @ss))

  (it "injects recall on the first user append of a newly opened session"
    (let [called (atom nil)]
      (with-redefs [recall-inject/inject-on-open! (fn [opts] (reset! called opts))]
        (policy/open-session! @pol "harbor-log" {:crew "cordelia" :cwd @root})
        (policy/append-message! @pol "harbor-log" {:role "user" :content "Which way through the reef passage?"})
        (should= :opened (:action @called))
        (should= "Which way through the reef passage?" (:query @called))
        (should= "cordelia" (:crew @called)))))

  (it "does not inject recall on a warm second user append"
    (let [calls (atom [])]
      (with-redefs [recall-inject/inject-on-open! (fn [opts] (swap! calls conj opts))]
        (policy/open-session! @pol "harbor-log" {:crew "cordelia" :cwd @root})
        (policy/append-message! @pol "harbor-log" {:role "user" :content "Chart the reef passage"})
        (policy/append-message! @pol "harbor-log" {:role "assistant" :content "Charted, keep west"})
        (reset! calls [])
        (policy/append-message! @pol "harbor-log" {:role "user" :content "Mark the buoys"})
        (should= [] @calls))))

  (it "opens a successor container on the same session-id after compaction"
    (let [cfg      {:episodes {:gist-model :gist}}
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})]
      (grover/enqueue! [{:type "text" :content "1-2: Reef charting"}])
      (policy/open-session! @pol "reef-chat" {:crew "cordelia" :cwd @root})
      (policy/append-message! @pol "reef-chat" {:role "user" :content "Chart the reef passage"})
      (policy/append-message! @pol "reef-chat" {:role "assistant" :content "Charted, keep west"})
      (with-redefs [isaac.config.loader/snapshot (fn [_reason] cfg)
                    isaac.config.resolve/resolve-crew-context
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
      (with-redefs [isaac.config.loader/snapshot (fn [_reason] cfg)
                    isaac.config.resolve/resolve-crew-context
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
      (with-redefs [isaac.config.loader/snapshot (fn [_reason] cfg)
                    isaac.config.resolve/resolve-crew-context
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
      (with-redefs [isaac.config.loader/snapshot (fn [_reason] cfg)]
        (binding [isaac.tool.memory/*now* t0]
          (policy/open-session! @pol "lantern-room" {:crew "cordelia" :cwd @root})
          (policy/append-message! @pol "lantern-room" {:role "user" :content "first"})
          (policy/append-message! @pol "lantern-room" {:role "assistant" :content "one"}))
        (binding [isaac.tool.memory/*now* t1]
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
      (with-redefs [isaac.config.loader/snapshot (fn [_reason] cfg)
                    isaac.config.resolve/resolve-crew-context
                    (fn [_cfg _crew _opts] {:provider provider :model "gist"})]
        (policy/splice-compaction! @pol "lantern-room" {:summary "Full summary of prior"})
        (let [dir (str @root "/sessions/cordelia/lantern-room/episodes")
              kids (when (fs/exists? @mem dir) (fs/children @mem dir))]
          (should= 2 (count kids))))))
  )
