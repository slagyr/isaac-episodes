(ns isaac.episodes.episode-steps
  "Feature steps for episode migration assertions."
  (:require
    [clojure.string :as str]
    [clojure.edn :as edn]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.config.loader :as loader]
    [isaac.episodes.store :as store]
    [isaac.episodes.worker :as worker]
    [isaac.drive.dispatch :as drive-dispatch]
    [isaac.foundation.cli-steps :as fcli]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.recall.index :as recall-index]
    [isaac.recall.score :as score]
    [isaac.session.context :as session-ctx]
    [isaac.session.session-steps :as session-steps]
    [isaac.session.store.spi :as session-store]
    [isaac.step-tables :as match]
    [isaac.logger :as log]
    [isaac.tool.memory :as memory]
    [isaac.foundation.log-steps]))

(helper! isaac.episodes.episode-steps)

(defonce ^:private isaac-edn-ensures-root?
  (do
    (alter-var-root #'isaac.foundation.fs-steps/isaac-edn-file-exists
      (fn [orig]
        (fn [path table]
          (if-not (or (g/get :root) (g/get :runtime-root-dir))
            (session-steps/default-grover-setup)
            (session-steps/ensure-grover-provider-files!))
          (orig path table))))
    (alter-var-root #'isaac.foundation.fs-steps/directory-has-exactly-n-files
      (fn [orig]
        (fn [path n-str]
          (when (g/get :turn-future)
            (session-steps/await-turn!))
          (orig path n-str))))
    true))

(fcli/register-isaac-run-wrapper!
  (fn [thunk]
    (if-let [current-time (g/get :current-time)]
      (binding [memory/*now* current-time]
        (thunk))
      (thunk))))

(defn- root-dir []
  (or (g/get :runtime-root-dir)
      (g/get :root)))

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (mem-fs)}
    (f)))

(defn- current-episode []
  (g/get :current-episode))

(defn- first-matching-episode [eps table]
  (or (some (fn [ep]
              (let [result (match/match-object table ep)]
                (when (empty? (:failures result)) ep)))
            eps)
      (first eps)))

(defn episode-exists-for-crew-matching [crew table]
  (with-feature-fs
    (fn []
      (let [eps (store/list-episodes (mem-fs) (root-dir) crew)
            ep  (first-matching-episode eps table)]
        (g/should-not-be-nil ep)
        (g/assoc! :current-episode (assoc ep :crew crew))
        (let [result (match/match-object table ep)]
          (g/should= [] (:failures result)))))))

(defn- cell-matches?
  "Match a table cell against an actual value. Regex cells use re-find
   (substring) because scene text is multi-line and features write focused
   patterns like #\"(?s)pinot noir\". An empty cell asserts the key is
   absent (or nil/false) — used for optional frontmatter like routine."
  [expected actual]
  (let [expected (str expected)]
    (cond
      (str/blank? expected)
      (or (nil? actual) (false? actual))

      (str/starts-with? expected "#\"")
      (let [[_ pattern] (re-matches #"#\"(.+)\"" expected)]
        (boolean (re-find (re-pattern pattern) (str actual))))

      :else
      (= expected (if (keyword? actual) (name actual) (str actual))))))

(defn- ensure-current-episode!
  "Prefer remembered episode; otherwise pick the only/most recent episode on disk."
  []
  (or (current-episode)
      (with-feature-fs
        (fn []
          (let [root (root-dir)
                fs*  (mem-fs)
                crews (or (fs/children fs* (store/episodes-root root)) [])
                eps (mapcat (fn [crew]
                              (map #(assoc % :crew crew)
                                   (store/list-episodes fs* root crew)))
                            crews)
                ep (last (sort-by :id eps))]
            (when ep (g/assoc! :current-episode ep))
            ep)))))

(defn that-episode-has-scenes-matching [table]
  (with-feature-fs
    (fn []
      (let [ep (ensure-current-episode!)
            _ (g/should-not-be-nil ep)
            ;; Re-read episode so force/resume updates are visible.
            ep (or (store/read-episode (mem-fs) (root-dir) (:crew ep) (:id ep)) ep)
            _ (g/assoc! :current-episode (assoc ep :crew (:crew ep)))
            scenes (store/list-scenes (mem-fs) (root-dir) (:crew ep) (:id ep))
            headers (:headers table)
            rows (:rows table)]
        (g/should= (count rows) (count scenes))
        (doseq [[row scene] (map vector rows scenes)]
          (let [row-map (zipmap headers row)
                failures (keep (fn [[k v]]
                                 (let [actual (get scene (keyword k))]
                                   (when-not (cell-matches? v actual)
                                     (str k ": expected " (pr-str v) ", got: " (pr-str actual)))))
                               row-map)]
            (g/should= [] (vec failures))))))))

(defn scene-n-does-not-contain [n-str needle]
  (with-feature-fs
    (fn []
      (let [ep (current-episode)
            n (if (string? n-str) (parse-long n-str) n-str)
            scenes (store/list-scenes (mem-fs) (root-dir) (:crew ep) (:id ep))
            scene (nth scenes (dec n) nil)]
        (g/should-not-be-nil scene)
        (g/should-not (str/includes? (str (:text scene)) needle))))))

(defn crew-has-n-episodes [crew n-str]
  (when (g/get :turn-future)
    (session-steps/await-turn!))
  (with-feature-fs
    (fn []
      (let [n (if (string? n-str) (parse-long n-str) n-str)
            eps (store/list-episodes (mem-fs) (root-dir) crew)]
        (g/should= n (count eps))
        (when-let [ep (last (sort-by :id eps))]
          (g/assoc! :current-episode (assoc ep :crew crew)))))))

(defthen "an episode exists for crew {crew:string} matching:" isaac.episodes.episode-steps/episode-exists-for-crew-matching
  "Reads episode.edn under ~/.isaac/episodes/<crew>/ and matches key/value rows
   (supports #\"regex\" cells). Remembers the episode for subsequent scene steps.")

(defthen "that episode has scenes matching:" isaac.episodes.episode-steps/that-episode-has-scenes-matching
  "Asserts sealed scene .md files (YAML frontmatter + body) for the current
   episode, in id order. Columns: gist, text (regex ok). Count and order must
   match the table.")

(defn that-episode-has-no-sealed-scenes []
  (with-feature-fs
    (fn []
      (let [ep (or (current-episode) (ensure-current-episode!))]
        (g/should-not-be-nil ep)
        (g/should= 0 (count (store/list-scenes (mem-fs) (root-dir) (:crew ep) (:id ep))))))))

(defthen "that episode has no sealed scenes"
  isaac.episodes.episode-steps/that-episode-has-no-sealed-scenes
  "Zero scene .md files under the remembered episode's dir.")

(defthen #"scene (\d+) of that episode does not contain \"([^\"]+)\"" isaac.episodes.episode-steps/scene-n-does-not-contain
  "Absence assertion on scene N's :text (1-based).")

(defthen #"crew \"([^\"]+)\" has (\d+) episodes?" isaac.episodes.episode-steps/crew-has-n-episodes
  "Counts episode directories under episodes/<crew>/. Accepts episode/episodes.")

(defn that-episode-has-n-scenes [n]
  (with-feature-fs
    (fn []
      (let [ep (or (current-episode) (ensure-current-episode!))
            n  (if (string? n) (parse-long n) n)]
        (g/should-not-be-nil ep)
        (g/should= n (count (store/list-scenes (mem-fs) (root-dir) (:crew ep) (:id ep))))))))

(defthen "that episode has {n:int} scenes" isaac.episodes.episode-steps/that-episode-has-n-scenes
  "Count variant of 'has scenes matching'. Zero means no sealed scenes.")

(defn index-for-crew-has-row-for-gist [crew gist]
  (with-feature-fs
    (fn []
      (let [rows   (recall-index/read-index (mem-fs) (root-dir) crew)
            scenes (mapcat (fn [ep]
                             (store/list-scenes (mem-fs) (root-dir) crew (:id ep)))
                           (store/list-episodes (mem-fs) (root-dir) crew))
            scene  (some #(when (= gist (:gist %)) %) scenes)
            hit    (when scene
                     (some #(when (= (:id scene) (:scene-id %)) %) rows))]
        (g/should-not-be-nil scene)
        (g/should-not-be-nil hit)))))

(defthen "the index for crew {crew:string} has a row for gist {gist:string}"
  isaac.episodes.episode-steps/index-for-crew-has-row-for-gist
  "Looks up a sealed scene by gist and asserts the packed index has a row
   for that scene id. Live seals do not pin ids/vectors.")

(defn- parse-iso [iso]
  (let [s (if (re-find #"[zZ]|[+-]\d{2}:?\d{2}$" iso) iso (str iso "Z"))]
    (java.time.Instant/parse s)))

(defn episodes-worker-ticks-at [iso]
  (let [now (parse-iso iso)]
    (g/assoc! :current-time now)
    ;; Slice subsequent negative log assertions to this tick only. The
    ;; positive matcher looks at the full capture; without a mark, "the log
    ;; does not have entries matching" after a second tick still sees the
    ;; first tick's :episodes/closing (isaac-9tjo idle_seal).
    (g/assoc! :log-entries-mark (count (log/get-entries)))
    (with-feature-fs
      (fn []
        (binding [memory/*now* now]
          (let [cfg (or (loader/snapshot "episodes worker tick") {})]
            (nexus/-with-nested-nexus {:root (root-dir) :fs (mem-fs)
                                      :sessions {:store (session-store/registered-store)}}
              (worker/tick! {:now           now
                             :cfg           cfg
                             :root          (root-dir)
                             :fs            (mem-fs)
                             :session-store (session-store/registered-store)}))))))))

(defwhen "the episodes worker ticks at {iso:string}"
  isaac.episodes.episode-steps/episodes-worker-ticks-at
  "Sets memory/now to the given ISO instant and runs one episodes worker tick.")

(defn that-episode-has-flagged-spans-matching [table]
  "Asserts :flagged-spans on the current episode. Table columns: span, raw."
  (with-feature-fs
    (fn []
      (let [ep (or (current-episode) (ensure-current-episode!))
            _ (g/should-not-be-nil ep)
            ep (or (store/read-episode (mem-fs) (root-dir) (:crew ep) (:id ep)) ep)
            flagged (vec (or (:flagged-spans ep) []))
            headers (:headers table)
            rows (:rows table)]
        (g/should= (count rows) (count flagged))
        (doseq [[row f] (map vector rows flagged)]
          (let [row-map (zipmap headers row)
                failures (keep (fn [[k v]]
                                 (let [actual (get f (keyword k))]
                                   (when-not (cell-matches? v actual)
                                     (str k ": expected " (pr-str v) ", got: " (pr-str actual)))))
                               row-map)]
            (g/should= [] (vec failures))))))))

(defthen "that episode has flagged spans matching:" isaac.episodes.episode-steps/that-episode-has-flagged-spans-matching
  "Asserts episode :flagged-spans records (span 1-based, optional raw).")

(defn- parse-cell [s]
  (let [s (str s)]
    (cond
      ;; 17-digit episode/scene ids are opaque strings, not Longs.
      (re-matches #"\d{17}" s) s
      (re-matches #"-?\d+" s) (parse-long s)
      (re-matches #"-?\d+\.\d+" s) (parse-double s)
      (or (str/starts-with? s "[") (str/starts-with? s "{") (str/starts-with? s ":"))
      (try (edn/read-string s) (catch Exception _ s))
      :else s)))

(defn crew-has-closed-episode-on-session-with-scenes [crew episode-id session-id table]
  (with-feature-fs
    (fn []
      (let [headers (:headers table)
            rows    (:rows table)
            scenes  (mapv (fn [row]
                            (let [m (zipmap (map keyword headers) row)
                                  routine? (let [v (str (:routine m ""))]
                                             (or (= "true" v) (= "True" v)))]
                              (cond-> {:id         (:id m)
                                       :started-at (:started-at m)
                                       :ended-at   (:ended-at m)
                                       :gist       (:gist m)
                                       :text       (:text m)
                                       :start-id   (str (:id m) "-start")
                                       :end-id     (str (:id m) "-end")
                                       :seal-reason :migrate}
                                routine? (assoc :routine true))))
                          rows)
            episode {:id         episode-id
                     :crew       crew
                     :status     :closed
                     :session-id session-id
                     :thread     session-id
                     :scene-ids  (mapv :id scenes)
                     :started-at (:started-at (first scenes))
                     :ended-at   (:ended-at (last scenes))}
            ss      (or (session-store/registered-store)
                        (nexus/get-in [:sessions :store]))]
        (store/write-episode! (mem-fs) (root-dir) episode scenes)
        (when ss
          (session-ctx/create-with-resolved-behavior!
            session-id {:crew crew :cwd (root-dir) :origin {:kind :cli}
                        :session-store ss :session-policy :episodes}))
        (g/assoc! :current-episode (assoc episode :crew crew))))))

(defgiven "crew {crew:string} has a closed episode {episode-id:string} on session {sid:string} with scenes:"
  isaac.episodes.episode-steps/crew-has-closed-episode-on-session-with-scenes
  "Nested-layout fixture: writes episode.edn + scenes under the session
   and opens the backing session so recall tools can resolve it.")

(defn crew-has-closed-episode-with-scenes [crew episode-id table]
  (with-feature-fs
    (fn []
      (let [headers (:headers table)
            rows    (:rows table)
            scenes  (mapv (fn [row]
                            (let [m (zipmap (map keyword headers) row)
                                  routine? (let [v (str (:routine m ""))]
                                             (or (= "true" v) (= "True" v)))]
                              (cond-> {:id         (:id m)
                                       :started-at (:started-at m)
                                       :ended-at   (:ended-at m)
                                       :gist       (:gist m)
                                       :text       (:text m)
                                       :start-id   (str (:id m) "-start")
                                       :end-id     (str (:id m) "-end")
                                       :seal-reason :migrate}
                                routine? (assoc :routine true))))
                          rows)
            episode {:id         episode-id
                     :crew       crew
                     :status     :closed
                     :scene-ids  (mapv :id scenes)
                     :started-at (:started-at (first scenes))
                     :ended-at   (:ended-at (last scenes))}]
        (store/write-episode! (mem-fs) (root-dir) episode scenes)
        (g/assoc! :current-episode (assoc episode :crew crew))))))

(defn- vector-close?
  "Normalize expected grover ints and compare to the stored vector at 1e-4
   (stored vectors are unit vectors quantized to ints at score/VECTOR_SCALE)."
  [expected actual]
  (let [want (score/normalize-vector expected)
        got  (cond
               (score/int-array? actual)   (mapv #(/ % score/VECTOR_SCALE) actual)
               (score/float-array? actual) (vec actual)
               :else                       (vec (score/normalize-vector actual)))
        n    (count want)]
    (and (= n (count got))
         (every? (fn [i]
                   (< (Math/abs (- (double (nth want i)) (double (nth got i))))
                      1.0e-4))
                 (range n)))))

(defn index-for-crew-has-rows [crew table]
  (with-feature-fs
    (fn []
      (let [rows    (recall-index/read-index (mem-fs) (root-dir) crew)
            headers (:headers table)
            expected (mapv (fn [row]
                             (into {}
                                   (map (fn [h v]
                                          [(keyword h)
                                           (if (= "kind" h)
                                             (keyword (parse-cell v))
                                             (parse-cell v))])
                                        headers row)))
                           (:rows table))]
        (g/should= (count expected) (count rows))
        (doseq [[want got] (map vector expected rows)]
          (let [failures (keep (fn [[k v]]
                                 (let [actual (get got k)]
                                   (cond
                                     (= :vector k)
                                     (when-not (vector-close? v actual)
                                       (str k ": expected ~" (pr-str (vec (score/normalize-vector v)))
                                            ", got: " (pr-str (vec actual))))
                                     (not= v actual)
                                     (str k ": expected " (pr-str v) ", got: " (pr-str actual)))))
                               want)]
            (g/should= [] (vec failures))))))))

(defn no-index-exists-for-crew [crew]
  (with-feature-fs
    (fn []
      (g/should-not (fs/exists? (mem-fs) (recall-index/index-path (root-dir) crew))))))

(defgiven #"crew \"([^\"]+)\" has a closed episode \"([^\"]+)\" with scenes:"
  isaac.episodes.episode-steps/crew-has-closed-episode-with-scenes
  "Writes episode.edn + scene .md via store/write-episode!. Synthesizes
   start/end-ids and :seal-reason :migrate. Regex is anchored before
   'on session' so the nested-layout Given does not match this phrase.")

(defthen "the index for crew {crew:string} has rows:"
  isaac.episodes.episode-steps/index-for-crew-has-rows
  "Reads the packed index via the READ API and matches the EXACT row set
   (count included). Expected grover integer vectors are unit-normalized
   and compared at 1e-4 (int-quantized store).")

(defthen "no index exists for crew {crew:string}"
  isaac.episodes.episode-steps/no-index-exists-for-crew
  "Asserts packed index.edn is absent (no half-written file).")

(defn- recalled-target-episode []
  (with-feature-fs
    (fn []
      (let [remembered (or (current-episode) (ensure-current-episode!))
            crew (or (:crew remembered) "cordelia")
            eps  (mapv #(assoc % :crew crew)
                       (store/list-episodes (mem-fs) (root-dir) crew))
            open (last (filter #(= :open (:status %)) (sort-by :id eps)))
            ep   (or open (last (sort-by :id eps)) remembered)
            ep   (or (store/read-episode (mem-fs) (root-dir) crew (:id ep)) ep)]
        (g/assoc! :current-episode (assoc ep :crew crew))
        (assoc ep :crew crew)))))

(defn- substring-regex-table
  "Transcript match uses re-matches on string cells. Feature regexes are
   written as substring probes (#\"(?s)pinot noir...\"); wrap them so they
   still mean 'contains'."
  [table]
  (update table :rows
          (fn [rows]
            (mapv (fn [row]
                    (mapv (fn [cell]
                            (let [s (str cell)]
                              (if-let [[_ inner] (re-matches #"#\"(.+)\"" s)]
                                (if (re-find #"\.\*" inner)
                                  cell
                                  (str "#\"(?s).*" inner ".*\""))
                                cell)))
                          row))
                  rows))))

(defn that-episode-backing-session-has-transcript-matching [table]
  (let [ep (or (recalled-target-episode) (current-episode) (ensure-current-episode!))]
    (g/should-not-be-nil ep)
    (session-steps/session-transcript-matching (or (:session-id ep) (:thread ep) (:id ep))
                                               (substring-regex-table table))))

(defn that-episode-backing-session-has-transcript [table]
  (let [ep (or (current-episode) (ensure-current-episode!))]
    (g/should-not-be-nil ep)
    (session-steps/session-has-transcript (or (:session-id ep) (:thread ep) (:id ep)) table)))

(defn episodes-for-crew-on-thread-chain-by-lineage [crew thread]
  (with-feature-fs
    (fn []
      (let [eps (->> (store/list-episodes (mem-fs) (root-dir) crew)
                     (filter #(or (= thread (:thread %))
                                  (= thread (:session-id %))))
                     (sort-by :id)
                     vec)]
        (g/should (>= (count eps) 2))
        (doseq [[pred succ] (partition 2 1 eps)]
          (g/should= (:id pred) (:parent-episode succ)))))))

(defn- parse-kv-table [table]
  (into {}
        (map (fn [[k v]]
               (let [k* (keyword k)
                     v* (str v)]
                 [k* (cond
                       (re-matches #"-?\d+\.\d+" v*) (parse-double v*)
                       (re-matches #"-?\d+" v*) (parse-long v*)
                       (= "true" v*) true
                       (= "false" v*) false
                       :else v*)]))
             (or (:rows table) []))))

(defn crew-has-open-episode-on-thread-with [crew thread table]
  (with-feature-fs
    (fn []
      (let [kv      (parse-kv-table table)
            id      (or (:id kv) "2020-01-01-0000-aaaa")
            episode {:id         id
                     :crew       crew
                     :status     :open
                     :thread     thread
                     :session-id thread}
            ss      (or (session-store/registered-store)
                        (nexus/get-in [:sessions :store]))
            head    (get kv :compaction.head)
            last-in (get kv :last-input-tokens)
            create-opts (cond-> {:crew crew :cwd (root-dir) :origin {:kind :cli}
                                 :session-store ss}
                          head (assoc :compaction {:head head}))]
        (store/write-episode! (mem-fs) (root-dir) episode [])
        (when ss
          (session-ctx/create-with-resolved-behavior! thread create-opts)
          (when last-in
            (session-store/update-session! ss thread {:last-input-tokens last-in})))
        (g/assoc! :current-episode (assoc episode :crew crew))))))

(defthen "that episode's backing session has transcript matching:"
  isaac.episodes.episode-steps/that-episode-backing-session-has-transcript-matching
  "Resolves the remembered episode id and delegates to the session transcript matcher.")

(defgiven "that episode's backing session has transcript:"
  isaac.episodes.episode-steps/that-episode-backing-session-has-transcript
  "Seeds the remembered episode's backing transcript.")

(defthen "the episodes for crew {crew:string} on thread {thread:string} chain by lineage"
  isaac.episodes.episode-steps/episodes-for-crew-on-thread-chain-by-lineage
  "Orders the thread's episodes by id; each successor's :parent-episode equals its predecessor's :id.")

(defn that-episode-has-recalled-scenes [table]
  (with-feature-fs
    (fn []
      (let [ep      (recalled-target-episode)
            refs    (vec (or (:recalled-scenes ep) []))
            headers (:headers table)
            rows    (:rows table)]
        (g/should= (count rows) (count refs))
        (doseq [[row ref] (map vector rows refs)]
          (let [row-map (zipmap headers row)
                failures (keep (fn [[k v]]
                                 (let [actual (get ref (keyword k))]
                                   (when-not (cell-matches? v actual)
                                     (str k ": expected " (pr-str v) ", got: " (pr-str actual)))))
                               row-map)]
            (g/should= [] (vec failures))))))))

(defn that-episode-has-no-recalled-scenes []
  (with-feature-fs
    (fn []
      (let [ep (recalled-target-episode)]
        (g/should (empty? (or (:recalled-scenes ep) [])))))))

(defn- llm-messages-text []
  (session-steps/await-turn!)
  (let [req (or (g/get :llm-request) (drive-dispatch/last-request))]
    (pr-str (or (:messages req) []))))

(defn last-llm-request-does-not-mention-recall []
  (let [text (llm-messages-text)]
    (g/should-not (re-find #"(?i)Recalled from earlier conversations|Previously in this conversation|recall__scene" text))))

(defn last-llm-request-mentions-exactly [needle n]
  (let [text (llm-messages-text)
        n    (if (string? n) (parse-long n) n)
        hits (count (re-seq (re-pattern (java.util.regex.Pattern/quote (str needle))) text))]
    (g/should= n hits)))

(defthen "that episode has recalled scenes:"
  isaac.episodes.episode-steps/that-episode-has-recalled-scenes
  "Asserts :recalled-scenes on the most recently opened episode (scene-id, origin-episode).")

(defthen "that episode has no recalled scenes"
  isaac.episodes.episode-steps/that-episode-has-no-recalled-scenes
  "Negative twin of recalled-scenes: absent or empty refs.")

(defthen "the last LLM request does not mention recall"
  isaac.episodes.episode-steps/last-llm-request-does-not-mention-recall
  "Outbound request contains neither the recall header nor recall__scene.")

(defn log-does-not-have-entries-matching
  "Negative twin of 'the log has entries matching:'. Awaits a parked
   :turn-future first so the second send in a multi-turn scenario has
   finished, then matches only against entries logged after that send
   (first-turn :session/compaction-started must not fail the
   'second turn does not re-compact' assertion — isaac-jom5)."
  [table]
  (when-let [turn-future (g/get :turn-future)]
    (when-not (realized? turn-future)
      (session-steps/await-turn!)))
  (let [all     (log/get-entries)
        mark    (or (g/get :log-entries-mark) 0)
        entries (vec (drop mark all))
        headers (:headers table)]
    (g/assoc! :log-entries-mark (count all))
    (doseq [row (:rows table)]
      (let [result (match/match-entries {:headers headers :rows [row]} entries)]
        (g/should-not (:pass? result))))))

(defthen "the log does not have entries matching:"
  isaac.episodes.episode-steps/log-does-not-have-entries-matching
  "Negative twin of 'the log has entries matching:' (isaac-jom5).")

(defthen #"the last LLM request mentions \"([^\"]+)\" exactly (\d+) times?"
  isaac.episodes.episode-steps/last-llm-request-mentions-exactly
  "Occurrence count of a substring across last LLM request messages.")

(defgiven "crew {crew:string} has an open episode on thread {thread:string} with:"
  isaac.episodes.episode-steps/crew-has-open-episode-on-thread-with
  "Writes an :open episode record + backing session with optional compaction.head.")
