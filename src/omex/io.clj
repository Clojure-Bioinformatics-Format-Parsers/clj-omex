(ns omex.io
  "I/O helpers for opening .omex ZIP archives, parsing manifest.xml,
   discovering metadata RDF files, and parsing RDF into Jena Models.
   
   This namespace provides hardened I/O operations with structured error handling.
   Functions with 'safe-' prefix return {:ok true :result ...} or {:ok false :error {...}}
   maps instead of throwing exceptions, allowing callers to handle errors gracefully."
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io])
  (:import [java.util.zip ZipFile ZipEntry ZipException]
           [java.io ByteArrayInputStream ByteArrayOutputStream FileNotFoundException]
           [org.apache.jena.rdf.model ModelFactory]
           [org.apache.jena.riot RDFDataMgr Lang RiotException]))

;;; ------------------------------------------------------------------
;;; Configuration and Limits
;;; ------------------------------------------------------------------

(def ^:dynamic *max-triples*
  "Maximum number of triples to load from an RDF model. nil means no limit."
  nil)

(def ^:dynamic *parse-timeout-ms*
  "Maximum time in milliseconds for RDF parsing. nil means no timeout."
  nil)

;;; ------------------------------------------------------------------
;;; Namespace Registry and URI Normalization
;;; ------------------------------------------------------------------

(def namespace-registry
  "Common RDF namespace prefixes used in OMEX metadata."
  {"dcterms"        "http://purl.org/dc/terms/"
   "dc"             "http://purl.org/dc/elements/1.1/"
   "bqbiol"         "http://biomodels.net/biology-qualifiers/"
   "bqmodel"        "http://biomodels.net/model-qualifiers/"
   "semsim"         "http://www.bhi.washington.edu/semsim#"
   "opb"            "http://identifiers.org/opb/"
   "fma"            "http://identifiers.org/fma/"
   "chebi"          "http://identifiers.org/chebi/"
   "orcid"          "https://orcid.org/"
   "identifiers"    "http://identifiers.org/"
   "ro"             "http://www.obofoundry.org/ro/ro.owl#"
   "rdf"            "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   "rdfs"           "http://www.w3.org/2000/01/rdf-schema#"
   "foaf"           "http://xmlns.com/foaf/0.1/"
   "prov"           "http://www.w3.org/ns/prov#"
   "provo"          "http://www.w3.org/ns/prov#"
   "vcard"          "http://www.w3.org/2006/vcard/ns#"})

(def reverse-namespace-registry
  "Reverse lookup from namespace URI to prefix."
  (into {} (map (fn [[k v]] [v k]) namespace-registry)))

(defn normalize-uri
  "Normalize a URI by applying common transformations:
   - Convert http to https for identifiers.org
   - Remove trailing slashes
   - Normalize case for scheme"
  [^String uri]
  (when uri
    (-> uri
        (clojure.string/replace #"^http://identifiers.org/" "https://identifiers.org/")
        (clojure.string/replace #"/+$" ""))))

(defn expand-curie
  "Expand a CURIE (e.g., 'opb:OPB_00154') to a full URI using namespace registry."
  [^String curie]
  (when curie
    (if-let [[_ prefix local] (re-matches #"([^:]+):(.+)" curie)]
      (if-let [ns-uri (get namespace-registry prefix)]
        (str ns-uri local)
        curie)
      curie)))

(defn compact-uri
  "Compact a URI to CURIE form if a matching prefix is registered.
   Prefers longer namespace matches (more specific prefixes)."
  [^String uri]
  (when uri
    (let [sorted-registry (->> reverse-namespace-registry
                               (sort-by (fn [[ns-uri _]] (- (count ns-uri)))))]
      (or (some (fn [[ns-uri prefix]]
                  (when (.startsWith uri ns-uri)
                    (str prefix ":" (subs uri (count ns-uri)))))
                sorted-registry)
          uri))))

;;; ------------------------------------------------------------------
;;; Error handling
;;; ------------------------------------------------------------------

(defn- wrap-io-error
  "Wrap an I/O operation with consistent error handling."
  [operation-name f]
  (try
    (f)
    (catch FileNotFoundException e
      (throw (ex-info (str operation-name ": file not found")
                      {:type :file-not-found
                       :message (.getMessage e)}
                      e)))
    (catch ZipException e
      (throw (ex-info (str operation-name ": invalid ZIP file")
                      {:type :invalid-zip
                       :message (.getMessage e)}
                      e)))
    (catch Exception e
      (throw (ex-info (str operation-name ": unexpected error")
                      {:type :io-error
                       :message (.getMessage e)}
                      e)))))

;;; ------------------------------------------------------------------
;;; Structured Error Returns (Safe API)
;;; ------------------------------------------------------------------

(defn make-error
  "Create a structured error map with stage and message."
  [stage message & {:keys [details cause]}]
  {:ok false
   :error (cond-> {:stage stage :message message}
            details (assoc :details details)
            cause   (assoc :cause-type (type cause)))})

(defn make-ok
  "Create a success result map."
  [result-key result-value]
  {:ok true result-key result-value})

;;; ------------------------------------------------------------------
;;; Low-level ZIP helpers
;;; ------------------------------------------------------------------

(defn list-zip-entries
  "Return a vector of entry info maps from the ZIP file at `zip-path`.
   Throws ex-info with :type on I/O errors."
  [^String zip-path]
  (wrap-io-error "list-zip-entries"
    #(with-open [zf (ZipFile. zip-path)]
       (let [entries (enumeration-seq (.entries zf))]
         (mapv (fn [^ZipEntry e]
                 {:name            (.getName e)
                  :size            (.getSize e)
                  :compressed-size (.getCompressedSize e)
                  :is-directory?   (.isDirectory e)
                  :last-modified   (.getTime e)})
               entries)))))

(defn extract-entry
  "Extract a single entry from the ZIP at `zip-path` by name.
   Returns a byte array or nil if not found.
   Throws ex-info with :type on I/O errors."
  [^String zip-path ^String entry-name]
  (wrap-io-error "extract-entry"
    #(with-open [zf (ZipFile. zip-path)]
       (when-let [entry (.getEntry zf entry-name)]
         (with-open [in (.getInputStream zf entry)
                     out (ByteArrayOutputStream.)]
           (io/copy in out)
           (.toByteArray out))))))

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

(defn safe-parse-manifest
  "Safely parse manifest.xml bytes, returning structured result.
   Returns {:ok true :manifest [...]} or {:ok false :error {...}}."
  [^bytes manifest-bytes]
  (try
    (let [manifest (parse-manifest manifest-bytes)]
      (make-ok :manifest manifest))
    (catch Exception e
      (make-error :manifest (.getMessage e) :cause e))))

(defn safe-read-manifest
  "Safely read and parse manifest.xml from an OMEX archive.
   Returns {:ok true :manifest [...]} or {:ok false :error {...}}."
  [^String omex-path]
  (try
    (if-let [manifest-bytes (extract-entry omex-path "manifest.xml")]
      (safe-parse-manifest manifest-bytes)
      (make-error :manifest "manifest.xml not found in archive"))
    (catch clojure.lang.ExceptionInfo e
      (make-error :manifest (.getMessage e) 
                  :details (ex-data e) :cause e))
    (catch Exception e
      (make-error :manifest (.getMessage e) :cause e))))

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

(defn- wrap-rdf-error
  "Wrap an RDF parsing operation with consistent error handling."
  [operation-name f]
  (try
    (f)
    (catch RiotException e
      (throw (ex-info (str operation-name ": RDF parsing error")
                      {:type :rdf-parse-error
                       :message (.getMessage e)}
                      e)))
    (catch Exception e
      (throw (ex-info (str operation-name ": unexpected error")
                      {:type :rdf-error
                       :message (.getMessage e)}
                      e)))))

(defn read-rdf-model
  "Load an RDF model from bytes using Jena.
   Defaults to RDF/XML; caller can pass `:lang` as Jena Lang.
   Throws ex-info with :type on parsing errors."
  ([^bytes rdf-bytes]
   (read-rdf-model rdf-bytes Lang/RDFXML))
  ([^bytes rdf-bytes lang]
   (wrap-rdf-error "read-rdf-model"
     #(let [model (ModelFactory/createDefaultModel)]
        (with-open [in (ByteArrayInputStream. rdf-bytes)]
          (RDFDataMgr/read model in lang))
        model))))

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

(defn safe-parse-rdf
  "Safely parse RDF bytes, returning structured result.
   Returns {:ok true :model <JenaModel>} or {:ok false :error {...}}.
   Respects *max-triples* limit if set."
  ([^bytes rdf-bytes]
   (safe-parse-rdf rdf-bytes Lang/RDFXML nil))
  ([^bytes rdf-bytes lang]
   (safe-parse-rdf rdf-bytes lang nil))
  ([^bytes rdf-bytes lang source-location]
   (try
     (let [model (ModelFactory/createDefaultModel)]
       (with-open [in (ByteArrayInputStream. rdf-bytes)]
         (RDFDataMgr/read model in lang))
       ;; Check triple count limit
       (if (and *max-triples* (> (.size model) *max-triples*))
         (make-error :rdf-parse 
                     (format "Model exceeds max-triples limit (%d > %d)" 
                             (.size model) *max-triples*)
                     :details {:location source-location
                               :triple-count (.size model)
                               :limit *max-triples*})
         (make-ok :model model)))
     (catch RiotException e
       (make-error :rdf-parse (.getMessage e)
                   :details {:location source-location
                             :lang (str lang)}
                   :cause e))
     (catch Exception e
       (make-error :rdf-parse (.getMessage e)
                   :details {:location source-location}
                   :cause e)))))

(defn safe-load-metadata-models
  "Safely load all metadata models from an OMEX archive.
   Returns {:ok true :models [{:location ... :model ...}...] :errors [...]}
   Errors are collected but don't prevent loading other models."
  [^String omex-path]
  (try
    (let [manifest-result (safe-read-manifest omex-path)]
      (if (:ok manifest-result)
        (let [meta-entries (metadata-entries (:manifest manifest-result))
              results (for [{:keys [location format]} meta-entries
                            :let [loc (normalize-path location)
                                  bytes (try 
                                          (extract-entry omex-path loc)
                                          (catch Exception e nil))
                                  lang (guess-rdf-lang location format)]]
                        (if bytes
                          (let [parse-result (safe-parse-rdf bytes lang location)]
                            (if (:ok parse-result)
                              {:ok true :location location :model (:model parse-result)}
                              {:ok false :location location :error (:error parse-result)}))
                          {:ok false :location location 
                           :error {:stage :extract :message "Failed to extract entry"}}))
              {successes true failures false} (group-by :ok results)]
          {:ok true
           :models (mapv #(select-keys % [:location :model]) successes)
           :errors (mapv #(select-keys % [:location :error]) failures)})
        manifest-result))
    (catch Exception e
      (make-error :load-metadata (.getMessage e) :cause e))))

;;; ------------------------------------------------------------------
;;; Model Statistics (for protection hooks)
;;; ------------------------------------------------------------------

(defn model-triple-count
  "Get the number of triples in a Jena Model."
  [model]
  (when model
    (.size model)))
