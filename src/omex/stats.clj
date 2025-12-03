(ns omex.stats
  "Statistics over single OMEX archives and collections.
   Includes annotation extraction statistics and error tracking."
  (:require [omex.io :as io]
            [omex.rdf :as rdf]
            [clojure.java.io :as cio])
  (:import [java.io File]))

;;; ------------------------------------------------------------------
;;; Single archive stats
;;; ------------------------------------------------------------------

(defn archive-basic-stats
  "Compute basic statistics for a single OMEX archive.
   Source can be a file path (String) or ZIP data (byte array).
   Returns a map with keys:
     :source             - the original source (path or :byte-array)
     :entry-count        - number of ZIP entries
     :total-size         - sum of uncompressed sizes in bytes
     :total-compressed   - sum of compressed sizes in bytes
     :manifest-entries   - count of <content> entries in manifest.xml
     :metadata-entries   - count of entries with RDF metadata MIME type
     :num-singular-annotations - count of singular annotations found
     :num-composite-annotations - count of composite annotations found
     :num-process-annotations - count of process annotations found
     :top-opb-terms      - frequency map of top OPB terms
     :annotation-extraction-errors - any errors during annotation extraction"
  [source]
  (let [zip-entries  (io/list-zip-entries source)
        manifest     (io/read-manifest source)
        meta-entries (io/metadata-entries manifest)
        ;; Extract annotations safely
        extraction-result (rdf/archive-annotations-safe source)
        annotations (when (:ok extraction-result) (:data extraction-result))
        ;; Count annotations across all metadata files
        singular-count (reduce + 0 (map #(count (:singular-annotations %)) annotations))
        composite-count (reduce + 0 (map #(count (:composite-annotations %)) annotations))
        process-count (reduce + 0 (map #(count (:process-annotations %)) annotations))
        ;; Aggregate OPB terms
        opb-terms (reduce (fn [acc m]
                            (merge-with + acc (:opb-terms m)))
                          {}
                          annotations)
        ;; Get top 10 OPB terms
        top-opb (into {} (take 10 (sort-by (comp - val) opb-terms)))
        ;; Collect errors
        extraction-errors (if (:ok extraction-result)
                            (concat (:errors extraction-result)
                                    (mapcat :extraction-errors annotations))
                            [(:error extraction-result)])]
    {:source                       (if (string? source) source :byte-array)
     :entry-count                  (count zip-entries)
     :total-size                   (reduce + 0 (map :size zip-entries))
     :total-compressed             (reduce + 0 (map :compressed-size zip-entries))
     :manifest-entries             (count manifest)
     :metadata-entries             (count meta-entries)
     :num-singular-annotations     singular-count
     :num-composite-annotations    composite-count
     :num-process-annotations      process-count
     :top-opb-terms                top-opb
     :annotation-extraction-errors (filterv some? extraction-errors)}))

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
     :total-singular-annotations
     :total-composite-annotations
     :total-process-annotations
     :aggregate-opb-terms
     :per-archive   - vector of individual archive-basic-stats"
  [omex-paths]
  (let [per-archive (mapv archive-basic-stats omex-paths)
        all-opb-terms (reduce (fn [acc m]
                                (merge-with + acc (:top-opb-terms m)))
                              {}
                              per-archive)]
    {:archive-count               (count per-archive)
     :total-entries               (reduce + 0 (map :entry-count per-archive))
     :total-size                  (reduce + 0 (map :total-size per-archive))
     :total-compressed            (reduce + 0 (map :total-compressed per-archive))
     :total-manifest-entries      (reduce + 0 (map :manifest-entries per-archive))
     :total-metadata-entries      (reduce + 0 (map :metadata-entries per-archive))
     :total-singular-annotations  (reduce + 0 (map :num-singular-annotations per-archive))
     :total-composite-annotations (reduce + 0 (map :num-composite-annotations per-archive))
     :total-process-annotations   (reduce + 0 (map :num-process-annotations per-archive))
     :aggregate-opb-terms         (into {} (take 20 (sort-by (comp - val) all-opb-terms)))
     :per-archive                 per-archive}))
