package support

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/** Stateful, public platform model. Never implements or intercepts an MCP tool. */
class SoftwareHubPlatform {
    final Map<String, Map> devices = [:]
    final Map<String, Map> variables = [software_counter: [name: 'software_counter', type: 'integer', value: 1]]
    final Map<String, byte[]> files = [:]
    final List<Map> commands = []
    final List<String> unsupported = []
    private int nextId = 100

    Map create(String namespace, String type, String dni, Map properties) {
        if (namespace != 'hubitat' || type != 'Virtual Switch') fail("driver:${namespace}:${type}")
        if (devices.values().any { it.device.deviceNetworkId == dni }) throw new IllegalArgumentException('DNI already exists')
        String id = (++nextId).toString()
        devices[id] = switchModel(id, dni, properties.label.toString())
        devices[id]
    }

    static Map switchModel(String id, String dni, String label) {
        [device: [id: id.toInteger(), name: 'Virtual Switch', label: label,
                  deviceNetworkId: dni, deviceTypeName: 'Virtual Switch', deviceTypeNamespace: 'hubitat',
                  data: [:], capabilities: ['Switch'], currentStates: [switch: [value: 'off', dataType: 'ENUM']]],
         commands: [[name: 'on', parameters: []], [name: 'off', parameters: []]], settings: [], inputValues: [:]]
    }

    Object get(String path, Map params = [:]) {
        if (path.startsWith('/device/fullJson/')) {
            def model = devices[path.substring('/device/fullJson/'.length())]
            if (!model) throw new IllegalArgumentException('Device not found')
            return JsonOutput.toJson(model)
        }
        if (path == '/hub/fileManager/json' && !params) {
            return JsonOutput.toJson([files: files.collect { name, bytes -> [name: name, size: bytes.length] }])
        }
        fail("GET:${path}")
    }

    Object postJson(String path, String body) {
        if (path != '/device/runmethod') fail("POST:${path}")
        Map payload = new JsonSlurper().parseText(body) as Map
        Map model = devices[payload.id.toString()]
        if (!model) throw new IllegalArgumentException('Device not found')
        List args = payload.args.collect { it.value }
        switch (payload.method) {
            case 'updateDataValue':
                if (args.size() != 2) throw new IllegalArgumentException('updateDataValue needs two arguments')
                model.device.data[args[0].toString()] = args[1].toString()
                break
            case 'on':
            case 'off':
                if (args) throw new IllegalArgumentException('Switch command takes no arguments')
                model.device.currentStates.switch.value = payload.method
                commands << [id: payload.id.toString(), command: payload.method]
                break
            default:
                fail("command:${payload.method}")
        }
        [success: true]
    }

    void delete(String dni) {
        String id = devices.find { key, model -> model.device.deviceNetworkId == dni }?.key
        if (id == null) throw new IllegalArgumentException('DNI not found')
        devices.remove(id)
    }

    boolean setVariable(String name, Object value) {
        if (!variables.containsKey(name)) return false
        variables[name].value = value
        true
    }

    byte[] download(String name) { files[name]?.clone() as byte[] }
    void upload(String name, byte[] data) { files[name] = data.clone() as byte[] }
    void deleteFile(String name) {
        if (files.remove(name) == null) throw new IllegalArgumentException('File not found')
    }

    String exceptionMessage(Throwable error) {
        "${error.class.name}: ${error.message}".toString()
    }

    String exceptionTrace(Throwable error) {
        exceptionMessage(error) + '\n' + error.stackTrace.take(5).collect { it.toString() }.join('\n')
    }

    private Object fail(String operation) {
        unsupported << operation
        throw new UnsupportedOperationException("Software hub does not implement ${operation}")
    }
}
