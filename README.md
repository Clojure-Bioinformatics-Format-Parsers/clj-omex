# clj-omex

A Clojure library for working with OMEX (Open Modeling EXchange) archive files. This library provides tools for parsing, extracting, and analyzing RDF metadata from OMEX archives commonly used in computational biology and systems biology.

## Features

- **Archive I/O**: Read and extract files from OMEX ZIP archives
- **Manifest Parsing**: Parse `manifest.xml` to discover archive contents
- **RDF Metadata Extraction**: Load and parse RDF/XML and Turtle metadata files using Apache Jena
- **Annotation Extractors**: Extract common annotation patterns:
  - Dublin Core metadata (title, creator, description, date)
  - BioModels qualifiers (bqbiol, bqmodel)
  - SemSim composite annotations (entities, properties, processes)
  - OPB term references
- **Statistics**: Compute statistics over single archives and collections
- **CLI Tools**: Command-line interface for common operations

## Installation

Add to your `deps.edn`:

```clojure
{:deps {org.clojure/clojure {:mvn/version "1.11.1"}
        org.clojure/data.xml {:mvn/version "0.0.8"}
        org.clojure/data.zip {:mvn/version "1.1.0"}
        org.apache.jena/jena-arq {:mvn/version "4.10.0"}}}
```

## Usage

### Basic I/O

```clojure
(require '[omex.io :as io])

;; List entries in an OMEX archive
(io/list-zip-entries "model.omex")

;; Read manifest
(io/read-manifest "model.omex")

;; Safe version with structured error handling
(io/safe-read-manifest "model.omex")
;; => {:ok true :manifest [...]} or {:ok false :error {...}}

;; Load RDF metadata models
(io/load-metadata-models "model.omex")
```

### RDF Annotation Extraction

```clojure
(require '[omex.rdf :as rdf])

;; Extract all annotations from an archive
(rdf/archive-annotations "model.omex")

;; Safe version with structured errors
(rdf/archive-annotations-safe "model.omex")
;; => {:ok true :data [...] :model-count 1 :errors []}

;; Extract specific annotation types from a model
(let [models (io/load-metadata-models "model.omex")
      model (first models)]
  {:dc (rdf/extract-dc-metadata model)
   :bqbiol (rdf/extract-bqbiol-annotations model)
   :composite (rdf/extract-composite-annotations model)
   :process (rdf/extract-process-annotations model)})
```

### Statistics

```clojure
(require '[omex.stats :as stats])

;; Get basic stats for an archive
(stats/archive-basic-stats "model.omex")
;; => {:path "model.omex"
;;     :entry-count 5
;;     :manifest-entries 4
;;     :metadata-entries 1
;;     :num-singular-annotations 10
;;     :num-composite-annotations 3
;;     :num-process-annotations 1
;;     :top-opb-terms {"http://identifiers.org/opb/OPB_00340" 2}
;;     ...}

;; Aggregate stats over multiple archives
(stats/aggregate-stats ["model1.omex" "model2.omex"])
```

### CLI

```bash
# Get statistics
clojure -M -m omex.cli stats model.omex
clojure -M -m omex.cli stats ./archive-directory/

# Extract annotations as JSON
clojure -M -m omex.cli extract model.omex

# Read manifest
clojure -M -m omex.cli manifest model.omex

# Help
clojure -M -m omex.cli help
```

## Structured Error Handling

Functions with `safe-` prefix return structured results instead of throwing exceptions:

```clojure
;; Success case
{:ok true :manifest [...]}

;; Error case
{:ok false
 :error {:stage :manifest
         :message "manifest.xml not found in archive"
         :details {...}}}
```

This allows callers to handle errors gracefully without try/catch.

## Namespace Registry

The library includes a registry of common RDF namespace prefixes:

- `dcterms`, `dc` - Dublin Core
- `bqbiol`, `bqmodel` - BioModels qualifiers
- `semsim` - SemSim ontology
- `opb`, `fma`, `chebi` - identifiers.org ontologies
- `orcid` - ORCID identifiers
- And more...

URI normalization helpers:

```clojure
(io/normalize-uri "http://identifiers.org/go/GO:0008150")
;; => "https://identifiers.org/go/GO:0008150"

(io/expand-curie "opb:OPB_00340")
;; => "http://identifiers.org/opb/OPB_00340"

(io/compact-uri "http://biomodels.net/biology-qualifiers/is")
;; => "bqbiol:is"
```

## Configuration

Dynamic variables for controlling behavior:

```clojure
;; Limit number of triples loaded (protection against large files)
(binding [io/*max-triples* 10000]
  (io/safe-parse-rdf bytes))

;; Timeout for parsing (not yet implemented)
(binding [io/*parse-timeout-ms* 5000]
  (io/safe-parse-rdf bytes))
```

## Testing

```bash
clojure -M:test
```

## License

Copyright © 2024

Distributed under the Eclipse Public License version 1.0.
