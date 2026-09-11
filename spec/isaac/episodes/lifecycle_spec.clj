(ns isaac.episodes.lifecycle-spec
  (:require
    [isaac.episodes.lifecycle :as sut]
    [isaac.episodes.store :as store]
    [isaac.episodes.worker :as worker]
    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.provider :as llm-provider]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.recall.embedding :as embedding]
    [isaac.session.store.memory :as memory-store]
    [isaac.session.store.spi :as session-store]
    [isaac.tool.memory :as memory]
    [speclj.core :refer :all]))

(defn- seed-open-episode!
  [ss mem root n-pairs]
  (let [session (session-store/open-session! ss "live-seal" {:crew "cordelia" :cwd root})
        pairs   (take n-pairs
                      [["What wine pairs with pheasant?" "A light pinot noir."]
                       ["Now the regatta schedule" "First race is Saturday."]
                       ["Back to wine — dessert?" "For dessert, a late harvest."]
                       ["Where do we anchor?" "Anchorage is at the north quay."]])]
    (doseq [[u a] pairs]
      (session-store/append-message! ss (:id session) {:role "user" :content u})
      (session-store/append-message! ss (:id session) {:role "assistant" :content a}))
    (store/write-episode! mem root {:id (:id session) :crew "cordelia" :status :open
                                    :thread "supper-chat" :scene-ids []} [])
    session))

(describe "isaac.episodes.lifecycle"

  (with mem (fs/mem-fs))
  (with root "/isaac-root")
  (with ss (memory-store/create-store @root))

  (before
    (grover/install-test-fixture!)
    (grover/reset-queue!)
    (fs/mkdirs @mem @root)
    (session-store/register-store! @ss))

  (around [example]
    (nexus/-with-nested-nexus {:fs @mem :sessions {:store @ss}}
      (example)))

  (it "opens an episode with :thread, :status :open, and a backing session named by the episode id"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "ab12")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (let [ep (sut/open-episode! {:fs @mem :root @root :crew "cordelia"
                                     :thread "reef-chat" :session-store @ss
                                     :cwd "/tmp" :origin {:kind :cli}})]
          (should= :open (:status ep))
          (should= "reef-chat" (:thread ep))
          (should= "cordelia" (:crew ep))
          (should= "20260301100000000" (:id ep))
          (should-be-nil (:parent-episode ep))
          (should= "20260301100000000" (:id (session-store/get-session @ss (:id ep))))))))

  (it "opens a successor with :parent-episode"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "cd34")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T11:45:00Z")]
        (let [ep (sut/open-episode! {:fs @mem :root @root :crew "cordelia"
                                     :thread "reef-chat" :session-store @ss
                                     :parent-episode "20260301100000000"})]
          (should= "20260301100000000" (:parent-episode ep))
          (should= "reef-chat" (:thread ep))))))

  (it "seeds a successor transcript with a compaction summary as the first entry"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "ef56")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:30:00Z")]
        (let [ep (sut/open-episode! {:fs @mem :root @root :crew "cordelia"
                                     :thread "reef-chat" :session-store @ss
                                     :seed-compaction {:summary "Summary so far"}})
              transcript (session-store/get-transcript @ss (:id ep))
              entries (vec (remove #(= "session" (:type %)) transcript))]
          (should= "compaction" (:type (first entries)))
          (should= "Summary so far" (:summary (first entries)))))))

  (it "closes an episode via migrate/seal and writes :closed with scenes"
    (let [session (session-store/open-session! @ss "live-ep" {:crew "cordelia" :cwd @root})
          _ (session-store/append-message! @ss (:id session) {:role "user" :content "Chart the reef passage."})
          _ (session-store/append-message! @ss (:id session) {:role "assistant" :content "Charted, keep west."})
          _ (store/write-episode! @mem @root {:id (:id session) :crew "cordelia" :status :open
                                              :thread "reef-chat" :scene-ids []} [])
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          _ (grover/enqueue! [{:type "text" :content "1-2: Reef charting"}])
          result (sut/close-episode! {:fs @mem :root @root :crew "cordelia"
                                      :episode-id (:id session)
                                      :session-store @ss
                                      :provider provider :model "gist"})
          ep (store/read-episode @mem @root "cordelia" (:id session))
          scenes (store/list-scenes @mem @root "cordelia" (:id session))]
      (should-not= :error (:status result))
      (should= :closed (:status ep))
      (should= "reef-chat" (:thread ep))
      (should= 1 (count scenes))
      (should= "Reef charting" (:gist (first scenes)))))

  (it "closes an episode whose backing transcript lives under :session-id, not the episode id"
    (let [session (session-store/open-session! @ss "reef-chat" {:crew "cordelia" :cwd @root})
          _ (session-store/append-message! @ss (:id session) {:role "user" :content "Chart the reef passage."})
          _ (session-store/append-message! @ss (:id session) {:role "assistant" :content "Charted, keep west."})
          ep-id "2026-03-01-1000-h7us"
          _ (store/write-episode! @mem @root {:id ep-id :crew "cordelia" :status :open
                                              :thread "reef-chat" :session-id "reef-chat" :scene-ids []} [])
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          _ (grover/enqueue! [{:type "text" :content "1-2: Reef charting"}])
          result (sut/close-episode! {:fs @mem :root @root :crew "cordelia"
                                      :episode-id ep-id
                                      :session-store @ss
                                      :provider provider :model "gist"})
          ep (store/read-episode @mem @root "cordelia" ep-id)
          scenes (store/list-scenes @mem @root "cordelia" ep-id)]
      (should-not= :error (:status result))
      (should-not= :partial (:status result))
      (should= :closed (:status ep))
      (should= "reef-chat" (:session-id ep))
      (should= 1 (count scenes))
      (should= "Reef charting" (:gist (first scenes)))))

  (it "closes after an idle seal without re-segmenting already-sealed scenes"
    (let [session (session-store/open-session! @ss "reef-chat" {:crew "cordelia" :cwd @root})
          _ (session-store/append-message! @ss (:id session) {:role "user" :content "Chart the reef passage."})
          _ (session-store/append-message! @ss (:id session) {:role "assistant" :content "Charted, keep west."})
          ep-id "2026-03-01-1000-h7us"
          _ (store/write-episode! @mem @root {:id ep-id :crew "cordelia" :status :open
                                              :thread "reef-chat" :session-id "reef-chat" :scene-ids []} [])
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          _ (grover/enqueue! [{:type "text" :content "1-2: Reef passage charted"}])
          sealed (sut/maybe-seal! {:fs @mem :root @root :crew "cordelia"
                                   :episode-id ep-id
                                   :session-store @ss
                                   :provider provider :model "gist"
                                   :trigger :idle
                                   :cfg {:episodes {:gist-model :gist :seal {:idle-minutes 0}}}})
          _ (grover/enqueue! [{:type "text" :content "not a boundary line"}])
          result (sut/close-episode! {:fs @mem :root @root :crew "cordelia"
                                      :episode-id ep-id
                                      :session-store @ss
                                      :provider provider :model "gist"})
          ep (store/read-episode @mem @root "cordelia" ep-id)]
      (should= :sealed (:status sealed))
      (should-not= :partial (:status result))
      (should= :closed (:status ep))
      (should= 1 (count (store/list-scenes @mem @root "cordelia" ep-id)))))

  (it "indexes sealed scenes on close when embedding is configured"
    (let [session (session-store/open-session! @ss "idx-ep" {:crew "cordelia" :cwd @root})
          _ (session-store/append-message! @ss (:id session) {:role "user" :content "What wine pairs with pheasant?"})
          _ (session-store/append-message! @ss (:id session) {:role "assistant" :content "A light pinot noir."})
          _ (store/write-episode! @mem @root {:id (:id session) :crew "cordelia" :status :open
                                              :thread "supper-chat" :scene-ids []} [])
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          _ (grover/enqueue! [{:type "text" :content "1-2: Wine pairing for pheasant"}])
          cfg {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
          result (sut/close-episode! {:fs @mem :root @root :crew "cordelia"
                                      :episode-id (:id session)
                                      :session-store @ss
                                      :provider provider :model "gist"
                                      :cfg cfg})]
      (should= 2 (:indexed result))
      (should= :closed (:status (:episode result)))))

  (it "seals unindexed when embedding throws at close"
    (let [session (session-store/open-session! @ss "idx-fail" {:crew "cordelia" :cwd @root})
          _ (session-store/append-message! @ss (:id session) {:role "user" :content "Chart the reef."})
          _ (session-store/append-message! @ss (:id session) {:role "assistant" :content "Charted."})
          _ (store/write-episode! @mem @root {:id (:id session) :crew "cordelia" :status :open
                                              :thread "reef-chat" :scene-ids []} [])
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          _ (grover/enqueue! [{:type "text" :content "1-2: Reef charting"}])
          cfg {:embedding {:source :provider :provider "grover" :model "mini-embed"}}]
      (with-redefs [isaac.recall.index/index-crew! (fn [& _] (throw (ex-info "nightbird down" {})))]
        (let [result (sut/close-episode! {:fs @mem :root @root :crew "cordelia"
                                          :episode-id (:id session)
                                          :session-store @ss
                                          :provider provider :model "gist"
                                          :cfg cfg})
              ep (store/read-episode @mem @root "cordelia" (:id session))]
          (should= :closed (:status ep))
          (should-be-nil (:indexed result))))))

  (it "warm? is true when last message is inside the TTL"
    (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:10:00Z")]
      (should (sut/warm? [{:type "message" :timestamp "2026-03-01T10:00:00"}] 60))
      (should-not (sut/warm? [{:type "message" :timestamp "2026-03-01T09:00:00"}] 60))
      (should-not (sut/warm? [] 60))
      (should (sut/warm? [{:type "compaction" :timestamp "2026-03-01T10:00:00"}] 60))
      (should-not (sut/warm? [{:type "compaction" :timestamp "2026-03-01T09:00:00"}] 60))))

  (it "resolves an absent thread to a newly opened episode"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "gh78")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (let [resolved (sut/resolve-thread! {:fs @mem :root @root :crew "cordelia"
                                             :thread "reef-chat" :session-store @ss
                                             :cfg {:episodes {:ttl-minutes 60}}
                                             :cwd "/tmp" :origin {:kind :cli}})]
          (should= "20260301100000000" (:session-key resolved))
          (should= :opened (:action resolved))
          (should= "reef-chat" (get-in resolved [:episode :thread]))))))

  (it "resolves a warm open episode to its backing session"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "ij90")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (let [opened (sut/open-episode! {:fs @mem :root @root :crew "cordelia"
                                         :thread "reef-chat" :session-store @ss})
              _ (session-store/append-message! @ss (:id opened) {:role "user" :content "Chart"})
              _ (session-store/append-message! @ss (:id opened) {:role "assistant" :content "Aye"})]
          (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:10:00Z")]
            (let [resolved (sut/resolve-thread! {:fs @mem :root @root :crew "cordelia"
                                                 :thread "reef-chat" :session-store @ss
                                                 :cfg {:episodes {:ttl-minutes 60}}})]
              (should= (:id opened) (:session-key resolved))
              (should= :warm (:action resolved))))))))

  (it "cold-resolves by closing the open episode and opening a successor with :parent-episode"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "kl12")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (let [opened (sut/open-episode! {:fs @mem :root @root :crew "cordelia"
                                         :thread "reef-chat" :session-store @ss})
              _ (session-store/append-message! @ss (:id opened) {:role "user" :content "Chart the reef passage."})
              _ (session-store/append-message! @ss (:id opened) {:role "assistant" :content "Charted, keep west."})
              provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
              _ (grover/enqueue! [{:type "text" :content "1-2: Reef charting"}])]
          (binding [memory/*now* (java.time.Instant/parse "2026-03-01T11:45:00Z")]
            (let [resolved (sut/resolve-thread! {:fs @mem :root @root :crew "cordelia"
                                                 :thread "reef-chat" :session-store @ss
                                                 :cfg {:episodes {:ttl-minutes 60 :gist-model :gist}}
                                                 :provider provider :model "gist"})]
              (should= :chained (:action resolved))
              (should= (:id opened) (get-in resolved [:episode :parent-episode]))
              (should= :open (get-in resolved [:episode :status]))
              (should= :closed (:status (store/read-episode @mem @root "cordelia" (:id opened))))))))))

  (it "compact-close seeds the successor transcript with the summary"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "mn34")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (let [opened (sut/open-episode! {:fs @mem :root @root :crew "cordelia"
                                         :thread "reef-chat" :session-store @ss})
              _ (session-store/append-message! @ss (:id opened) {:role "user" :content "Please summarize the logging work."})
              _ (session-store/append-message! @ss (:id opened) {:role "assistant" :content "We discussed sinks and the tool loop."})
              provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
              _ (grover/enqueue! [{:type "text" :content "1-2: Logging retrospective"}])]
          (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:30:00Z")]
            (let [result (sut/compact-close! {:fs @mem :root @root :crew "cordelia"
                                              :thread "reef-chat" :session-store @ss
                                              :summary "Summary so far"
                                              :provider provider :model "gist"})
                  successor (:episode result)
                  entries (->> (session-store/get-transcript @ss (:id successor))
                               (remove #(= "session" (:type %)))
                               vec)]
              (should= :chained (:action result))
              (should= (:id opened) (:parent-episode successor))
              (should= "compaction" (:type (first entries)))
              (should= "Summary so far" (:summary (first entries)))
              (should= :closed (:status (store/read-episode @mem @root "cordelia" (:id opened))))))))))

  (it "compact-close reuses an already-open successor on the thread instead of opening another"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "mn34")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (let [opened (sut/open-episode! {:fs @mem :root @root :crew "cordelia"
                                         :thread "reef-chat" :session-store @ss})
              _ (session-store/append-message! @ss (:id opened) {:role "user" :content "Please summarize the logging work."})
              _ (session-store/append-message! @ss (:id opened) {:role "assistant" :content "We discussed sinks and the tool loop."})
              provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
              _ (grover/enqueue! [{:type "text" :content "1-2: Logging retrospective"}])]
          (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:30:00Z")]
            (let [first-close (sut/compact-close! {:fs @mem :root @root :crew "cordelia"
                                                   :thread "reef-chat" :session-store @ss
                                                   :summary "Summary so far"
                                                   :provider provider :model "gist"})
                  successor-id (get-in first-close [:episode :id])
                  _ (grover/enqueue! [{:type "text" :content "1-2: Logging retrospective"}])
                  second (sut/compact-close! {:fs @mem :root @root :crew "cordelia"
                                              :thread "reef-chat" :session-store @ss
                                              :episode-id (:id opened)
                                              :summary "Summary so far"
                                              :provider provider :model "gist"})
                  open-eps (->> (store/list-episodes @mem @root "cordelia")
                                (filter #(= :open (:status %))))]
              (should= :reused (:action second))
              (should= successor-id (:session-key second))
              (should= successor-id (get-in second [:episode :id]))
              (should= 1 (count open-eps))
              (should= successor-id (:id (first open-eps)))
              (should= 2 (count (store/list-episodes @mem @root "cordelia")))))))))

  (it "close-open-episodes! counts a migrate result that only carries :episode :status"
    (let [session (session-store/open-session! @ss "live-ep-count" {:crew "cordelia" :cwd @root})
          _ (session-store/append-message! @ss (:id session) {:role "user" :content "Chart the reef passage."})
          _ (session-store/append-message! @ss (:id session) {:role "assistant" :content "Charted, keep west."})
          _ (store/write-episode! @mem @root {:id (:id session) :crew "cordelia" :status :open
                                              :thread "reef-chat" :scene-ids []} [])
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          _ (grover/enqueue! [{:type "text" :content "1-2: Reef charting"}])
          result (sut/close-open-episodes! {:fs @mem :root @root :crew "cordelia"
                                            :session-store @ss
                                            :provider provider :model "gist"})]
      (should= 1 (:closed result))
      (should= :closed (:status (store/read-episode @mem @root "cordelia" (:id session))))))

  (it "resolve-thread! after an explicit close chains a successor even inside the warm window"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "op56")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (let [opened (sut/open-episode! {:fs @mem :root @root :crew "cordelia"
                                         :thread "reef-chat" :session-store @ss})
              _ (session-store/append-message! @ss (:id opened) {:role "user" :content "Chart the reef passage."})
              _ (session-store/append-message! @ss (:id opened) {:role "assistant" :content "Charted, keep west."})
              provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
              _ (grover/enqueue! [{:type "text" :content "1-2: Reef charting"}])
              closed (sut/close-open-episodes! {:fs @mem :root @root :crew "cordelia"
                                                :session-store @ss
                                                :provider provider :model "gist"})]
          (should= 1 (:closed closed))
          (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:05:00Z")]
            (let [resolved (sut/resolve-thread! {:fs @mem :root @root :crew "cordelia"
                                                 :thread "reef-chat" :session-store @ss
                                                 :cfg {:episodes {:ttl-minutes 60}}})]
              (should= :chained (:action resolved))
              (should= (:id opened) (get-in resolved [:episode :parent-episode]))
              (should= :open (get-in resolved [:episode :status]))
              (should= "reef-chat" (get-in resolved [:episode :thread]))))))))

  (it "does not delete an empty successor that is still inside the TTL window"
    (let [id "fresh-empty"]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (session-store/open-session! @ss id {:crew "cordelia" :cwd @root})
        (session-store/append-compaction! @ss id {:summary "Summary so far"})
        (store/write-episode! @mem @root {:id id :crew "cordelia" :status :open
                                          :thread "reef-chat" :started-at "2026-03-01T10:00:00"} []))
      (log/capture-logs
        (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:10:00Z")]
          (let [result (sut/maybe-close-if-cold! {:fs @mem :root @root :crew "cordelia"
                                                  :episode-id id :session-store @ss
                                                  :cfg {:episodes {:ttl-minutes 60}}})]
            (should= :skipped (:status result))
            (should= :warm (:reason result))
            (should= :open (:status (store/read-episode @mem @root "cordelia" id)))
            (should-not (some #(= :episodes/closing (:event %)) @log/captured-logs)))))))

  (it "deletes an empty cold episode (record + backing session) without an LLM pass"
    (let [id "20260301100000000"]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (session-store/open-session! @ss id {:crew "cordelia" :cwd @root :name "Reef Chat"})
        (session-store/append-compaction! @ss id {:summary "Charted the reef passage."})
        (store/write-episode! @mem @root {:id id :crew "cordelia" :status :open
                                          :thread "reef-chat" :started-at "2026-03-01T10:00:00"} []))
      (log/capture-logs
        (binding [memory/*now* (java.time.Instant/parse "2026-03-01T11:30:00Z")]
          (let [result (sut/maybe-close-if-cold! {:fs @mem :root @root :crew "cordelia"
                                                  :episode-id id :session-store @ss
                                                  :cfg {:episodes {:ttl-minutes 60}}})]
            (should= :deleted (:status result))
            (should= :empty (:reason result))
            (should-be-nil (store/read-episode @mem @root "cordelia" id))
            (should-be-nil (session-store/get-session @ss id))
            (let [events (mapv :event @log/captured-logs)]
              (should= [:episodes/closing :episodes/deleted] events)
              (should= :empty (:reason (first (filter #(= :episodes/deleted (:event %)) @log/captured-logs))))))))))

  (it "deletes an empty cold episode planted only as files (no in-memory session)"
    (let [id      "20260301100000000"
          ep-path (str @root "/episodes/cordelia/" id "/episode.edn")
          sess-dir (str @root "/sessions/" id)
          sess-edn (str sess-dir "/session.edn")
          current  (str sess-dir "/current.ednl")]
      (store/write-episode! @mem @root {:id id :crew "cordelia" :status :open
                                        :thread "reef-chat" :started-at "2026-03-01T10:00:00"} [])
      (fs/mkdirs @mem sess-dir)
      (fs/spit @mem sess-edn (pr-str {:id id :name "Reef Chat" :crew "cordelia"}))
      (fs/spit @mem current
              (str "{:type \"session\" :id \"s0\" :timestamp \"2026-03-01T10:00:00\" :crew \"cordelia\"}\n"
                   "{:type \"compaction\" :id \"c1\" :timestamp \"2026-03-01T10:00:01\" :summary \"Charted the reef passage.\"}\n"))
      (log/capture-logs
        (binding [memory/*now* (java.time.Instant/parse "2026-03-01T11:30:00Z")]
          (let [result (sut/maybe-close-if-cold! {:fs @mem :root @root :crew "cordelia"
                                                  :episode-id id :session-store @ss
                                                  :cfg {:episodes {:ttl-minutes 60}}})]
            (should= :deleted (:status result))
            (should-be-nil (store/read-episode @mem @root "cordelia" id))
            (should-not (fs/exists? @mem ep-path))
            (should-not (fs/exists? @mem sess-edn))
            (should= [:episodes/closing :episodes/deleted]
                     (mapv :event (filter #(#{:episodes/closing :episodes/deleted} (:event %))
                                          @log/captured-logs))))))))

  (it "logs :episodes/close-failed when a cold close cannot write a closed episode"
    (let [id "20260301100000000"]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (session-store/open-session! @ss id {:crew "cordelia" :cwd @root})
        (session-store/append-message! @ss id {:role "user" :content "Chart the reef."})
        (session-store/append-message! @ss id {:role "assistant" :content "Charted."})
        (store/write-episode! @mem @root {:id id :crew "cordelia" :status :open
                                          :thread "reef-chat"} []))
      (log/capture-logs
        (binding [memory/*now* (java.time.Instant/parse "2026-03-01T11:30:00Z")]
          (with-redefs [isaac.episodes.lifecycle/close-episode!
                        (fn [_] {:exit 1 :status :error :message "nothing to segment"})]
            (let [result (sut/maybe-close-if-cold! {:fs @mem :root @root :crew "cordelia"
                                                    :episode-id id :session-store @ss
                                                    :cfg {:episodes {:ttl-minutes 60}}})]
              (should= :error (:status result))
              (should= "nothing to segment" (:error result))
              (should= :open (:status (store/read-episode @mem @root "cordelia" id)))
              (let [failed (first (filter #(= :episodes/close-failed (:event %)) @log/captured-logs))]
                (should= :warn (:level failed))
                (should= "nothing to segment" (:error failed))
                (should (some #(= :episodes/closing (:event %)) @log/captured-logs))
                (should-not (some #(= :episodes/closed (:event %)) @log/captured-logs)))))))))

  (it "close-episode! deletes an empty transcript instead of migrating"
    (let [id "empty-close"]
      (session-store/open-session! @ss id {:crew "cordelia" :cwd @root})
      (session-store/append-compaction! @ss id {:summary "Summary so far"})
      (store/write-episode! @mem @root {:id id :crew "cordelia" :status :open
                                        :thread "reef-chat"} [])
      (log/capture-logs
        (let [result (sut/close-episode! {:fs @mem :root @root :crew "cordelia"
                                          :episode-id id :session-store @ss})]
          (should= :deleted (:status result))
          (should= :empty (:reason result))
          (should-be-nil (store/read-episode @mem @root "cordelia" id))
          (should-be-nil (session-store/get-session @ss id))))))

  (context "maybe-seal!"

    (it "seals all but the last scene when the unsealed tail hits :size-cap"
      (let [session  (seed-open-episode! @ss @mem @root 2)
            provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
            _        (grover/enqueue! [{:type "text" :content "1-2: Wine pairing for pheasant\n3-4: Regatta scheduling"}])
            result   (sut/maybe-seal! {:fs @mem :root @root :crew "cordelia"
                                       :episode-id (:id session)
                                       :session-store @ss
                                       :provider provider :model "gist"
                                       :cfg {:episodes {:gist-model :gist :seal {:size-cap 4}}}})
            ep       (store/read-episode @mem @root "cordelia" (:id session))
            scenes   (store/list-scenes @mem @root "cordelia" (:id session))]
        (should= :sealed (:status result))
        (should= 1 (:sealed result))
        (should= :open (:status ep))
        (should= 1 (count scenes))
        (should= "Wine pairing for pheasant" (:gist (first scenes)))))

    (it "skips when the tail is under the size cap and drift is not configured"
      (let [session (seed-open-episode! @ss @mem @root 1)
            result  (sut/maybe-seal! {:fs @mem :root @root :crew "cordelia"
                                      :episode-id (:id session)
                                      :session-store @ss
                                      :cfg {:episodes {:seal {:size-cap 4}}}})
            scenes  (store/list-scenes @mem @root "cordelia" (:id session))]
        (should= :skipped (:status result))
        (should= :no-trigger (:reason result))
        (should= 0 (count scenes))))

    (it "seals the whole tail when size-cap fires and the segmenter returns one scene"
      (let [session  (seed-open-episode! @ss @mem @root 2)
            provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
            _        (grover/enqueue! [{:type "text" :content "1-4: Wine pairing for pheasant"}])
            result   (sut/maybe-seal! {:fs @mem :root @root :crew "cordelia"
                                       :episode-id (:id session)
                                       :session-store @ss
                                       :provider provider :model "gist"
                                       :cfg {:episodes {:gist-model :gist
                                                        :seal {:size-cap 4}}}})
            scenes   (store/list-scenes @mem @root "cordelia" (:id session))]
        (should= :sealed (:status result))
        (should= :size-cap (:trigger result))
        (should= 1 (count scenes))
        (should= "Wine pairing for pheasant" (:gist (first scenes)))))

    (it "absorbs a single-scene segmentation as a no-op when only drift fired"
      (let [session  (seed-open-episode! @ss @mem @root 1)
            provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
            embed-cfg {:embedding {:source :provider :provider "grover" :model "mini-embed"}
                       :episodes  {:gist-model :gist
                                   :seal {:drift-threshold 0.999 :min-tail 2}}}
            calls    (atom 0)]
        (with-redefs [embedding/embed-texts
                      (fn [_cfg _texts]
                        (let [n (swap! calls inc)]
                          {:vectors [(if (= 1 n) [1.0 0.0 0.0 0.0] [0.0 1.0 0.0 0.0])]}))]
          (let [planted (sut/maybe-seal! {:fs @mem :root @root :crew "cordelia"
                                          :episode-id (:id session)
                                          :session-store @ss
                                          :provider provider :model "gist"
                                          :cfg embed-cfg})
                planted-ep (store/read-episode @mem @root "cordelia" (:id session))
                _ (should= :skipped (:status planted))
                _ (should (seq (:open-scene-vector planted-ep)))
                _ (session-store/append-message! @ss (:id session) {:role "user" :content "Now the regatta schedule"})
                _ (session-store/append-message! @ss (:id session) {:role "assistant" :content "First race is Saturday."})
                _ (grover/enqueue! [{:type "text" :content "1-4: Wine pairing for pheasant"}])
                result (sut/maybe-seal! {:fs @mem :root @root :crew "cordelia"
                                         :episode-id (:id session)
                                         :session-store @ss
                                         :provider provider :model "gist"
                                         :cfg embed-cfg})
                scenes (store/list-scenes @mem @root "cordelia" (:id session))]
            (should= :skipped (:status result))
            (should= :single-scene (:reason result))
            (should= 0 (count scenes))))))

    (it "seals under the size cap when the new exchange drifts from the rolling vector"
      (let [session  (seed-open-episode! @ss @mem @root 1)
            provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
            embed-cfg {:embedding {:source :provider :provider "grover" :model "mini-embed"}
                       :episodes  {:gist-model :gist
                                   :seal {:drift-threshold 0.999 :min-tail 2}}}
            calls    (atom 0)]
        (with-redefs [embedding/embed-texts
                      (fn [_cfg _texts]
                        (let [n (swap! calls inc)]
                          {:vectors [(if (= 1 n) [1.0 0.0 0.0 0.0] [0.0 1.0 0.0 0.0])]}))]
          (sut/maybe-seal! {:fs @mem :root @root :crew "cordelia"
                            :episode-id (:id session)
                            :session-store @ss
                            :provider provider :model "gist"
                            :cfg embed-cfg})
          (session-store/append-message! @ss (:id session) {:role "user" :content "Now the regatta schedule"})
          (session-store/append-message! @ss (:id session) {:role "assistant" :content "First race is Saturday."})
          (grover/enqueue! [{:type "text" :content "1-2: Wine pairing for pheasant\n3-4: Regatta scheduling"}])
          (let [result (sut/maybe-seal! {:fs @mem :root @root :crew "cordelia"
                                         :episode-id (:id session)
                                         :session-store @ss
                                         :provider provider :model "gist"
                                         :cfg embed-cfg})
                scenes (store/list-scenes @mem @root "cordelia" (:id session))]
            (should= :sealed (:status result))
            (should= :drift (:trigger result))
            (should= 1 (count scenes))
            (should= "Wine pairing for pheasant" (:gist (first scenes)))))))

    (it "does not fire drift when embedding is unconfigured; size-cap still seals"
      (let [session  (seed-open-episode! @ss @mem @root 2)
            provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
            _        (grover/enqueue! [{:type "text" :content "1-2: Wine pairing for pheasant\n3-4: Regatta scheduling"}])
            result   (sut/maybe-seal! {:fs @mem :root @root :crew "cordelia"
                                       :episode-id (:id session)
                                       :session-store @ss
                                       :provider provider :model "gist"
                                       :cfg {:episodes {:gist-model :gist
                                                        :seal {:size-cap 4
                                                               :drift-threshold 0.999
                                                               :min-tail 2}}}})
            scenes   (store/list-scenes @mem @root "cordelia" (:id session))
            ep       (store/read-episode @mem @root "cordelia" (:id session))]
        (should= :sealed (:status result))
        (should= :size-cap (:trigger result))
        (should= 1 (count scenes))
        (should-not (contains? ep :open-scene-vector))))

    (it "indexes newly sealed scenes when embedding is configured"
      (let [session  (seed-open-episode! @ss @mem @root 2)
            provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
            _        (grover/enqueue! [{:type "text" :content "1-2: Wine pairing for pheasant\n3-4: Regatta scheduling"}])
            result   (sut/maybe-seal! {:fs @mem :root @root :crew "cordelia"
                                       :episode-id (:id session)
                                       :session-store @ss
                                       :provider provider :model "gist"
                                       :cfg {:embedding {:source :provider :provider "grover" :model "mini-embed"}
                                             :episodes {:gist-model :gist :seal {:size-cap 4}}}})]
        (should= :sealed (:status result))
        (should (>= (or (:indexed result) 0) 1))))

    (it "logs and leaves the episode unharmed when segmentation throws"
      (let [session (seed-open-episode! @ss @mem @root 2)]
        (with-redefs [isaac.episodes.segment/segment-span! (fn [& _] (throw (ex-info "gist down" {})))]
          (log/capture-logs
            (let [result (sut/maybe-seal! {:fs @mem :root @root :crew "cordelia"
                                           :episode-id (:id session)
                                           :session-store @ss
                                           :provider :unused :model "gist"
                                           :cfg {:episodes {:gist-model :gist :seal {:size-cap 4}}}})
                  scenes (store/list-scenes @mem @root "cordelia" (:id session))
                  ep     (store/read-episode @mem @root "cordelia" (:id session))]
              (should= :error (:status result))
              (should= 0 (count scenes))
              (should= :open (:status ep))
              (should (some #(= :episodes/seal-failed (:event %)) @log/captured-logs)))))))

    (it "persists a rolling open-scene vector after a turn when embedding is configured"
      (let [session (seed-open-episode! @ss @mem @root 1)
            _       (sut/maybe-seal! {:fs @mem :root @root :crew "cordelia"
                                      :episode-id (:id session)
                                      :session-store @ss
                                      :cfg {:embedding {:source :provider :provider "grover" :model "mini-embed"}
                                            :episodes {:seal {:size-cap 80 :min-tail 2}}}})
            ep      (store/read-episode @mem @root "cordelia" (:id session))]
        (should (seq (:open-scene-vector ep)))
        (should= 1 (:open-scene-vector-n ep))
        (should (every? int? (:open-scene-vector ep)))))

    (it "resets the rolling vector after a successful seal"
      (let [session  (seed-open-episode! @ss @mem @root 2)
            provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
            _        (grover/enqueue! [{:type "text" :content "1-2: Wine pairing for pheasant\n3-4: Regatta scheduling"}])
            _        (sut/maybe-seal! {:fs @mem :root @root :crew "cordelia"
                                       :episode-id (:id session)
                                       :session-store @ss
                                       :provider provider :model "gist"
                                       :cfg {:embedding {:source :provider :provider "grover" :model "mini-embed"}
                                             :episodes {:gist-model :gist :seal {:size-cap 4}}}})
            ep       (store/read-episode @mem @root "cordelia" (:id session))]
        (should-not (contains? ep :open-scene-vector))
        (should-not (contains? ep :open-scene-vector-n))))
    )
  )

(describe "isaac.episodes.worker"

  (with mem (fs/mem-fs))
  (with root "/isaac-root")
  (with ss (memory-store/create-store @root))

  (before
    (grover/install-test-fixture!)
    (grover/reset-queue!)
    (fs/mkdirs @mem @root)
    (session-store/register-store! @ss))

  (around [example]
    (nexus/-with-nested-nexus {:fs @mem :sessions {:store @ss} :root @root}
      (example)))

  (it "idle-seals a quiet open episode on the tick"
    (let [session  (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
                     (seed-open-episode! @ss @mem @root 1))
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          cfg      {:crew     {"cordelia" {:session-policy :episodes :model "echo" :soul "You are Cordelia"}}
                    :episodes {:gist-model :gist :seal {:idle-minutes 3} :ttl-minutes 60}
                    :embedding {:source :provider :provider "grover" :model "mini-embed"}}]
      (grover/enqueue! [{:type "text" :content "1-2: Wine pairing for pheasant"}])
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:10:00Z")]
        (worker/tick! {:now (java.time.Instant/parse "2026-03-01T10:10:00Z")
                       :cfg cfg :fs @mem :root @root
                       :session-store @ss :provider provider :model "gist"}))
      (let [scenes (store/list-scenes @mem @root "cordelia" (:id session))
            ep     (store/read-episode @mem @root "cordelia" (:id session))]
        (should= :open (:status ep))
        (should= 1 (count scenes))
        (should= :idle (:seal-reason (first scenes)))
        (should= "Wine pairing for pheasant" (:gist (first scenes))))))

  (it "deletes an empty cold episode on the tick and does not retry it"
    (let [id "20260301100000000"
          cfg {:crew     {"cordelia" {:session-policy :episodes :model "echo" :soul "You are Cordelia"}}
               :episodes {:gist-model :gist :seal {:idle-minutes 3} :ttl-minutes 60}}]
      (store/write-episode! @mem @root {:id id :crew "cordelia" :status :open
                                        :thread "reef-chat" :started-at "2026-03-01T10:00:00"} [])
      (fs/mkdirs @mem (str @root "/sessions/" id))
      (fs/spit @mem (str @root "/sessions/" id "/session.edn")
              (pr-str {:id id :name "Reef Chat" :crew "cordelia"}))
      (fs/spit @mem (str @root "/sessions/" id "/current.ednl")
              (str "{:type \"session\" :id \"s0\" :timestamp \"2026-03-01T10:00:00\" :crew \"cordelia\"}\n"
                   "{:type \"compaction\" :id \"c1\" :timestamp \"2026-03-01T10:00:01\" :summary \"Charted.\"}\n"))
      (log/capture-logs
        (binding [memory/*now* (java.time.Instant/parse "2026-03-01T11:30:00Z")]
          (worker/tick! {:now (java.time.Instant/parse "2026-03-01T11:30:00Z")
                         :cfg cfg :fs @mem :root @root :session-store @ss}))
        (should-be-nil (store/read-episode @mem @root "cordelia" id))
        (should-not (fs/exists? @mem (str @root "/sessions/" id "/session.edn")))
        (should (some #(= :episodes/deleted (:event %)) @log/captured-logs))
        (let [after-delete (count (filter #(= :episodes/closing (:event %)) @log/captured-logs))]
          (binding [memory/*now* (java.time.Instant/parse "2026-03-01T11:31:00Z")]
            (worker/tick! {:now (java.time.Instant/parse "2026-03-01T11:31:00Z")
                           :cfg cfg :fs @mem :root @root :session-store @ss}))
          (should= after-delete
                   (count (filter #(= :episodes/closing (:event %)) @log/captured-logs)))))))

  (it "skips an in-flight episode on the tick"
    (let [session  (seed-open-episode! @ss @mem @root 1)
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          cfg      {:crew     {"cordelia" {:session-policy :episodes :model "echo" :soul "You are Cordelia"}}
                    :episodes {:gist-model :gist :seal {:idle-minutes 3} :ttl-minutes 60}
                    :embedding {:source :provider :provider "grover" :model "mini-embed"}}]
      (grover/enqueue! [{:type "text" :content "1-2: Wine pairing for pheasant"}])
      (session-store/mark-in-flight! @ss (:id session))
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:10:00Z")]
        (worker/tick! {:now (java.time.Instant/parse "2026-03-01T10:10:00Z")
                       :cfg cfg :fs @mem :root @root
                       :session-store @ss :provider provider :model "gist"}))
      (session-store/clear-in-flight! @ss (:id session))
      (should= 0 (count (store/list-scenes @mem @root "cordelia" (:id session))))
      (should= :open (:status (store/read-episode @mem @root "cordelia" (:id session))))))
  )
