(ns omex.stats
  "Statistics over single OMEX archives and collections."
  (:require [omex.io :as io]
            [clojure.java.io :as cio])
  (:import [java.io File]))

;;; ------------------------------------------------------------------
;;; Single archive stats
;;; ------------------------------------------------------------------

(defn archive-basic-stats
  "Compute basic statistics for a single OMEX archive at `omex-path`.
   Returns a map with keys:
     :path               - the archive path
     :entry-count        - number of ZIP entries
     :total-size         - sum of uncompressed sizes in bytes
     :total-compressed   - sum of compressed sizes in bytes
     :manifest-entries   - count of <content> entries in manifest.xml
     :metadata-entries   - count of entries with RDF metadata MIME type"
  [^String omex-path]
  (let [zip-entries  (io/list-zip-entries omex-path)
        manifest     (io/read-manifest omex-path)
        meta-entries (io/metadata-entries manifest)]
    {:path              omex-path
     :entry-count       (count zip-entries)
     :total-size        (reduce + 0 (map :size zip-entries))
     :total-compressed  (reduce + 0 (map :compressed-size zip-entries))
     :manifest-entries  (count manifest)
     :metadata-entries  (count meta-entries)}))

;;; ------------------------------------------------------------------
;;; Collection / directory utilities
;;; ------------------------------------------------------------------

(defn omex-files-in-dir
  "Return a seq of .omex file paths found in `dir-path`."
  [^String dir-path]
  (->> (file-seq (cio/file dir-path))
       (filter #(and (.isFile ^File %)
                     (.endsWith (.getName ^File %) ".omex")))
       (map #(.getAbsolutePath ^File %))))

;;; ------------------------------------------------------------------
;;; Aggregate stats
;;; ------------------------------------------------------------------

(defn aggregate-stats
  "Compute aggregate statistics over a collection of OMEX paths.
   Returns a map with:
     :archive-count
     :total-entries
     :total-size
     :total-compressed
     :total-manifest-entries
     :total-metadata-entries
     :per-archive   - vector of individual archive-basic-stats"
  [omex-paths]
  (let [per-archive (mapv archive-basic-stats omex-paths)]
    {:archive-count          (count per-archive)
     :total-entries          (reduce + 0 (map :entry-count per-archive))
     :total-size             (reduce + 0 (map :total-size per-archive))
     :total-compressed       (reduce + 0 (map :total-compressed per-archive))
     :total-manifest-entries (reduce + 0 (map :manifest-entries per-archive))
     :total-metadata-entries (reduce + 0 (map :metadata-entries per-archive))
     :per-archive            per-archive}))
