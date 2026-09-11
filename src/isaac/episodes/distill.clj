(ns isaac.episodes.distill
  "Distill transcript messages for segmentation/gisting.

   Keeps user+assistant text; collapses toolCall items to one-line markers;
   drops toolResult payloads from scene text (ordinals still count them)."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.session.transcript :as transcript]))

(def ^:private ARG_SUMMARY_MAX 80)

(defn- truncate [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 n) "…"))))

(defn- arg-summary [arguments]
  (cond
    (nil? arguments) ""
    (string? arguments)
    (let [trimmed (str/trim arguments)]
      (if (or (str/starts-with? trimmed "{") (str/starts-with? trimmed "["))
        (try
          (arg-summary (json/parse-string trimmed true))
          (catch Exception _ (truncate trimmed ARG_SUMMARY_MAX)))
        (truncate trimmed ARG_SUMMARY_MAX)))
    (map? arguments)
    (->> arguments
         (map (fn [[k v]]
                (str (if (keyword? k) (name k) (str k))
                     "="
                     (truncate (if (string? v) v (pr-str v)) 40))))
         (str/join " ")
         (#(truncate % ARG_SUMMARY_MAX)))
    :else
    (truncate (pr-str arguments) ARG_SUMMARY_MAX)))

(defn- tool-marker [call]
  (let [name (or (:name call) (get call "name") "tool")
        args (or (:arguments call) (get call "arguments"))
        summary (arg-summary args)]
    (if (str/blank? summary)
      (str "(tool " name ")")
      (str "(tool " name " " summary ")"))))

(defn- parse-content [content]
  (cond
    (nil? content) nil
    (string? content)
    (let [trimmed (str/trim content)]
      (if (and (str/starts-with? trimmed "[") (str/ends-with? trimmed "]"))
        (try
          (let [parsed (json/parse-string trimmed true)]
            (if (and (sequential? parsed) (every? map? parsed)) (vec parsed) content))
          (catch Exception _
            (try
              (let [parsed (edn/read-string trimmed)]
                (if (and (sequential? parsed) (every? map? parsed)) (vec parsed) content))
              (catch Exception _ content))))
        content))
    :else content))

(defn- content-parts [content]
  (let [content (parse-content content)]
    (cond
      (string? content) [{:kind :text :text content}]
      (and (sequential? content) (every? map? content))
      (mapv (fn [item]
              (let [type (str (or (:type item) (get item "type")))]
                (cond
                  (= "text" type)
                  {:kind :text :text (or (:text item) (get item "text") "")}
                  (= "toolCall" type)
                  {:kind :tool :call item}
                  :else
                  {:kind :other})))
            content)
      :else
      (when-let [t (transcript/content->text content)]
        [{:kind :text :text t}]))))

(defn message-text
  "Distilled text for a transcript message entry, or nil when dropped."
  [entry]
  (let [message (or (:message entry) entry)
        role    (or (:role message) (get message "role"))]
    (when-not (#{"toolResult" "tool"} role)
      (let [parts (content-parts (:content message))]
        (when (seq parts)
          (->> parts
               (keep (fn [p]
                       (case (:kind p)
                         :text (when-not (str/blank? (:text p)) (:text p))
                         :tool (tool-marker (:call p))
                         nil)))
               (str/join " ")
               str/trim
               not-empty))))))

(defn distill-entry
  "Normalize a transcript message entry for span tiling.
   Always returns a map with :id :timestamp :role :text :dropped?."
  [entry]
  (let [message (or (:message entry) {})
        role    (or (:role message) "unknown")
        text    (message-text entry)]
    {:id        (:id entry)
     :timestamp (:timestamp entry)
     :role      role
     :text      text
     :dropped?  (nil? text)}))

(defn distill-messages
  "Distill only message-typed transcript entries (skip session header/compaction)."
  [transcript]
  (->> transcript
       (filter #(= "message" (:type %)))
       (mapv distill-entry)))

(def ^:private SEGMENT_INSTRUCTIONS
  (str "You are segmenting a conversation into scenes: contiguous runs of\n"
       "messages about one topic. Below is a numbered list of messages.\n"
       "\n"
       "Output one line per scene, nothing else:\n"
       "<first>-<last>: <one-sentence gist of what was discussed or decided>\n"
       "Mark a routine scene with a leading ~ on the gist:\n"
       "<first>-<last>: ~ <gist>\n"
       "When a later scene resumes an earlier topic in this same span,\n"
       "mark the continuation after the ordinal:\n"
       "<first>-<last>: (cont <earlier-first>-<earlier-last>) <gist>\n"
       "\n"
       "A scene is routine when it is procedural mechanics with no recallable\n"
       "substance — running tests/suites, loading skills, processing\n"
       "telemetry/webhook streams, reading files to orient. Substantive scenes\n"
       "are decisions, diagnoses, fixes, designs, conversations, findings.\n"
       "\n"
       "Gists describe what was accomplished, discussed, or discovered — tool\n"
       "activity is evidence, not the subject. Write \"Testing recall weight\n"
       "precedence\" not \"Running CLI spec tests\".\n"
       "\n"
       "Every message number must fall in exactly one scene, in order,\n"
       "no gaps. Start a new scene when the topic changes. A single-message\n"
       "scene is written as e.g. \"7-7: ...\".\n"
       "Prefer several scenes over one broad scene — long conversations\n"
       "almost always contain multiple topics.\n"))

(defn format-span-prompt
  "Build the user message for one segmentation LLM call."
  [distilled-messages preceding-summary]
  (let [body (->> distilled-messages
                  (map-indexed
                    (fn [idx m]
                      (let [n (inc idx)
                            role (:role m)
                            line (if (:dropped? m)
                                   (str n ". [" role "] (dropped)")
                                   (str n ". [" role "] " (:text m)))]
                        line)))
                  (str/join "\n"))
        preamble (when-not (str/blank? preceding-summary)
                   (str "Preceding context (summary of the conversation before this point):\n"
                        preceding-summary "\n\n"))]
    (str SEGMENT_INSTRUCTIONS
         "There are " (count distilled-messages) " messages. "
         "Your final line must end at " (count distilled-messages) ".\n"
         "\n"
         preamble
         "Messages:\n"
         body)))
