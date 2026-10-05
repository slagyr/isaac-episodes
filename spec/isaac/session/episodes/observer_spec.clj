(ns isaac.session.episodes.observer-spec
  (:require
    [isaac.session.episodes.context :as context]
    [isaac.session.episodes.observer :as observer]
    [isaac.session.episodes.store :as episode-store]
    [isaac.foundation.fs :as fs]
    [isaac.agent.llm.api.grover :as grover]
    [isaac.agent.llm.provider :as llm-provider]
    [isaac.foundation.nexus :as nexus]
    [isaac.session.episodes.recall.inject :as recall-inject]
    [isaac.agent.session.store.memory :as memory-store]
    [isaac.agent.session.store.spi :as session-store]
    [speclj.core :refer :all]))

(defn- fire [cfg event]
  ((observer/create cfg) event))

(describe "isaac.session.episodes.observer"

  (with mem (fs/mem-fs))
  (with root "/tmp-episodes-observer")
  (with ss (memory-store/create-store @root))
  (with cfg {:crew {"cordelia" {:observers [:episodes] :context-mode :episodes
                                :model "echo" :soul "You are Cordelia"}}
             :episodes {:embedding {:api "grover" :model "mini-embed"}}})

  (around [example]
    (nexus/-with-nested-nexus {:fs            @mem
                               :sessions      {:store @ss}
                               :root          @root
                               :config        (atom @cfg)}
      (example)))

  (before
    (grover/install-test-fixture!)
    (grover/reset-queue!)
    (fs/mkdirs @mem @root)
    (session-store/register-store! @ss)
    (session-store/open-session! @ss "harbor-log" {:crew "cordelia" :cwd @root}))

  (it "opens an episode on turn-started whose id is a timestamp, session id unchanged"
    (fire @cfg {:event :turn-started :session-id "harbor-log" :config @cfg})
    (let [eps (episode-store/list-episodes @mem @root "cordelia")]
      (should= 1 (count eps))
      (should= "harbor-log" (:session-id (first eps)))
      (should-not= "harbor-log" (:id (first eps)))
      (should (re-matches #"\d{17}" (:id (first eps))))))

  (it "keeps the same open episode on a warm second turn-started"
    (fire @cfg {:event :turn-started :session-id "harbor-log" :config @cfg})
    (let [first-id (:id (first (episode-store/list-episodes @mem @root "cordelia")))]
      (session-store/append-message! @ss "harbor-log" {:role "user" :content "Chart the reef"})
      (session-store/append-message! @ss "harbor-log" {:role "assistant" :content "West"})
      (fire @cfg {:event :turn-started :session-id "harbor-log" :config @cfg})
      (let [eps (episode-store/list-episodes @mem @root "cordelia")]
        (should= 1 (count eps))
        (should= first-id (:id (first eps))))))

  (it "chains a successor when the open episode is past TTL"
    (let [t0  (java.time.Instant/parse "2026-03-01T10:00:00Z")
          t1  (java.time.Instant/parse "2026-03-01T10:30:00Z")
          cfg (assoc-in @cfg [:episodes :ttl-minutes] 5)]
      (with-redefs [isaac.session.episodes.lifecycle/close-episode!
                    (fn [{:keys [fs root crew episode-id]}]
                      (let [ep     (episode-store/read-episode fs root crew episode-id)
                            closed (assoc ep :status :closed)]
                        (episode-store/write-episode! fs root closed [])
                        {:status :closed :episode closed}))]
        (binding [isaac.agent.tool.memory/*now* t0]
          (fire cfg {:event :turn-started :session-id "harbor-log" :config cfg})
          (session-store/append-message! @ss "harbor-log" {:role "user" :content "first"})
          (session-store/append-message! @ss "harbor-log" {:role "assistant" :content "one"}))
        (binding [isaac.agent.tool.memory/*now* t1]
          (fire cfg {:event :turn-started :session-id "harbor-log" :config cfg}))
        (let [eps    (episode-store/list-episodes @mem @root "cordelia")
              closed (first (filter #(= :closed (:status %)) eps))
              open   (first (filter #(= :open (:status %)) eps))]
          (should= 2 (count eps))
          (should-not-be-nil closed)
          (should-not-be-nil open)
          (should= "harbor-log" (:session-id closed))
          (should= "harbor-log" (:session-id open))
          (should= (:id closed) (:parent-episode open))))))

  (it "does not rotate the stored transcript on a cold chain"
    (with-redefs [isaac.session.episodes.lifecycle/warm? (constantly false)
                  isaac.session.episodes.lifecycle/close-episode! (fn [_] {:status :closed})]
      (fire @cfg {:event :turn-started :session-id "harbor-log" :config @cfg})
      (session-store/append-message! @ss "harbor-log" {:role "user" :content "old question"})
      (session-store/append-message! @ss "harbor-log" {:role "assistant" :content "old answer"})
      (fire @cfg {:event :turn-started :session-id "harbor-log" :config @cfg})
      (should= 2 (count (filter #(= "message" (:type %))
                                (session-store/active-transcript @ss "harbor-log"))))))

  (it "opens a successor on compaction-spliced and stamps last-input-tokens on the closed episode"
    (let [provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          cfg      (assoc-in @cfg [:episodes :gist-model] :gist)]
      (grover/enqueue! [{:type "text" :content "1-2: Reef charting"}])
      (fire cfg {:event :turn-started :session-id "harbor-log" :config cfg})
      (session-store/append-message! @ss "harbor-log" {:role "user" :content "Chart the reef passage"})
      (session-store/append-message! @ss "harbor-log" {:role "assistant" :content "Charted, keep west"})
      (session-store/update-session! @ss "harbor-log" {:last-input-tokens 85})
      (with-redefs [isaac.foundation.config.loader/snapshot (fn [_] cfg)
                    isaac.agent.config.resolve/resolve-crew-context
                    (fn [_cfg _crew _opts] {:provider provider :model "gist"})]
        (fire cfg {:event :compaction-spliced :session-id "harbor-log" :config cfg})
        (let [eps    (episode-store/list-episodes @mem @root "cordelia")
              closed (first (filter #(= :closed (:status %)) eps))
              open   (first (filter #(= :open (:status %)) eps))]
          (should= 2 (count eps))
          (should-not-be-nil closed)
          (should-not-be-nil open)
          (should= (:id closed) (:parent-episode open))
          (should= 85 (:last-input-tokens closed))))))
  )

(describe "isaac.session.episodes.context"

  (with mem (fs/mem-fs))
  (with root "/tmp-episodes-context")
  (with ss (memory-store/create-store @root))
  (with cfg {:crew {"cordelia" {:observers [:episodes] :context-mode :episodes
                                :model "echo" :soul "You are Cordelia"}}
             :episodes {:embedding {:api "grover" :model "mini-embed"}}})

  (around [example]
    (nexus/-with-nested-nexus {:fs            @mem
                               :sessions      {:store @ss}
                               :root          @root
                               :config        (atom @cfg)}
      (example)))

  (before
    (grover/install-test-fixture!)
    (grover/reset-queue!)
    (fs/mkdirs @mem @root)
    (session-store/register-store! @ss)
    (session-store/open-session! @ss "harbor-log" {:crew "cordelia" :cwd @root}))

  (it "injects recall on the first prepare of a newly opened session"
    (let [called (atom nil)]
      (with-redefs [recall-inject/inject-on-open! (fn [opts] (reset! called opts))]
        (fire @cfg {:event :turn-started :session-id "harbor-log" :config @cfg})
        (should= :opened (context/prepare-turn! @ss "harbor-log" "Which way through the reef passage?"))
        (should= :opened (:action @called))
        (should= "Which way through the reef passage?" (:query @called))
        (should= "cordelia" (:crew @called)))))

  (it "does not inject recall on a warm second prepare"
    (let [calls (atom [])]
      (with-redefs [recall-inject/inject-on-open! (fn [opts] (swap! calls conj opts))]
        (fire @cfg {:event :turn-started :session-id "harbor-log" :config @cfg})
        (context/prepare-turn! @ss "harbor-log" "Chart the reef passage")
        (session-store/append-message! @ss "harbor-log" {:role "user" :content "Chart the reef passage"})
        (session-store/append-message! @ss "harbor-log" {:role "assistant" :content "Charted, keep west"})
        (reset! calls [])
        (should= :warm (context/prepare-turn! @ss "harbor-log" "Mark the buoys"))
        (should= [] @calls))))

  (it "prefixes held recall onto the model-facing transcript without writing it to the store"
    (fire @cfg {:event :turn-started :session-id "harbor-log" :config @cfg})
    (let [open (first (filter #(= :open (:status %)) (episode-store/list-episodes @mem @root "cordelia")))]
      (episode-store/write-episode! @mem @root
        (assoc open :pending-recall "[Recalled memory]\nPast voyages")
        (episode-store/list-scenes @mem @root "cordelia" (:id open))))
    (context/prepare-turn! @ss "harbor-log" "Chart the reef")
    (session-store/append-message! @ss "harbor-log" {:role "user" :content "Chart the reef"})
    (let [stored (session-store/active-transcript @ss "harbor-log")
          seen   (context/select-transcript stored)]
      (should= "Chart the reef"
               (or (get-in (last stored) [:message :content 0 :text])
                   (get-in (last stored) [:message :content])))
      (should (re-find #"Past voyages" (str (get-in (last seen) [:message :content 0 :text])
                                           (get-in (last seen) [:message :content]))))))
  )
