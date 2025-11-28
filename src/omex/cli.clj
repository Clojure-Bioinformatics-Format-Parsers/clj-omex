(ns omex.cli
  "Minimal CLI with commands: stats"
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.pprint :as pprint]
            [omex.stats :as stats])
  (:gen-class))

;;; ------------------------------------------------------------------
;;; CLI option specs
;;; ------------------------------------------------------------------

(def cli-options
  [["-h" "--help" "Show this help message"]
   ["-d" "--dir DIR" "Directory containing .omex files (for stats command)"]])

;;; ------------------------------------------------------------------
;;; Commands
;;; ------------------------------------------------------------------

(defn cmd-stats
  "Run stats on a single file or directory of OMEX archives."
  [{:keys [arguments options]}]
  (let [targets (if-let [dir (:dir options)]
                  (stats/omex-files-in-dir dir)
                  arguments)]
    (if (empty? targets)
      (println "No OMEX files specified. Use --dir or provide file paths.")
      (let [result (if (= 1 (count targets))
                     (stats/archive-basic-stats (first targets))
                     (stats/aggregate-stats targets))]
        (pprint/pprint result)))))

(defn print-help
  [summary]
  (println "Usage: clj -M:main <command> [options] [files...]")
  (println)
  (println "Commands:")
  (println "  stats    Compute statistics on OMEX archives")
  (println)
  (println "Options:")
  (println summary))

;;; ------------------------------------------------------------------
;;; Entry point
;;; ------------------------------------------------------------------

(defn -main
  [& args]
  (let [{:keys [options arguments summary errors]} (parse-opts args cli-options)
        [command & rest-args] arguments]
    (cond
      errors
      (do (doseq [e errors] (println e))
          (System/exit 1))

      (:help options)
      (print-help summary)

      (= command "stats")
      (cmd-stats {:arguments rest-args :options options})

      :else
      (print-help summary))))
