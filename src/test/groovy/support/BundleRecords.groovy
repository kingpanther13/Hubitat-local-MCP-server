package support

import groovy.json.JsonOutput

/**
 * The hub's bundle-record replies as the bundle specs stub them, in the shapes a live hub returns
 * (captured on 2.5.2.135): /bundle/list/json is a bare array of records, and /bundle/json/<id>
 * wraps one record under `bundle`, with contentReadable null there and the hub's code lists beside
 * it. Keep these in step with _readBundleList / _readBundle in
 * libraries/mcp-bundles-lib.groovy.
 */
class BundleRecords {

    static final String SECRET = 'zip-pass-not-for-output'

    /** One bundle record as /bundle/list/json lists it, including the password fields a result must never carry. */
    static Map record(Map over = [:]) {
        Map rec = [id: 4, name: 'Throwaway', namespace: 'mcptest', 'private': false,
         appTypeIds: [], deviceTypeIds: [], libraryIds: [],
         sourceUrl: 'https://example.com/throwaway.zip',
         contentReadable: 'apps [], drivers [], libraries []',
         sourcePassword: SECRET, encryptedPassword: SECRET + '-enc'] + over
        // The hub also sends each id list as a comma-joined string and in update order.
        ['appTypeIds', 'deviceTypeIds', 'libraryIds'].each { f ->
            List ids = (rec[f] ?: []) as List
            rec.put(f + 'String', ids.join(','))
            rec.put(f + 'UpdateOrder', ids)
            rec.put(f + 'UpdateOrderString', ids.join(','))
        }
        return rec
    }

    /** GET /bundle/list/json */
    static String listJson(List records) { JsonOutput.toJson(records) }

    /** GET /bundle/json/<id> for an id the hub has: the record (contentReadable null) plus page data. */
    static String recordJson(Map rec) {
        JsonOutput.toJson([bundleId: rec.id, bundle: rec + [contentReadable: null], extraBreadcrumb: rec.name,
                           appTypeList: [], deviceTypeList: [], libList: [],
                           hasAppTypes: false, hasDeviceTypes: false, hasLibraries: false])
    }

    /** GET /bundle/json/<id> for an id the hub does not have (also a non-numeric id). */
    static String unknownJson() { '{"success":false,"message":null}' }
}
