definition(name: "Codex415 Map Probe 0909", namespace: "codex415", author: "Codex", description: "Inert temporary sandbox diagnostic", category: "Convenience", singleInstance: true)
preferences { page(name: "mainPage"); page(name: "nativePage"); page(name: "listOptions"); page(name: "mapOptions") }
def installed() {}
def updated() {}
def mainPage() {
 def results = []
 ["ordinary", "fields", "Fields", "getClass", "class", "metaClass"].each { key ->
 recordProbe(results, "1", key) { probe1(key) }
 recordProbe(results, "2", key) { probe2(key) }
 recordProbe(results, "3", key) { probe3(key) }
 recordProbe(results, "4", key) { probe4(key) }
 recordProbe(results, "5", key) { probe5(key) }
 recordProbe(results, "6", key) { probe6(key) }
 recordProbe(results, "7", key) { probe7(key) }
 recordProbe(results, "8", key) { probe8(key) }
 recordProbe(results, "9", key) { probe9(key) }
 recordProbe(results, "10", key) { probe10(key) }
 recordProbe(results, "11", key) { probe11(key) }
 recordProbe(results, "12", key) { probe12(key) }
 recordProbe(results, "13", key) { probe13(key) }
 recordProbe(results, "14", key) { probe14(key) }
 recordProbe(results, "15", key) { probe15(key) }
 recordProbe(results, "16", key) { probe16(key) }
 recordProbe(results, "17", key) { probe17(key) }
 recordProbe(results, "18", key) { probe18(key) }
 recordProbe(results, "19", key) { probe19(key) }
 recordProbe(results, "20", key) { probe20(key) }
 recordProbe(results, "21", key) { probe21(key) }
 recordProbe(results, "22", key) { probe22(key) }
 recordProbe(results, "23", key) { probe23(key) }
 recordProbe(results, "24", key) { probe24(key) }
 recordProbe(results, "gap_savedSchema", key) { probegap_savedSchema(key) }
 recordProbe(results, "gap_layout", key) { probegap_layout(key) }
 recordProbe(results, "untyped_read", key) { probeuntyped_read(key) }
 recordProbe(results, "typed_each_read", key) { probetyped_each_read(key) }
 recordProbe(results, "untyped_each_read", key) { probeuntyped_each_read(key) }
 recordProbe(results, "typed_each_write", key) { probetyped_each_write(key) }
 recordProbe(results, "null_safe_put", key) { probenull_safe_put(key) }
 }
 dynamicPage(name:"mainPage",title:"Codex415 inert probes",install:true,uninstall:true) {
  section("Results") { paragraph groovy.json.JsonOutput.toJson(results) }
 }
}
def recordProbe(List results, String id, String key, Closure body) {
 def row = [id:id,key:key]
 try { row.put("result", body.call()); row.put("success",true) }
 catch(Exception e) { row.put("success",false); row.put("error",e.toString()) }
 results.add(row)
}
def nativePage() {
 dynamicPage(name:"nativePage",title:"Owned input names",install:false,uninstall:false) {
  section("Owned settings") {
   input(name:"fields",type:"bool",title:"Collision field",defaultValue:false,required:false)
   input(name:"ordinary",type:"number",title:"Ordinary zero",defaultValue:0,required:false)
  }
 }
}
def listOptions() {
 dynamicPage(name:"listOptions",title:"List options",install:false,uninstall:false) {
  section("Owned options") { input(name:"choice",type:"enum",title:"Choice",options:[[fields:"<b>Fields</b>"],[ordinary:"<i>Ordinary</i>"]],required:false) }
 }
}
def mapOptions() {
 dynamicPage(name:"mapOptions",title:"Map options",install:false,uninstall:false) {
  section("Owned options") { input(name:"choice",type:"enum",title:"Choice",options:[fields:"<b>Fields</b>",ordinary:"<i>Ordinary</i>"],required:false) }
 }
}

def probe1(String key) {
 def seed = [(key):false]
 Map cached = [:]; String entryId = key; Map rec = [value: false]; cached[entryId] = rec; return cached
}

def probe2(String key) {
 def seed = [(key):false]
 def kept = [:]; seed.each { k, v -> if (v != null) kept[k] = v }; return kept
}

def probe3(String key) {
 def seed = [(key):false]
 def schema = [(key): [type: "text"]]; def schemaForBuild = schema; schemaForBuild = [:] + schema; schemaForBuild[key] = ([:] + schema[key]) << [type: "bool"]; return schemaForBuild
}

def probe4(String key) {
 def seed = [(key):false]
 def liveDeviceIds = [:]; [[name:key, deviceIdsForDeviceList:["owned"]]].each { st -> def n = st?.name?.toString(); if (n && st?.deviceIdsForDeviceList instanceof List && st.deviceIdsForDeviceList) liveDeviceIds[n] = st.deviceIdsForDeviceList }; return liveDeviceIds
}

def probe5(String key) {
 def seed = [(key):false]
 def out = []; for (entry in [[(key):"<b>label</b>"]]) { if(entry instanceof Map) { def cleaned = [:]; entry.each { k,v -> cleaned[k] = (v instanceof String) ? v.replaceAll(/<[^>]+>/,"") : v }; out << cleaned } }; return out
}

def probe6(String key) {
 def seed = [(key):false]
 def options = [(key):"<b>label</b>"]; def cleaned = [:]; options.each { k,v -> cleaned[k] = (v instanceof String) ? v.replaceAll(/<[^>]+>/,"") : v }; return cleaned
}

def probe7(String key) {
 def seed = [(key):false]
 def pageValues = [:]; for(i in [[name:key,value:false]]) { def nm = i.name.toString(); if(i.value != null) pageValues[nm] = i.value }; return pageValues
}

def probe8(String key) {
 def seed = [(key):false]
 def pageValues = [:]; for(i in [[name:key,defaultValue:0]]) { def nm = i.name.toString(); if(i.defaultValue != null) pageValues[nm] = i.defaultValue }; return pageValues
}

def probe9(String key) {
 def seed = [(key):false]
 def settingsMap = [:]; seed.each { name, meta -> def chosen = false; if(chosen != null) settingsMap[name] = chosen }; return settingsMap
}

def probe10(String key) {
 def seed = [(key):false]
 def pageValues = [(key):false]; def settingsMap = [:]; seed.each { name, meta -> settingsMap[name] = pageValues.containsKey(name) ? pageValues[name] : "" }; return settingsMap
}

def probe11(String candidateKey) {
 def seed = [(candidateKey):false]
 def result = [:]; Map mapping = ["owned":"mapped"]; [deviceId:"owned"].each { key, value -> if(key == "deviceId" && value != null) { def mappedId = mapping[value.toString()]; result[key] = mappedId != null ? mappedId.toString() : value } }; return result
}

def probe12(String candidateKey) {
 def seed = [(candidateKey):false]
 def result = [:]; Map mapping = ["owned":"mapped"]; [deviceIds:["owned",null,"missing"]].each { key,value -> if(key == "deviceIds" && value instanceof List) result[key] = value.collect { id -> if(id != null) { def mappedId = mapping[id.toString()]; return mappedId != null ? mappedId.toString() : id }; return id } }; return result
}

def probe13(String candidateKey) {
 def seed = [(candidateKey):false]
 def result = [:]; seed.each { key,value -> result[key] = value }; return result
}

def probe14(String key) {
 def seed = [(key):false]
 def tiles = [[id:0]]; def tile = tiles.find { it.id == 0 }; def spec = [(key):0]; spec.each { k,v -> if(k?.toString() != "id") tile[k] = v }; return tile
}

def probe15(String key) {
 def seed = [(key):false]
 def filtered = [:]; def id = "2311"; def entry = [label:"owned"]; if(id.isInteger()) filtered[id] = entry; return filtered
}

def probe16(String key) {
 def seed = [(key):false]
 def liveSettings = [(key):false]; def settingsMap = [:]; seed.each { name,meta -> def v = liveSettings[name]; if(v == null) v = ""; settingsMap[name] = v }; return settingsMap
}

def probe17(String key) {
 def seed = [(key):false]
 def liveSettings = [(key):0]; def settingsMap = [:]; seed.each { name,meta -> def v = liveSettings[name]; if(v == null) v = ""; settingsMap[name] = v }; return settingsMap
}

def probe18(String key) {
 def seed = [(key):false]
 Map currentSettings = [(key):false]; def fullMap = [:]; seed.each { name,meta -> if(currentSettings?.containsKey(name)) fullMap[name] = currentSettings[name] }; return fullMap
}

def probe19(String key) {
 def seed = [(key):false]
 def fullMap = [:]; [(key):[type:"button"]].each { name,meta -> if(meta?.type == "button") fullMap[name] = "" }; return fullMap
}

def probe20(String key) {
 def seed = [(key):false]
 def fullMap = [:]; seed.each { name,meta -> fullMap[name] = "" }; return fullMap
}

def probe21(String key) {
 def seed = [(key):false]
 Map extraSettings = [(key):false]; def fullMap = [:]; extraSettings?.each { k,v -> fullMap[k] = v }; return fullMap
}

def probe22(String key) {
 def seed = [(key):false]
 def schema = seed; def settingsMap = [(key):false]; def knownSettings = [:]; settingsMap.each { k,v -> if(schema?.containsKey(k.toString())) knownSettings[k] = v }; return knownSettings
}

def probe23(String key) {
 def seed = [(key):false]
 def states = [:]; [[name:key,value:0]].each { st -> states[st.name] = st.value }; return states
}

def probe24(String key) {
 def seed = [(key):false]
 Map args = [latitude:"0"]; String coordinate = "latitude"; def v = args[coordinate]; v = v.toBigDecimal(); args[coordinate] = v; return args
}

def probegap_savedSchema(String key) {
 def seed = [(key):false]
 def savedSchema = [:]; [[name:key,type:"text"]].each { s -> def n = s?.name?.toString(); if(n && !savedSchema.containsKey(n)) savedSchema[n] = [name:n,type:s?.type?.toString(),multiple:false] }; return savedSchema
}

def probegap_layout(String candidateKey) {
 def seed = [(candidateKey):false]
 Map current = [:]; def layout = new LinkedHashMap(current); seed.each { k,v -> def key = k?.toString(); layout[key] = v }; return layout
}

def probeuntyped_read(String key) {
 def seed = [(key):false]
 def target = new LinkedHashMap(); target.put(key,false); return target[key]
}

def probetyped_each_read(String key) {
 def seed = [(key):false]
 Map target = [(key):false]; def out = []; seed.each { k,v -> out << target[k] }; return out
}

def probeuntyped_each_read(String key) {
 def seed = [(key):false]
 def target = [(key):false]; def out = []; seed.each { k,v -> out << target[k] }; return out
}

def probetyped_each_write(String key) {
 def seed = [(key):false]
 Map target = [:]; seed.each { k,v -> target[k] = v }; return target
}

def probenull_safe_put(String key) {
 def seed = [(key):false]
 def target = [:]; [null,false,0,[],[nested:false]].each { v -> target.put(key,v); if(target.get(key) != v) throw new IllegalStateException("mismatch") }; return target
}
