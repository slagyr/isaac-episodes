(ns isaac.session.episodes.crew-config-spec
  (:require
    [isaac.session.episodes.crew :as sut]
    [speclj.core :refer :all]))

(describe "crew episode settings"
  (it "overrides nested settings without losing global siblings"
    (should= {:seal {:idle-minutes 10 :size-cap 20}
              :recall {:half-life 7 :ledger true}}
             (:episodes (sut/config-for {:episodes {:seal {:idle-minutes 3 :size-cap 20}
                                                    :recall {:half-life 30 :ledger true}}
                                          :crew {"cordelia" {:episodes {:seal {:idle-minutes 10}
                                                                             :recall {:half-life 7}}}}}
                             "cordelia"))))
  (it "leaves another crew on global settings"
    (should= 30 (get-in (sut/config-for {:episodes {:recall {:half-life 30}}
                                         :crew {"cordelia" {:episodes {:recall {:half-life 7}}}}}
                                        "bosun") [:episodes :recall :half-life]))))
