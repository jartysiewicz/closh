(ns closh.zero.frontend.sci
  (:gen-class)
  (:require
   ; [closh.zero.compiler]
   ; [closh.zero.parser :as parser]
   ; [closh.zero.pipeline]
   ; [closh.zero.platform.eval :as eval]
   ; [closh.zero.platform.process :as process]
   ; [closh.zero.env :as env]
   ; [closh.zero.reader :as reader]
   [closh.zero.core :as closh.core]
   [closh.zero.utils.clojure-main-sci :refer [main]]
   [rebel-readline.clojure.main :refer [syntax-highlight-prn]]
   [rebel-readline.core :as core]
   [rebel-readline.clojure.line-reader :as clj-line-reader]
   [rebel-readline.jline-api :as api]
   [rebel-readline.clojure.service.local :as clj-service]
   [clojure.string :as string]
   [clojure.java.io :as jio]
   [closh.zero.env :as env]
   [closh.zero.reader]
   [closh.zero.core :as closh.core]
   [closh.zero.platform.process :refer [process?]]
   [closh.zero.platform.eval :as eval]
   [closh.zero.frontend.main :as main]
   [closh.zero.service.completion :refer [complete-shell]]
   [closh.zero.util :refer [thread-stop]]
   [closh.zero.utils.clojure-main :refer [repl-requires] :as clojure-main]
   [closh.zero.frontend.jline-history :as jline-history])
  (:import [org.jline.reader Completer ParsedLine LineReader]))

#_(defn repl-print
    [result]
    (when-not (or (nil? result)
                  (identical? result env/success)
                  (process/process? result))
      (if (or (string? result)
              (char? result))
        (print result)
        (pr result))
      (flush)))

#_(defn -main [& args]
    (reset! process/*cwd* (System/getProperty "user.dir"))
    (let [cmd (or (first args) "echo hello clojure")]
      (repl-print
       (eval/eval
        `(-> ~(closh.zero.compiler/compile-interactive
               (closh.zero.parser/parse
                (reader/read-string cmd)
                #_(edamame/parse-string-all cmd {:all true})))
             (closh.zero.pipeline/wait-for-pipeline))))))

(defn repl-prompt []
  (try
    (eval/eval '(print (closh-prompt)))
    (catch Exception e
      (println "Error printing prompt:" (:cause (Throwable->map e)))
      (println "Please check the definition of closh-prompt function in your ~/.closhrc")
      (print "$ ")))
  (let [title
        (try
          (eval/eval '(closh-title))
          (catch Exception e
            (str "closh: Error in (closh-title): " (:cause (Throwable->map e)))))]
    (.print System/out (str "\u001b]0;" title "\u0007"))))

(def opts {:prompt repl-prompt})

; rebel-readline.clojure.main/create-repl-read
(def create-repl-read
  (core/create-buffered-repl-reader-fn
   (fn [s] (clojure.lang.LineNumberingPushbackReader.
            (java.io.StringReader. s)))
   core/has-remaining?
   closh.zero.frontend.main/repl-read))

(defn repl-print
  [& args]
  (when-not (or (nil? (first args))
                (identical? (first args) env/success)
                (process? (first args)))
    (apply syntax-highlight-prn args)))

; rebel-readline.clojure.line-reader/clojure-completer
(defn clojure-completer []
  (proxy [Completer] []
    (complete [^LineReader reader ^ParsedLine line ^java.util.List candidates]
      (let [word (.word line)]
        (when (and
               (:completion @api/*line-reader*)
               (not (string/blank? word))
               (pos? (count word)))
          (let [options (let [ns' (clj-line-reader/current-ns)
                              context (clj-line-reader/complete-context line)]
                          (cond-> {}
                            ns'     (assoc :ns ns')
                            context (assoc :context context)))
                {:keys [cursor word-cursor line]} (meta line)
                paren-begin (= \( (get line (- cursor word-cursor 1)))
                shell-completions (->> (complete-shell (subs line 0 cursor))
                                       (map (fn [candidate] {:candidate candidate})))
                clj-completions (clj-line-reader/completions word options)]
            (->>
             (if paren-begin
               (concat
                clj-completions
                shell-completions)
               (concat
                shell-completions
                clj-completions))
             (map #(clj-line-reader/candidate %))
             (take 10)
             (.addAll candidates))))))))

(defn load-init-file
  "Loads init file."
  [init-path]
  (when (.isFile (jio/file init-path))
    (eval/eval `(~'load-file ~init-path))))

(defn handle-sigint-form []
  `(let [thread# (Thread/currentThread)]
     (clojure.repl/set-break-handler! (fn [signal#] (thread-stop thread#)))))

(defn repl [[_ & args] inits]
  (core/ensure-terminal
   (core/with-line-reader
     (let [line-reader (clj-line-reader/create
                        (clj-service/create
                         (when api/*line-reader* @api/*line-reader*))
                        {:completer (clojure-completer)})]
       (.setVariable line-reader LineReader/HISTORY_FILE (str (jio/file (System/getProperty "user.home") ".closh" "history")))
       (try
         (.setHistory line-reader (doto (jline-history/sqlite-history)
                                    (.moveToEnd)))
         (catch Exception e
           (binding [*out* *err*]
             (println "Error while initializing history file ~/.closh/closh.sqlite:\n" e))))
       line-reader)
     (binding [*out* (api/safe-terminal-writer api/*line-reader*)]
       (when-let [prompt-fn (:prompt opts)]
         (swap! api/*line-reader* assoc :prompt prompt-fn))
        ; (println (core/help-message))
       (apply
        clojure.main/repl
        (-> {:init (fn []
                     (clojure-main/initialize args inits)
                     (in-ns 'user)
                     (apply require repl-requires)
                     (in-ns 'user)
                     (eval/eval-closh-requires)
                     (eval/eval env/*closh-environment-init*)
                     (try
                       (load-init-file (.getCanonicalPath (jio/file (System/getProperty "user.home") ".closhrc")))
                       (catch Exception e
                         (binding [*out* *err*]
                           (println "Error while loading init file ~/.closhrc:\n" e)))))
             :print repl-print
             :read (create-repl-read)
             :eval (fn [form] (eval/eval `(do ~(handle-sigint-form) ~form)))}
            (merge opts {:prompt (fn [])})
            seq
            flatten))))))

(defn -main [& args]
  (if (= args '("--version"))
    (prn {:closh (closh.core/closh-version)
          :clojure (clojure-version)})
    #_(apply main args)
    (repl nil nil)))
