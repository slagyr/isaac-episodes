(ns isaac.recall.inject-spec
  (:require
    [clojure.string :as str]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.recall.index :as index]
    [isaac.recall.inject :as sut]
    [isaac.session.store.memory :as memory-store]
    [isaac.session.store.spi :as session-store]
    [speclj.core :refer [around before context describe it should should-be-nil should-contain should-not should= with]]))

(def ^:private root "/tmp-recall-inject")

(def ^:private wine-scene
  {:id         "2026-03-01-1000-s1x1"
   :started-at "2026-03-01T10:00:00"
   :ended-at   "2026-03-01T10:05:00"
   :gist       "Wine pairing for pheasant"
   :text       "a light pinot noir suits roast pheasant"})

(def ^:private embed-cfg
  {:embedding {:source :provider :provider "grover" :model "mini-embed"}})

(defn- write-closed! [fs* crew episode-id scenes]
  (store/write-episode!
    fs* root
    {:id         episode-id
     :crew       crew
     :status     :closed
     :scene-ids  (mapv :id scenes)
     :started-at (:started-at (first scenes))
     :ended-at   (:ended-at (last scenes))}
    scenes))

(describe "isaac.recall.inject"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (with mem (fs/mem-fs))

  (before
    (fs/mkdirs @mem root))

  (it "formats a scene line with id, date, and gist"
    (should= "- [2026-03-01-1000-s1x1 · 2026-03-01] Wine pairing for pheasant"
             (sut/format-line wine-scene)))

  (it "renders a search block with header, gist line, and distilled text for the full tier"
    (let [block (sut/render-search-block [wine-scene] {:full 1 :gists 2})]
      (should-contain "Recalled from earlier conversations" block)
      (should-contain "recall__scene" block)
      (should-contain "[2026-03-01-1000-s1x1 · 2026-03-01] Wine pairing for pheasant" block)
      (should-contain "pinot noir suits roast pheasant" block)))

  (it "renders a lineage block gist-only under the previously-on header"
    (let [block (sut/render-lineage-block [wine-scene])]
      (should-contain "Previously in this conversation" block)
      (should-contain "recall__scene" block)
      (should-contain "[2026-03-01-1000-s1x1 · 2026-03-01] Wine pairing for pheasant" block)
      (should-not (re-find #"pinot noir" block))))

  (it "filters hits below the cosine floor unless lex anchors"
    (let [hits [{:scene-id "a" :text 0.2 :gist 0.1 :lex 0.0}
                {:scene-id "b" :text 0.99 :gist 0.5 :lex 0.0}
                {:scene-id "c" :text 0.1 :gist 0.1 :lex 0.9}]]
      (should= ["b" "c"]
               (mapv :scene-id (sut/passing-hits hits 0.5)))))

  (context "inject-on-open!"
    (with ss (memory-store/create-store root))

    (around [example]
      (nexus/-with-nested-nexus {:fs @mem :sessions {:store @ss}}
        (example)))

    (before
      (session-store/register-store! @ss)
      (write-closed! @mem "cordelia" "2026-03-01-1000-ab12" [wine-scene])
      (index/index-crew! @mem root "cordelia" embed-cfg {})
      (session-store/open-session! @ss "open-ep" {:crew "cordelia" :cwd root}))

    (it "appends a search recall user message and records refs on a cold open"
      (store/write-episode! @mem root {:id "open-ep" :crew "cordelia" :status :open
                                       :thread "supper-chat" :scene-ids []} [])
      (log/capture-logs
        (sut/inject-on-open!
          {:fs            @mem
           :root          root
           :cfg           embed-cfg
           :crew          "cordelia"
           :episode       {:id "open-ep" :crew "cordelia"}
           :query         "What wine pairs with pheasant?"
           :action        :opened
           :session-store @ss}))
      (let [ep     (store/read-episode @mem root "cordelia" "open-ep")
            trans  (session-store/get-transcript @ss "open-ep")
            msgs   (filter #(= "message" (:type %)) trans)
            block  (get-in (first msgs) [:message :content])
            text   (if (string? block) block (->> block (map :text) (str/join "\n")))]
        (should= 1 (count (:recalled-scenes ep)))
        (should= "2026-03-01-1000-s1x1" (:scene-id (first (:recalled-scenes ep))))
        (should= "2026-03-01-1000-ab12" (:origin-episode (first (:recalled-scenes ep))))
        (should-contain "Recalled from earlier conversations" text)
        (should-contain "pinot noir" text)))

    (it "appends recall onto the episode's :session-id when that is the backing store key"
      (session-store/open-session! @ss "harbor-log" {:crew "cordelia" :cwd root})
      (store/write-episode! @mem root {:id "open-ep" :crew "cordelia" :status :open
                                       :session-id "harbor-log" :thread "harbor-log" :scene-ids []} [])
      (log/capture-logs
        (sut/inject-on-open!
          {:fs            @mem
           :root          root
           :cfg           embed-cfg
           :crew          "cordelia"
           :episode       {:id "open-ep" :crew "cordelia" :session-id "harbor-log" :thread "harbor-log"}
           :query         "What wine pairs with pheasant?"
           :action        :opened
           :session-store @ss}))
      (let [trans (session-store/get-transcript @ss "harbor-log")
            msgs  (filter #(= "message" (:type %)) trans)
            block (get-in (first msgs) [:message :content])
            text  (if (string? block) block (->> block (map :text) (str/join "\n")))]
        (should= 0 (count (filter #(= "message" (:type %)) (session-store/get-transcript @ss "open-ep"))))
        (should-contain "Recalled from earlier conversations" text)
        (should-contain "recall__scene" text)))

    (it "logs :episodes/recalled with search count, lineage, top, and floor on a cold open"
      (store/write-episode! @mem root {:id "open-ep" :crew "cordelia" :status :open
                                       :thread "supper-chat" :scene-ids []} [])
      (log/capture-logs
        (sut/inject-on-open!
          {:fs            @mem
           :root          root
           :cfg           embed-cfg
           :crew          "cordelia"
           :episode       {:id "open-ep" :crew "cordelia" :thread "supper-chat"}
           :query         "What wine pairs with pheasant?"
           :action        :opened
           :session-store @ss})
        (let [entry (first (filter #(= :episodes/recalled (:event %)) @log/captured-logs))]
          (should-not (nil? entry))
          (should= :info (:level entry))
          (should= "cordelia" (:crew entry))
          (should= "open-ep" (:episode entry))
          (should= "supper-chat" (:thread entry))
          (should= 1 (:search entry))
          (should= 0 (:lineage entry))
          (should= ["2026-03-01-1000-s1x1"] (:scene-ids entry))
          (should (number? (:top entry)))
          (should (pos? (double (:top entry))))
          (should= 0.47 (:floor entry))
          (should (pos? (long (:query-chars entry)))))))

    (it "logs :episodes/recall-empty with the best score when nothing clears the floor"
      (store/write-episode! @mem root {:id "open-ep" :crew "cordelia" :status :open
                                       :thread "logs-chat" :scene-ids []} [])
      (log/capture-logs
        (sut/inject-on-open!
          {:fs            @mem
           :root          root
           :cfg           embed-cfg
           :crew          "cordelia"
           :episode       {:id "open-ep" :crew "cordelia" :thread "logs-chat"}
           :query         "How do I rotate the ship logs?"
           :action        :opened
           :session-store @ss})
        (let [entry (first (filter #(= :episodes/recall-empty (:event %)) @log/captured-logs))]
          (should-not (nil? entry))
          (should= :info (:level entry))
          (should= "cordelia" (:crew entry))
          (should= "open-ep" (:episode entry))
          (should= "logs-chat" (:thread entry))
          (should (number? (:best entry)))
          (should= 0.47 (:floor entry)))))

    (it "logs :episodes/recall-skipped at debug on a warm turn"
      (store/write-episode! @mem root {:id "open-ep" :crew "cordelia" :status :open
                                       :thread "supper-chat"} [])
      (log/capture-logs
        (sut/inject-on-open!
          {:fs @mem :root root :cfg embed-cfg :crew "cordelia"
           :episode {:id "open-ep"} :query "pheasant" :action :warm
           :session-store @ss})
        (let [entry (first (filter #(= :episodes/recall-skipped (:event %)) @log/captured-logs))]
          (should-not (nil? entry))
          (should= :debug (:level entry))
          (should= :warm (:reason entry)))))

    (it "skips injection when action is warm"
      (store/write-episode! @mem root {:id "open-ep" :crew "cordelia" :status :open
                                       :thread "supper-chat"} [])
      (log/capture-logs
        (sut/inject-on-open!
          {:fs @mem :root root :cfg embed-cfg :crew "cordelia"
           :episode {:id "open-ep"} :query "pheasant" :action :warm
           :session-store @ss}))
      (should-be-nil (:recalled-scenes (store/read-episode @mem root "cordelia" "open-ep"))))

    (it "skips quietly when embedding is unconfigured"
      (store/write-episode! @mem root {:id "open-ep" :crew "cordelia" :status :open
                                       :thread "supper-chat"} [])
      (log/capture-logs
        (sut/inject-on-open!
          {:fs @mem :root root :cfg {} :crew "cordelia"
           :episode {:id "open-ep"} :query "pheasant" :action :opened
           :session-store @ss}))
      (should-be-nil (:recalled-scenes (store/read-episode @mem root "cordelia" "open-ep"))))

    (it "skips when the embedder throws and still leaves the episode usable"
      (store/write-episode! @mem root {:id "open-ep" :crew "cordelia" :status :open
                                       :thread "supper-chat"} [])
      (log/capture-logs
        (with-redefs [isaac.recall.query/query (fn [& _] (throw (ex-info "nightbird down" {})))]
          (sut/inject-on-open!
            {:fs @mem :root root :cfg embed-cfg :crew "cordelia"
             :episode {:id "open-ep"} :query "pheasant" :action :opened
             :session-store @ss})))
      (should-be-nil (:recalled-scenes (store/read-episode @mem root "cordelia" "open-ep"))))

    (it "seeds parent gists on a chained open and does not duplicate search hits"
      (let [parent-id "2026-03-01-1000-ab12"]
        (store/write-episode! @mem root {:id "open-ep" :crew "cordelia" :status :open
                                         :thread "reef-chat" :parent-episode parent-id} [])
        (log/capture-logs
          (sut/inject-on-open!
            {:fs @mem :root root :cfg embed-cfg :crew "cordelia"
             :episode {:id "open-ep" :crew "cordelia" :parent-episode parent-id}
             :query "Back to the reef passage" :action :chained
             :session-store @ss}))
        (let [ep    (store/read-episode @mem root "cordelia" "open-ep")
              trans (session-store/get-transcript @ss "open-ep")
              text  (->> trans
                         (filter #(= "message" (:type %)))
                         (map #(get-in % [:message :content]))
                         (str/join "\n"))]
          (should= 1 (count (:recalled-scenes ep)))
          (should-contain "Previously in this conversation" text)
          (should= 1 (count (re-seq #"Wine pairing for pheasant" text))))))
    )
  )
