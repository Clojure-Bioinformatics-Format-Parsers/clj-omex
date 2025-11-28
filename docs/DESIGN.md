# clj-omex Design Document

## Overview

clj-omex is a Clojure library for working with OMEX (Open Modeling EXchange) archive files. This document describes the architecture, design decisions, and implementation details for the RDF extraction and error handling capabilities.

## Architecture

### Namespace Organization

```
omex/
├── io.clj      # Low-level I/O: ZIP handling, manifest parsing, RDF loading
├── rdf.clj     # RDF extraction: annotation patterns, SPARQL queries
├── stats.clj   # Statistics: archive stats, aggregation
└── cli.clj     # Command-line interface
```

### Core Design Principles

1. **Structured Error Handling**: Functions return `{:ok true ...}` or `{:ok false :error {...}}` instead of throwing exceptions
2. **Backward Compatibility**: New safe functions augment existing throwing functions
3. **Provenance Tracking**: Extracted data includes source file and subject URI information
4. **URI Normalization**: Consistent handling of URIs across different sources

## Structured Error Model

### Error Structure

```clojure
{:ok false
 :error {:stage :manifest|:rdf-parse|:extract|...
         :message "Human-readable error message"
         :details {:location "path/to/file.rdf"
                   :lang "Lang:RDF/XML"
                   ...}
         :cause-type java.lang.Exception}}
```

### Error Stages

| Stage | Description |
|-------|-------------|
| `:manifest` | Error reading or parsing manifest.xml |
| `:extract` | Error extracting entry from ZIP |
| `:rdf-parse` | Error parsing RDF content |
| `:singular-extract` | Error extracting singular annotations |
| `:composite-extract` | Error extracting composite annotations |
| `:process-extract` | Error extracting process annotations |
| `:energy-extract` | Error extracting energy differentials |
| `:sparql` | Error executing SPARQL query |

### Success Structure

```clojure
{:ok true
 :data [...extracted data...]
 :model-count 3
 :errors [...any non-fatal errors...]}
```

## RDF Extraction Patterns

### Singular Annotations

Simple subject-predicate-object patterns:

```turtle
<subject> dc:creator "Author Name" .
<subject> bqbiol:is <https://identifiers.org/go/GO:0008150> .
```

Extracted predicates:
- `dc:creator`, `dc:description`
- `dcterms:created`
- `bqbiol:is`, `bqbiol:isVersionOf`, `bqbiol:isPropertyOf`, `bqbiol:hasTaxon`, `bqbiol:isPartOf`
- `bqmodel:isDescribedBy`, `bqmodel:is`

### Composite Annotations (Entity-Property)

SemSim entity-property composites:

```turtle
<composite1>
    semsim:hasPhysicalEntity <entity1> ;
    semsim:hasPhysicalProperty opb:OPB_00340 .

<entity1>
    semsim:hasPhysicalEntityReference chebi:CHEBI:17234 ;
    ro:part_of fma:FMA:7088 .
```

Extracted structure:
```clojure
{:subject "http://example.org/composite1"
 :type :entity-composite
 :entities [{:type :uri :value "..."}]
 :properties [{:type :uri :value "http://identifiers.org/opb/OPB_00340"}]
 :entity-references [...]
 :multipliers [...]
 :part-of [...]
 :provenance {:source "metadata.rdf"}}
```

### Process Annotations

SemSim process patterns with sources, sinks, and mediators:

```turtle
<process1>
    semsim:hasSourceParticipant <source1> ;
    semsim:hasSinkParticipant <sink1> ;
    semsim:hasMediatorParticipant <mediator1> .
```

### Energy Differentials

Thermodynamic driving forces with source and sink participants:

```turtle
<energy_diff1>
    semsim:hasSourceParticipant <high_energy> ;
    semsim:hasSinkParticipant <low_energy> ;
    semsim:hasPhysicalProperty opb:OPB_01058 .
```

## URI Normalization

### Canonicalization Rules

1. Convert `http://identifiers.org/` to `https://identifiers.org/`
2. Remove trailing slashes
3. Normalize URI scheme case

### CURIE Expansion/Compaction

Namespace registry enables bidirectional conversion:

```clojure
"opb:OPB_00340" ⟷ "http://identifiers.org/opb/OPB_00340"
"bqbiol:is" ⟷ "http://biomodels.net/biology-qualifiers/is"
```

### Blank Node Normalization

Blank nodes are normalized to stable identifiers:
```
_:b<source-hash>_<original-id>
```

This ensures consistent references within a single extraction context.

## Protection Mechanisms

### Max Triples Limit

```clojure
(binding [io/*max-triples* 10000]
  (io/safe-parse-rdf bytes))
```

If the parsed model exceeds the limit, returns an error:
```clojure
{:ok false
 :error {:stage :rdf-parse
         :message "Model exceeds max-triples limit (15000 > 10000)"
         :details {:triple-count 15000 :limit 10000}}}
```

### Timeout (Planned)

```clojure
(binding [io/*parse-timeout-ms* 5000]
  (io/safe-parse-rdf bytes))
```

## SPARQL Support

For complex extraction patterns, SPARQL queries can be used:

```clojure
(rdf/run-sparql-select model
  "PREFIX semsim: <http://www.bhi.washington.edu/semsim#>
   SELECT ?subject ?entity ?property
   WHERE {
     ?subject semsim:hasPhysicalEntity ?entity .
     OPTIONAL { ?subject semsim:hasPhysicalProperty ?property }
   }")
```

Returns:
```clojure
{:ok true
 :data [{:subject "..." :entity "..." :property "..."}
        ...]}
```

## Statistics Model

### Archive Stats

```clojure
{:path "model.omex"
 :entry-count 10
 :total-size 125000
 :total-compressed 45000
 :manifest-entries 8
 :metadata-entries 2
 :num-singular-annotations 25
 :num-composite-annotations 5
 :num-process-annotations 2
 :top-opb-terms {"http://identifiers.org/opb/OPB_00340" 3
                 "http://identifiers.org/opb/OPB_00154" 2}
 :annotation-extraction-errors []}
```

### Aggregate Stats

Additional fields for collection analysis:
- `:total-singular-annotations`
- `:total-composite-annotations`
- `:total-process-annotations`
- `:aggregate-opb-terms`

## Testing Strategy

### Unit Tests

1. URI normalization and CURIE handling
2. Individual extractor functions with fixture files
3. Error handling for malformed input

### Integration Tests

1. Full archive extraction with test OMEX file
2. CLI command outputs
3. Statistics computation

### Test Fixtures

Located in `test/resources/fixtures/`:
- `singular-annotation.ttl` - Dublin Core and BioModels qualifiers
- `entity-composite.ttl` - SemSim entity-property composites
- `process-composite.ttl` - SemSim process participants
- `energy-differential.ttl` - Energy differential patterns
- `test-archive.omex` - Integration test archive

## Future Enhancements

1. **Timeout Implementation**: Add actual timeout handling for long-running parses
2. **Streaming Extraction**: Process large RDF files without loading entirely into memory
3. **SPARQL Construct**: Support SPARQL CONSTRUCT queries for complex transformations
4. **Validation**: Add annotation validation against ontology constraints
5. **Export Formats**: Add export to JSON-LD, CSV, and other formats

## Dependencies

- **Apache Jena**: RDF parsing, SPARQL execution
- **clojure.data.xml**: XML parsing for manifest.xml
- **clojure.data.zip**: XML navigation
