metadata {
 definition(name: "Codex415 Map Attribute Probe", namespace: "codex415", author: "Codex") {
  capability "Actuator"
  attribute "fields", "string"
  attribute "ordinary", "number"
  attribute "getClass", "string"
  command "seedProbe"
 }
 preferences {
  input name: "probeChoice", type: "enum", title: "Owned options", options: [fields:"<b>Fields</b>", ordinary:"Ordinary"], required: false
 }
}
def installed() { seedProbe() }
def updated() {}
def seedProbe() {
 sendEvent(name:"ordinary",value:0)
 sendEvent(name:"fields",value:"owned-fields")
 sendEvent(name:"getClass",value:"owned-getClass")
}
