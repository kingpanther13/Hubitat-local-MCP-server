package support

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.util.concurrent.ConcurrentHashMap

/**
 * Validates rendered MCP wire payloads against the OFFICIAL Model Context Protocol
 * JSON Schemas vendored under {@code src/test/resources/mcp-schema/} — the referee
 * for {@code server.McpWireSchemaConformanceSpec}. Provenance, dialects, and the
 * refresh procedure live in that folder's {@code README.md}.
 *
 * Why a wrapper document is spliced per type: the MCP schemas have NO root schema.
 * The legacy file is {@code $schema} + {@code definitions}, the draft is
 * {@code $schema} + {@code $defs}; every type is reachable only as a named entry.
 * So {@link #schemaFor} builds
 *
 * <pre>{ "$schema": …, "$ref": "#/definitions/InitializeResult", "definitions": { … } }</pre>
 *
 * from the vendored definitions map verbatim. Every reference stays an internal JSON
 * pointer, so nothing resolves over the network — and neither does the meta-schema:
 * com.networknt ships every standard dialect inside its own jar.
 *
 * {@link #strictErrors} additionally forces {@code additionalProperties: false} onto one
 * named definition. That reproduces the MCP TypeScript SDK's {@code EmptyResultSchema}
 * ({@code ResultSchema.strict()}), whose unknown-key rejection is what turned a stray
 * {@code resultType} on a legacy {@code ping} into a client-side protocol error. The
 * published draft-07 {@code Result} carries {@code additionalProperties: {}} and accepts
 * anything, so plain validation against it cannot express that check.
 *
 * Both schema documents and every compiled schema are cached statically: compiling the
 * ~150-entry definitions map per feature would dominate the suite's runtime.
 *
 * NOTE for callers: {@code com.networknt.schema.Error} collides with
 * {@code java.lang.Error} under Groovy's default imports, so that type is never named
 * here or in a spec — the validate calls return plain {@code List<String>} messages.
 */
class McpSchemaValidator {

    /** Published 2025-06-18 schema (draft-07) — the legacy era. */
    static final String LEGACY_SCHEMA = 'src/test/resources/mcp-schema/2025-06-18/schema.json'
    /** Upstream's in-progress schema (2020-12) — the 2026-07-28 revision. */
    static final String DRAFT_SCHEMA = 'src/test/resources/mcp-schema/draft/schema.json'

    private static final JsonSlurper SLURPER = new JsonSlurper()
    private static final Map DOCS = new ConcurrentHashMap()
    private static final Map SCHEMAS = new ConcurrentHashMap()

    /**
     * Validation messages for {@code instance} against the named type in the legacy
     * (2025-06-18) schema. Empty list == conformant.
     */
    static List<String> legacyErrors(String typeName, Object instance) {
        return errors(LEGACY_SCHEMA, typeName, instance, null)
    }

    /**
     * Validation messages for {@code instance} against the named type in the draft
     * (2026-07-28) schema. Empty list == conformant.
     */
    static List<String> draftErrors(String typeName, Object instance) {
        return errors(DRAFT_SCHEMA, typeName, instance, null)
    }

    /**
     * As {@link #legacyErrors}, but with {@code additionalProperties: false} forced onto
     * the {@code strictDefinition} entry first — the strict-unknown-key view a legacy
     * client's own parser applies. {@code strictDefinition} is usually the definition the
     * requested type {@code $ref}s (e.g. type {@code EmptyResult} → strict {@code Result}).
     */
    static List<String> legacyStrictErrors(String typeName, String strictDefinition, Object instance) {
        return errors(LEGACY_SCHEMA, typeName, instance, strictDefinition)
    }

    private static List<String> errors(String schemaPath, String typeName, Object instance, String strictDefinition) {
        def schema = schemaFor(schemaPath, typeName, strictDefinition)
        def found = schema.validate(JsonOutput.toJson(instance), InputFormat.JSON)
        return found.collect { msg ->
            String at = msg.instanceLocation?.toString()
            return (at ? "${at} " : '(root) ') + msg.message
        }
    }

    private static Object schemaFor(String schemaPath, String typeName, String strictDefinition) {
        String key = "${schemaPath}|${typeName}|${strictDefinition}"
        def cached = SCHEMAS[key]
        if (cached != null) return cached

        Map doc = document(schemaPath)
        String defsKey = doc.containsKey('$defs') ? '$defs' : 'definitions'
        Map defs = doc[defsKey] as Map
        if (!defs.containsKey(typeName)) {
            throw new IllegalArgumentException(
                "No definition '${typeName}' in ${schemaPath} (#/${defsKey}). Either the type was " +
                "renamed by a schema refresh or the spec asked for the wrong era's type name.")
        }
        if (strictDefinition != null) {
            if (!defs.containsKey(strictDefinition)) {
                throw new IllegalArgumentException(
                    "No definition '${strictDefinition}' in ${schemaPath} to make strict.")
            }
            // Shallow copies are enough: only one key inside one definition is replaced,
            // and the cached document must not be mutated for other callers.
            defs = new LinkedHashMap(defs)
            Map strict = new LinkedHashMap(defs[strictDefinition] as Map)
            strict['additionalProperties'] = false
            defs[strictDefinition] = strict
        }
        Map wrapper = ['$schema': doc['$schema'], '$ref': "#/${defsKey}/${typeName}".toString(), (defsKey): defs]

        def registry = SchemaRegistry.withDefaultDialect(dialectFor(doc['$schema'] as String, schemaPath))
        def compiled = registry.getSchema(JsonOutput.toJson(wrapper))
        SCHEMAS[key] = compiled
        return compiled
    }

    private static Map document(String schemaPath) {
        def cached = DOCS[schemaPath]
        if (cached != null) return cached as Map
        // Relative path: the forked test JVM runs with the repo root as its working
        // directory in BOTH lanes (Gradle's default for the root build; ci/groovy2x-spock
        // sets workingDir = repoRoot explicitly). Same convention as
        // support.RMUtilsSandboxInterceptionSpec's probe-script load.
        File file = new File(schemaPath)
        if (!file.exists()) {
            throw new IllegalStateException(
                "Vendored MCP schema not found at '${schemaPath}' (resolved to ${file.absolutePath}). " +
                "The test JVM must run with the repo root as its working directory; see " +
                "src/test/resources/mcp-schema/README.md.")
        }
        Map doc = SLURPER.parse(file, 'UTF-8') as Map
        DOCS[schemaPath] = doc
        return doc
    }

    private static SpecificationVersion dialectFor(String declared, String schemaPath) {
        if (declared?.contains('draft-07')) return SpecificationVersion.DRAFT_7
        if (declared?.contains('2020-12')) return SpecificationVersion.DRAFT_2020_12
        throw new IllegalStateException(
            "${schemaPath} declares \$schema '${declared}', which this validator has no dialect " +
            "mapping for. Add it here (com.networknt.schema.SpecificationVersion) after checking " +
            "the validator version supports it.")
    }
}
