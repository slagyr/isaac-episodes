(ns isaac.episodes.prompt-cli-spec
  (:require
    [isaac.agent.config.runtime :as runtime]
    [isaac.bridge.core :as bridge]
    [isaac.bridge.prompt-cli :as sut]
    [isaac.charge :as charge]
    [isaac.comm.protocol :as comm]
    [isaac.config.loader :as loader]
    [isaac.marigold :as marigold]
    [isaac.session.context :as session-ctx]
    [isaac.session.policy.episodes]
    [isaac.session.spec-helper :as helper]
    [isaac.session.store.spi :as store]
    [isaac.tool.builtin :as builtin]
    [speclj.core :refer :all]))

(def crew-name marigold/captain)
(def crew-soul (:soul (marigold/crew-cfg crew-name)))

(def base-opts
  {:root "/test/prompt"})

(def synthetic-config
  {:crew   {crew-name {:name crew-name :soul crew-soul :model "grover" :session-policy :episodes}}
   :models {"grover" {:alias "grover" :model "echo" :provider "grover" :context-window 32768}}})

(defn- fake-charge [request]
  (let [cfg       (:config request)
        crew-id   (or (:crew request) crew-name)
        crew-cfg  (get-in cfg [:crew crew-id])
        model-id  (:model crew-cfg)
        model-cfg (get-in cfg [:models model-id])]
    (merge request
           {:model (:model model-cfg)
            :soul  (:soul crew-cfg)})))

(describe "CLI prompt with the episodes policy"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example] (helper/with-memory-store (example)))

  (redefs-around [loader/load-config!        (fn [& _] synthetic-config)
                  loader/load-config-result  (fn [& _] {:config synthetic-config})
                  runtime/install!           (fn [_] nil)
                  builtin/register-all!      (fn [] nil)
                  charge/build               fake-charge
                  session-ctx/create-with-resolved-behavior!
                  (fn [session-key opts]
                    (store/open-session! (:session-store opts)
                                         session-key
                                         (select-keys opts [:crew :tags :cwd :origin])))])

  (it "keeps --session as the session id for an episodes crew"
    (let [used-key (atom nil)
          ss       (store/registered-store)]
      (with-redefs [bridge/dispatch! (fn [charge]
                                       (reset! used-key (:session-key charge))
                                       (comm/on-chatter (:comm charge) (:session-key charge) nil "Charted")
                                       {})]
        (with-out-str
          (should= 0 (sut/run (assoc base-opts :message "Chart the reef"
                                               :session "reef-chat" :crew crew-name)))))
      (should= "reef-chat" @used-key)
      (should-not-be-nil (store/get-session ss "reef-chat"))))

  (it "warm-routes a second prompt to the same episodes session id"
    (let [keys (atom [])]
      (with-redefs [bridge/dispatch! (fn [charge]
                                       (swap! keys conj (:session-key charge))
                                       (store/append-message! (store/registered-store)
                                                              (:session-key charge)
                                                              {:role "user" :content "x"})
                                       (store/append-message! (store/registered-store)
                                                              (:session-key charge)
                                                              {:role "assistant" :content "y"})
                                       (comm/on-chatter (:comm charge) (:session-key charge) nil "Ok")
                                       {})]
        (with-out-str
          (sut/run (assoc base-opts :message "first" :session "reef-chat" :crew crew-name))
          (sut/run (assoc base-opts :message "second" :session "reef-chat" :crew crew-name))))
      (should= ["reef-chat" "reef-chat"] @keys)))
  )
