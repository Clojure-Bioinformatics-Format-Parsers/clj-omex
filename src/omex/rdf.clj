(ns omex.rdf
  "RDF extractors for common annotation patterns in OMEX archives.
   Includes extractors for Dublin Core, BioModels qualifiers (bqbiol/bqmodel),
   and SemSim composite annotations.
   
   Extraction functions return normalized Clojure maps with stable identifiers
   for blank nodes and local resources, and include provenance information
   (source file and subject URI)."
  (:require [omex.io :as io]
            [clojure.string :as str])
  (:import [org.apache.jena.rdf.model Model Resource Property RDFNode Statement]
           [org.apache.jena.vocabulary DC DCTerms]
           [org.apache.jena.query QueryFactory QueryExecutionFactory]))

;;; ------------------------------------------------------------------
;;; Common RDF namespaces
;;; ------------------------------------------------------------------

(def ^:const bqbiol-ns "http://biomodels.net/biology-qualifiers/")
(def ^:const bqmodel-ns "http://biomodels.net/model-qualifiers/")
(def ^:const semsim-ns "http://www.bhi.washington.edu/semsim#")
(def ^:const ro-ns "http://www.obofoundry.org/ro/ro.owl#")
(def ^:const opb-ns "http://identifiers.org/opb/")

;;; ------------------------------------------------------------------
;;; Error handling helpers
;;; ------------------------------------------------------------------

(defn safe-extract
  "Wrap extraction in try-catch, returning nil on failure.
   If error-handler is provided, it will be called with the exception."
  ([f] (safe-extract f nil))
  ([f error-handler]
   (try
     (f)
     (catch Exception e
       (when error-handler
         (error-handler e))
       nil))))

(defmacro with-error-handling
  "Execute body with error handling, returning default-value on exception."
  [default-value & body]
  `(try
     ~@body
     (catch Exception _#
       ~default-value)))

(defn make-extraction-error
  "Create a structured extraction error result."
  [stage message & {:keys [details cause]}]
  {:ok false
   :error (cond-> {:stage stage :message message}
            details (assoc :details details)
            cause   (assoc :cause-type (type cause)))})

(defn make-extraction-ok
  "Create a successful extraction result."
  [data]
  {:ok true :data data})

;;; ------------------------------------------------------------------
;;; URI normalization and canonicalization
;;; ------------------------------------------------------------------

(defn canonicalize-uri
  "Canonicalize a URI by:
   - Converting http to https for identifiers.org
   - Normalizing case for scheme
   - Removing trailing slashes
   Returns nil for nil input."
  [^String uri]
  (when uri
    (-> uri
        (str/replace #"^http://identifiers.org/" "https://identifiers.org/")
        (str/replace #"/+$" "")
        (str/replace #"^HTTP:" "http:")
        (str/replace #"^HTTPS:" "https:"))))

(defn is-local-uri?
  "Check if a URI is local to the archive (starts with ./ or # or is relative)."
  [^String uri]
  (when uri
    (or (.startsWith uri "./")
        (.startsWith uri "#")
        (.startsWith uri "file:")
        (and (not (.contains uri "://"))
             (not (.startsWith uri "urn:"))))))

(defn normalize-blank-node
  "Normalize a blank node identifier to a stable form.
   Takes the model location and blank node ID to create a unique identifier."
  [source-location ^String bnode-id]
  (if (and source-location bnode-id)
    (format "_:b%s_%s" 
            (Integer/toHexString (hash source-location))
            (str/replace bnode-id #"[^a-zA-Z0-9]" "_"))
    bnode-id))

(defn extract-curie-parts
  "Extract prefix and local name from a CURIE-style URI.
   Returns {:prefix ... :local ...} or nil if not a CURIE."
  [^String uri]
  (when uri
    (when-let [[_ prefix local] (re-matches #"([^:]+):(.+)" uri)]
      (when (and (not (contains? #{"http" "https" "urn" "file"} prefix))
                 (not (.contains prefix "/")))
        {:prefix prefix :local local}))))

;;; ------------------------------------------------------------------
;;; Model query helpers
;;; ------------------------------------------------------------------

(defn- property
  "Create a Jena Property from a namespace URI and local name."
  [^Model model ^String ns-uri ^String local-name]
  (.createProperty model ns-uri local-name))

(defn- get-node-value
  "Extract a value from an RDFNode, handling literals, resources, and blank nodes."
  [^RDFNode node source-location]
  (cond
    (.isLiteral node)
    {:type :literal
     :value (.getString (.asLiteral node))
     :datatype (some-> (.asLiteral node) .getDatatype str)}
    
    (.isURIResource node)
    {:type :uri
     :value (canonicalize-uri (.getURI (.asResource node)))}
    
    (.isAnon node)
    {:type :blank-node
     :value (normalize-blank-node source-location (.getId (.asResource node)))}
    
    :else nil))

(defn- get-literal-values
  "Get all literal values for a given property from a resource."
  [^Resource resource ^Property prop]
  (with-error-handling []
    (->> (iterator-seq (.listProperties resource prop))
         (map #(.getObject ^Statement %))
         (filter #(.isLiteral ^RDFNode %))
         (mapv #(.getString (.asLiteral ^RDFNode %))))))

(defn- get-resource-values
  "Get all resource URIs for a given property from a resource."
  [^Resource resource ^Property prop]
  (with-error-handling []
    (->> (iterator-seq (.listProperties resource prop))
         (map #(.getObject ^Statement %))
         (filter #(.isResource ^RDFNode %))
         (mapv #(canonicalize-uri (.getURI (.asResource ^RDFNode %)))))))

(defn- get-all-values
  "Get all values (literal and resource) for a given property from a resource."
  [^Resource resource ^Property prop source-location]
  (with-error-handling []
    (->> (iterator-seq (.listProperties resource prop))
         (map #(get-node-value (.getObject ^Statement %) source-location))
         (filterv some?))))

;;; ------------------------------------------------------------------
;;; Dublin Core extractors
;;; ------------------------------------------------------------------

(defn extract-dc-metadata
  "Extract Dublin Core metadata from a Jena Model.
   Returns a map with :title, :creator, :description, :date, :source."
  [^Model model]
  (with-error-handling {}
    (let [subjects (iterator-seq (.listSubjects model))]
      (reduce
       (fn [acc ^Resource subj]
         (merge acc
                {:title       (first (get-literal-values subj DC/title))
                 :creator     (get-literal-values subj DC/creator)
                 :description (first (get-literal-values subj DC/description))
                 :date        (first (get-literal-values subj DCTerms/created))
                 :source      (first (get-literal-values subj DC/source))}))
       {}
       subjects))))

;;; ------------------------------------------------------------------
;;; BioModels qualifiers extractors (bqbiol, bqmodel)
;;; ------------------------------------------------------------------

(def bqbiol-qualifiers
  "Common BioModels biology qualifiers."
  ["is" "hasPart" "isPartOf" "isVersionOf" "hasVersion"
   "isHomologTo" "isDescribedBy" "isEncodedBy" "encodes"
   "occursIn" "hasProperty" "isPropertyOf" "hasTaxon"])

(def bqmodel-qualifiers
  "Common BioModels model qualifiers."
  ["is" "isDerivedFrom" "isDescribedBy" "isInstanceOf" "hasInstance"])

(defn extract-bqbiol-annotations
  "Extract BioModels biology qualifier annotations from a Jena Model.
   Returns a map keyed by qualifier name, with vectors of resource URIs."
  [^Model model]
  (with-error-handling {}
    (let [subjects (iterator-seq (.listSubjects model))]
      (reduce
       (fn [acc ^Resource subj]
         (reduce
          (fn [inner-acc qualifier]
            (let [prop (property model bqbiol-ns qualifier)
                  values (get-resource-values subj prop)]
              (if (seq values)
                (update inner-acc (keyword qualifier) (fnil into []) values)
                inner-acc)))
          acc
          bqbiol-qualifiers))
       {}
       subjects))))

(defn extract-bqmodel-annotations
  "Extract BioModels model qualifier annotations from a Jena Model.
   Returns a map keyed by qualifier name, with vectors of resource URIs."
  [^Model model]
  (with-error-handling {}
    (let [subjects (iterator-seq (.listSubjects model))]
      (reduce
       (fn [acc ^Resource subj]
         (reduce
          (fn [inner-acc qualifier]
            (let [prop (property model bqmodel-ns qualifier)
                  values (get-resource-values subj prop)]
              (if (seq values)
                (update inner-acc (keyword qualifier) (fnil into []) values)
                inner-acc)))
          acc
          bqmodel-qualifiers))
       {}
       subjects))))

;;; ------------------------------------------------------------------
;;; Singular Annotations Extractor
;;; ------------------------------------------------------------------

(def singular-annotation-predicates
  "Predicates used in singular (non-composite) annotations."
  [{:ns "http://purl.org/dc/terms/" :local "creator" :key :dc-creator}
   {:ns "http://purl.org/dc/terms/" :local "description" :key :dc-description}
   {:ns bqbiol-ns :local "is" :key :bqbiol-is}
   {:ns bqbiol-ns :local "isVersionOf" :key :bqbiol-isVersionOf}
   {:ns bqbiol-ns :local "isPropertyOf" :key :bqbiol-isPropertyOf}
   {:ns bqbiol-ns :local "hasTaxon" :key :bqbiol-hasTaxon}
   {:ns bqbiol-ns :local "isPartOf" :key :bqbiol-isPartOf}
   {:ns bqmodel-ns :local "isDescribedBy" :key :bqmodel-isDescribedBy}
   {:ns bqmodel-ns :local "is" :key :bqmodel-is}])

(defn extract-singular-annotations
  "Extract singular annotations from a Jena Model.
   Returns a vector of maps with :subject, :predicate, :object, and :provenance keys."
  ([^Model model] (extract-singular-annotations model nil))
  ([^Model model source-location]
   (with-error-handling {:ok false :error {:stage :singular-extract :message "Extraction failed"}}
     (let [results (atom [])]
       (doseq [{:keys [ns local key]} singular-annotation-predicates]
         (let [prop (property model ns local)
               stmts (iterator-seq (.listStatements model nil prop nil))]
           (doseq [^Statement stmt stmts]
             (let [subj (.getSubject stmt)
                   obj (.getObject stmt)]
               (swap! results conj
                      {:subject (if (.isURIResource subj)
                                  (canonicalize-uri (.getURI subj))
                                  (normalize-blank-node source-location (str (.getId subj))))
                       :predicate key
                       :predicate-uri (str ns local)
                       :object (get-node-value obj source-location)
                       :provenance {:source source-location}})))))
       (make-extraction-ok @results)))))

;;; ------------------------------------------------------------------
;;; SemSim composite annotation extractors
;;; ------------------------------------------------------------------

(def semsim-predicates
  "SemSim predicates for composite annotations."
  {:hasPhysicalEntity "hasPhysicalEntity"
   :hasPhysicalProperty "hasPhysicalProperty"
   :hasPhysicalEntityReference "hasPhysicalEntityReference"
   :hasMultiplier "hasMultiplier"})

(def semsim-process-predicates
  "SemSim predicates for process annotations."
  {:hasSourceParticipant "hasSourceParticipant"
   :hasSinkParticipant "hasSinkParticipant"
   :hasMediatorParticipant "hasMediatorParticipant"})

(defn extract-semsim-annotations
  "Extract SemSim composite annotations from a Jena Model.
   Returns a vector of maps with :entity, :property, and :process keys
   representing composite annotations."
  [^Model model]
  (with-error-handling []
    (let [has-physical-entity (property model semsim-ns "hasPhysicalEntity")
          has-physical-property (property model semsim-ns "hasPhysicalProperty")
          part-of (property model ro-ns "part_of")
          subjects (iterator-seq (.listSubjects model))]
      (->> subjects
           (mapcat
            (fn [^Resource subj]
              (let [entities (get-resource-values subj has-physical-entity)
                    properties (get-resource-values subj has-physical-property)
                    part-of-rels (get-resource-values subj part-of)]
                (when (or (seq entities) (seq properties))
                  [{:subject    (.getURI subj)
                    :entities   entities
                    :properties properties
                    :part-of    part-of-rels}]))))
           (filterv some?)))))

(defn extract-composite-annotations
  "Extract entity-property composite annotations from a Jena Model.
   Returns structured maps with normalized identifiers and provenance."
  ([^Model model] (extract-composite-annotations model nil))
  ([^Model model source-location]
   (with-error-handling {:ok false :error {:stage :composite-extract :message "Extraction failed"}}
     (let [has-entity-prop (property model semsim-ns "hasPhysicalEntity")
           has-property-prop (property model semsim-ns "hasPhysicalProperty")
           has-entity-ref-prop (property model semsim-ns "hasPhysicalEntityReference")
           has-multiplier-prop (property model semsim-ns "hasMultiplier")
           part-of-prop (property model ro-ns "part_of")
           results (atom [])]
       ;; Find all subjects with hasPhysicalEntity or hasPhysicalProperty
       (doseq [^Statement stmt (iterator-seq (.listStatements model nil has-entity-prop nil))]
         (let [subj (.getSubject stmt)
               subj-uri (if (.isURIResource subj)
                          (canonicalize-uri (.getURI subj))
                          (normalize-blank-node source-location (str (.getId subj))))
               entities (get-all-values subj has-entity-prop source-location)
               properties (get-all-values subj has-property-prop source-location)
               entity-refs (get-all-values subj has-entity-ref-prop source-location)
               multipliers (get-all-values subj has-multiplier-prop source-location)
               part-ofs (get-all-values subj part-of-prop source-location)]
           (swap! results conj
                  {:subject subj-uri
                   :type :entity-composite
                   :entities entities
                   :properties properties
                   :entity-references entity-refs
                   :multipliers multipliers
                   :part-of part-ofs
                   :provenance {:source source-location}})))
       (make-extraction-ok (vec (distinct @results)))))))

(defn extract-process-annotations
  "Extract SemSim process annotations (sources, sinks, mediators).
   Returns structured maps with participant information and provenance."
  ([^Model model] (extract-process-annotations model nil))
  ([^Model model source-location]
   (with-error-handling {:ok false :error {:stage :process-extract :message "Extraction failed"}}
     (let [source-prop (property model semsim-ns "hasSourceParticipant")
           sink-prop (property model semsim-ns "hasSinkParticipant")
           mediator-prop (property model semsim-ns "hasMediatorParticipant")
           entity-ref-prop (property model semsim-ns "hasPhysicalEntityReference")
           multiplier-prop (property model semsim-ns "hasMultiplier")
           results (atom [])]
       ;; Find subjects with any process participant property
       (let [process-subjects 
             (distinct 
              (concat 
               (map #(.getSubject ^Statement %) (iterator-seq (.listStatements model nil source-prop nil)))
               (map #(.getSubject ^Statement %) (iterator-seq (.listStatements model nil sink-prop nil)))
               (map #(.getSubject ^Statement %) (iterator-seq (.listStatements model nil mediator-prop nil)))))]
         (doseq [^Resource subj process-subjects]
           (let [subj-uri (if (.isURIResource subj)
                            (canonicalize-uri (.getURI subj))
                            (normalize-blank-node source-location (str (.getId subj))))
                 sources (get-all-values subj source-prop source-location)
                 sinks (get-all-values subj sink-prop source-location)
                 mediators (get-all-values subj mediator-prop source-location)]
             (swap! results conj
                    {:subject subj-uri
                     :type :process
                     :sources sources
                     :sinks sinks
                     :mediators mediators
                     :provenance {:source source-location}}))))
       (make-extraction-ok (vec @results))))))

(defn extract-energy-differentials
  "Extract energy differential annotations from SemSim models.
   These represent thermodynamic driving forces in biological models."
  ([^Model model] (extract-energy-differentials model nil))
  ([^Model model source-location]
   (with-error-handling {:ok false :error {:stage :energy-extract :message "Extraction failed"}}
     ;; Energy differentials typically use semsim:hasSourceParticipant and hasSinkParticipant
     ;; with OPB properties indicating chemical potential or energy
     (let [source-prop (property model semsim-ns "hasSourceParticipant")
           sink-prop (property model semsim-ns "hasSinkParticipant")
           property-prop (property model semsim-ns "hasPhysicalProperty")
           results (atom [])]
       ;; Look for patterns with both source and sink participants
       (doseq [^Statement stmt (iterator-seq (.listStatements model nil source-prop nil))]
         (let [subj (.getSubject stmt)
               subj-uri (if (.isURIResource subj)
                          (canonicalize-uri (.getURI subj))
                          (normalize-blank-node source-location (str (.getId subj))))
               sinks (get-all-values subj sink-prop source-location)
               properties (get-all-values subj property-prop source-location)]
           (when (seq sinks)  ; Energy differential needs both source and sink
             (swap! results conj
                    {:subject subj-uri
                     :type :energy-differential
                     :source (get-node-value (.getObject stmt) source-location)
                     :sinks sinks
                     :properties properties
                     :provenance {:source source-location}}))))
       (make-extraction-ok (vec (distinct @results)))))))

;;; ------------------------------------------------------------------
;;; OPB Term Extraction
;;; ------------------------------------------------------------------

(defn extract-opb-terms
  "Extract all OPB (Ontology of Physics for Biology) term references from a model.
   Returns a frequency map of OPB terms."
  [^Model model]
  (with-error-handling {}
    (let [all-objects (->> (iterator-seq (.listStatements model))
                           (map #(.getObject ^Statement %))
                           (filter #(.isURIResource ^RDFNode %))
                           (map #(.getURI (.asResource ^RDFNode %)))
                           (filter #(str/starts-with? % opb-ns)))]
      (frequencies all-objects))))

;;; ------------------------------------------------------------------
;;; SPARQL-based extractors (fallback for complex patterns)
;;; ------------------------------------------------------------------

(defn run-sparql-select
  "Execute a SPARQL SELECT query on a model and return results as Clojure maps."
  [^Model model ^String query-string]
  (with-error-handling {:ok false :error {:stage :sparql :message "Query failed"}}
    (let [query (QueryFactory/create query-string)]
      (with-open [qexec (QueryExecutionFactory/create query model)]
        (let [results (.execSelect qexec)
              vars (vec (.getResultVars results))]
          (make-extraction-ok
           (loop [rows []]
             (if (.hasNext results)
               (let [solution (.next results)
                     row (into {} 
                               (for [v vars]
                                 [(keyword v)
                                  (when-let [node (.get solution v)]
                                    (cond
                                      (.isLiteral node) (.getString (.asLiteral node))
                                      (.isURIResource node) (.getURI (.asResource node))
                                      :else (str node)))]))]
                 (recur (conj rows row)))
               rows))))))))

(def ^:private composite-annotation-sparql
  "SPARQL query for extracting composite annotations."
  "PREFIX semsim: <http://www.bhi.washington.edu/semsim#>
   PREFIX ro: <http://www.obofoundry.org/ro/ro.owl#>
   SELECT ?subject ?entity ?property ?partOf
   WHERE {
     ?subject semsim:hasPhysicalEntity ?entity .
     OPTIONAL { ?subject semsim:hasPhysicalProperty ?property }
     OPTIONAL { ?subject ro:part_of ?partOf }
   }")

(defn extract-composite-via-sparql
  "Extract composite annotations using SPARQL query."
  [^Model model]
  (run-sparql-select model composite-annotation-sparql))

;;; ------------------------------------------------------------------
;;; Convenience functions for OMEX archives
;;; ------------------------------------------------------------------

(defn extract-all-annotations
  "Extract all annotation types from a single Jena Model.
   Returns a map with :dc, :bqbiol, :bqmodel, and :semsim keys."
  [^Model model]
  {:dc      (extract-dc-metadata model)
   :bqbiol  (extract-bqbiol-annotations model)
   :bqmodel (extract-bqmodel-annotations model)
   :semsim  (extract-semsim-annotations model)})

(defn extract-all-annotations-safe
  "Extract all annotation types from a single Jena Model with structured errors.
   Returns {:ok true :data {...}} or {:ok false :error {...}}."
  ([^Model model] (extract-all-annotations-safe model nil))
  ([^Model model source-location]
   (try
     (let [singular (extract-singular-annotations model source-location)
           composite (extract-composite-annotations model source-location)
           process (extract-process-annotations model source-location)
           energy (extract-energy-differentials model source-location)
           opb-terms (extract-opb-terms model)]
       (make-extraction-ok
        {:singular-annotations (if (:ok singular) (:data singular) [])
         :composite-annotations (if (:ok composite) (:data composite) [])
         :process-annotations (if (:ok process) (:data process) [])
         :energy-differentials (if (:ok energy) (:data energy) [])
         :opb-terms opb-terms
         :dc (extract-dc-metadata model)
         :bqbiol (extract-bqbiol-annotations model)
         :bqmodel (extract-bqmodel-annotations model)
         :extraction-errors (filterv some?
                                     [(when-not (:ok singular) (:error singular))
                                      (when-not (:ok composite) (:error composite))
                                      (when-not (:ok process) (:error process))
                                      (when-not (:ok energy) (:error energy))])
         :provenance {:source source-location}}))
     (catch Exception e
       (make-extraction-error :extract-all (.getMessage e) :cause e)))))

(defn archive-annotations
  "Extract all annotations from all metadata files in an OMEX archive.
   Source can be a file path (String) or ZIP data (byte array).
   Returns a vector of annotation maps, one per metadata file."
  [source]
  (with-error-handling []
    (->> (io/load-metadata-models source)
         (mapv extract-all-annotations))))

(defn archive-annotations-safe
  "Safely extract all annotations from all metadata files in an OMEX archive.
   Source can be a file path (String) or ZIP data (byte array).
   Returns {:ok true :data [...] :errors [...]} with structured results."
  [source]
  (try
    (let [load-result (io/safe-load-metadata-models source)]
      (if (:ok load-result)
        (let [extractions (for [{:keys [location model]} (:models load-result)]
                            (extract-all-annotations-safe model location))
              {successes true failures false} (group-by :ok extractions)]
          {:ok true
           :data (mapv :data successes)
           :model-count (count (:models load-result))
           :errors (vec (concat (:errors load-result)
                                (mapv :error failures)))})
        load-result))
    (catch Exception e
      (make-extraction-error :archive-extract (.getMessage e) :cause e))))

(defn aggregate-annotations
  "Aggregate annotations across multiple OMEX archives.
   Sources can be file paths (String) or ZIP data (byte arrays).
   Returns a map with counts and collected annotations."
  [sources]
  (with-error-handling {:error "Failed to aggregate annotations"}
    (let [all-annotations (mapcat archive-annotations sources)]
      {:archive-count (count sources)
       :metadata-files (count all-annotations)
       :dc-creators (->> all-annotations
                         (mapcat #(get-in % [:dc :creator]))
                         (filterv some?)
                         distinct
                         vec)
       :bqbiol-references (->> all-annotations
                               (map :bqbiol)
                               (mapcat vals)
                               flatten
                               distinct
                               vec)
       :bqmodel-references (->> all-annotations
                                (map :bqmodel)
                                (mapcat vals)
                                flatten
                                distinct
                                vec)})))
