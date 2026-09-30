(ns isaac.session.episodes.service-spec
  (:require
    [isaac.foundation.component.factory :as component-factory]
    [isaac.foundation.component.protocol :as component]
    [isaac.session.episodes.service]
    [isaac.session.episodes.worker :as worker]
    [speclj.core :refer :all]))

(describe "episodes component"

  (it "starts and stops the idle-seal worker"
    (let [started  (atom 0)
          stopped  (atom nil)
          instance (component-factory/create :episodes {})]
      (with-redefs [worker/start! (fn [_]
                                    (swap! started inc)
                                    {:task-id :episodes/tick})
                    worker/stop!  #(reset! stopped %)]
        (component/start instance)
        (component/stop instance)
        (should= 1 @started)
        (should= {:task-id :episodes/tick} @stopped))))

  )
