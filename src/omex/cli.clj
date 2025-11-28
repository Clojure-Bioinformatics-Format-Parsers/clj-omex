(ns omex.cli
  "Command-line interface for OMEX archive tools.
   Provides subcommands for extracting annotations and computing statistics."
  (:require [omex.io :as io]
            [omex.rdf :as rdf]
            [omex.stats :as stats]
            [clojure.string :as str]
            [clojure.java.io :as cio])
  (:import [java.io File]))

;;; ------------------------------------------------------------------
;;; JSON output helpers
;;; ------------------------------------------------------------------

(defn- to-json-str
  "Convert a Clojure data structure to JSON-like string.
   Uses simple string conversion without external dependencies.
   Note: This is a basic implementation for CLI output. For production
   use with complex data, consider adding a proper JSON library."
  [data]
  (cond
    (nil? data) "null"
    (string? data) (str "\"" (str/escape data {\\ "\\\\" \" "\\\"" \newline "\\n" \return "\\r" \tab "\\t"}) "\"")
    (keyword? data) (str "\"" (name data) "\"")
    (number? data) (str data)
    (boolean? data) (str data)
    (symbol? data) (str "\"" (str data) "\"")
    (map? data) (str "{"
                     (str/join ", "
                               (map (fn [[k v]]
                                      (str (to-json-str k) ": " (to-json-str v)))
                                    data))
                     "}")
    (set? data) (to-json-str (vec data))
    (sequential? data) (str "[" (str/join ", " (map to-json-str data)) "]")
    :else (str "\"" (str/replace (str data) "\"" "\\\"") "\"")))

(defn- print-json
  "Print data as JSON to stdout."
  [data]
  (println (to-json-str data)))

;;; ------------------------------------------------------------------
;;; Subcommand: stats
;;; ------------------------------------------------------------------

(defn stats-cmd
  "Execute the 'stats' subcommand.
   Computes and prints basic statistics for OMEX archives."
  [args]
  (let [paths (if (seq args)
                args
                [(str (System/getProperty "user.dir"))])]
    (doseq [path paths]
      (let [file (cio/file path)]
        (cond
          (.isFile file)
          (print-json (stats/archive-basic-stats path))
          
          (.isDirectory file)
          (let [omex-files (stats/omex-files-in-dir path)]
            (if (seq omex-files)
              (print-json (stats/aggregate-stats omex-files))
              (println (str "No .omex files found in: " path))))
          
          :else
          (println (str "Path not found: " path)))))))

;;; ------------------------------------------------------------------
;;; Subcommand: extract
;;; ------------------------------------------------------------------

(defn extract-cmd
  "Execute the 'extract' subcommand.
   Extracts and prints normalized annotations from OMEX archives as JSON."
  [args]
  (let [paths (if (seq args)
                args
                (do (println "Usage: extract <omex-file> [omex-file...]")
                    []))]
    (doseq [path paths]
      (let [file (cio/file path)]
        (if (.isFile file)
          (let [result (rdf/archive-annotations-safe path)]
            (print-json (assoc result :source-path path)))
          (println (str "File not found: " path)))))))

;;; ------------------------------------------------------------------
;;; Subcommand: manifest
;;; ------------------------------------------------------------------

(defn manifest-cmd
  "Execute the 'manifest' subcommand.
   Reads and prints the manifest.xml content from an OMEX archive."
  [args]
  (let [paths (if (seq args)
                args
                (do (println "Usage: manifest <omex-file> [omex-file...]")
                    []))]
    (doseq [path paths]
      (let [result (io/safe-read-manifest path)]
        (print-json (assoc result :source-path path))))))

;;; ------------------------------------------------------------------
;;; Help and usage
;;; ------------------------------------------------------------------

(def help-text
  "clj-omex - Tools for working with OMEX archive files

Usage: clj-omex <command> [args...]

Commands:
  stats <path>       Compute statistics for OMEX archive(s)
                     If path is a directory, processes all .omex files
  
  extract <file>     Extract normalized annotations as JSON
                     Includes singular, composite, and process annotations
  
  manifest <file>    Read and display manifest.xml content
  
  help               Show this help message

Examples:
  clj-omex stats archive.omex
  clj-omex stats ./omex-collection/
  clj-omex extract model.omex
  clj-omex manifest biosimulations.omex
")

(defn help-cmd
  "Print help information."
  [_args]
  (println help-text))

;;; ------------------------------------------------------------------
;;; Main entry point
;;; ------------------------------------------------------------------

(defn run
  "Main entry point for CLI. Dispatches to appropriate subcommand."
  [args]
  (let [[cmd & rest-args] args]
    (case cmd
      "stats"    (stats-cmd rest-args)
      "extract"  (extract-cmd rest-args)
      "manifest" (manifest-cmd rest-args)
      "help"     (help-cmd rest-args)
      nil        (help-cmd nil)
      (do
        (println (str "Unknown command: " cmd))
        (println "Run 'clj-omex help' for usage information.")))))

(defn -main
  "CLI main entry point."
  [& args]
  (run args))
