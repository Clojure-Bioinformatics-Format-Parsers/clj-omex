(ns omex.io
  "I/O helpers for opening .omex ZIP archives, parsing manifest.xml,
   discovering metadata RDF files, and parsing RDF into Jena Models."
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io])
  (:import [java.util.zip ZipFile ZipEntry]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [org.apache.jena.rdf.model ModelFactory]
           [org.apache.jena.riot RDFDataMgr Lang]))

;;; ------------------------------------------------------------------
;;; Low-level ZIP helpers
;;; ------------------------------------------------------------------

(defn list-zip-entries
  "Return a vector of entry info maps from the ZIP file at `zip-path`."
  [^String zip-path]
  (with-open [zf (ZipFile. zip-path)]
    (let [entries (enumeration-seq (.entries zf))]
      (mapv (fn [^ZipEntry e]
              {:name            (.getName e)
               :size            (.getSize e)
               :compressed-size (.getCompressedSize e)
               :is-directory?   (.isDirectory e)
               :last-modified   (.getTime e)})
            entries))))

(defn extract-entry
  "Extract a single entry from the ZIP at `zip-path` by name.
   Returns a byte array or nil if not found."
  [^String zip-path ^String entry-name]
  (with-open [zf (ZipFile. zip-path)]
    (when-let [entry (.getEntry zf entry-name)]
      (with-open [in (.getInputStream zf entry)
                  out (ByteArrayOutputStream.)]
        (io/copy in out)
        (.toByteArray out)))))

;;; ------------------------------------------------------------------
;;; manifest.xml parsing
;;; ------------------------------------------------------------------

(defn parse-manifest
  "Given bytes of manifest.xml, parse and return a vector of maps with
   :location and :format keys for each <content> element."
  [^bytes manifest-bytes]
  (let [root (xml/parse (ByteArrayInputStream. manifest-bytes))]
    (->> (:content root)
         (filter #(= :content (:tag %)))
         (mapv (fn [elem]
                 {:location (get-in elem [:attrs :location])
                  :format   (get-in elem [:attrs :format])})))))

(defn read-manifest
  "Read and parse manifest.xml from an OMEX archive at `omex-path`."
  [^String omex-path]
  (some-> (extract-entry omex-path "manifest.xml")
          parse-manifest))

;;; ------------------------------------------------------------------
;;; Metadata / RDF discovery
;;; ------------------------------------------------------------------

(def ^:private rdf-mime-types
  "MIME types that indicate RDF metadata."
  #{"http://identifiers.org/combine.specifications/omex-metadata"
    "application/rdf+xml"})

(defn metadata-entries
  "Return manifest entries whose format indicates RDF metadata."
  [manifest]
  (filter #(rdf-mime-types (:format %)) manifest))

;;; ------------------------------------------------------------------
;;; Jena RDF model loading
;;; ------------------------------------------------------------------

(defn read-rdf-model
  "Load an RDF model from bytes using Jena.
   Defaults to RDF/XML; caller can pass `:lang` as Jena Lang."
  ([^bytes rdf-bytes]
   (read-rdf-model rdf-bytes Lang/RDFXML))
  ([^bytes rdf-bytes lang]
   (let [model (ModelFactory/createDefaultModel)]
     (with-open [in (ByteArrayInputStream. rdf-bytes)]
       (RDFDataMgr/read model in lang))
     model)))

(defn- normalize-path
  "Normalize a relative path by removing leading './' and handling edge cases."
  [^String path]
  (cond
    (nil? path) nil
    (.startsWith path "./") (recur (subs path 2))
    (.startsWith path "/") (subs path 1)
    :else path))

(defn- guess-rdf-lang
  "Guess the RDF language based on file extension or MIME type."
  [^String location ^String format]
  (cond
    (and format (.contains format "turtle")) Lang/TURTLE
    (and location (.endsWith (.toLowerCase location) ".ttl")) Lang/TURTLE
    (and location (.endsWith (.toLowerCase location) ".n3")) Lang/N3
    (and location (.endsWith (.toLowerCase location) ".nt")) Lang/NTRIPLES
    (and location (.endsWith (.toLowerCase location) ".jsonld")) Lang/JSONLD
    :else Lang/RDFXML))

(defn load-metadata-models
  "Given an omex-path, return a seq of Jena Models for each metadata entry."
  [^String omex-path]
  (let [manifest (read-manifest omex-path)
        meta-entries (metadata-entries manifest)]
    (for [{:keys [location format]} meta-entries
          :let [loc (normalize-path location)
                bytes (extract-entry omex-path loc)
                lang (guess-rdf-lang location format)]
          :when bytes]
      (read-rdf-model bytes lang))))
