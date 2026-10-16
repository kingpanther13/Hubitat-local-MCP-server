package server

import groovy.json.JsonOutput
import support.TestLocation
import support.ToolSpecBase
import spock.lang.Shared
import spock.lang.Unroll

/**
 * The addAction catalog against Rule Machine's own action table: every action sub-type RM offers
 * is modelled, each of the sub-types added in #544 maps to the settings RM's editor takes in its
 * reveal order, and the argument checks (requires, exclusive, the Simple Conditional Action
 * condition) refuse before any write. All calls are direct; nothing reaches a hub.
 */
class ActionCatalogSourceSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        sharedLocation.modes = [[id: "1", name: "Day"], [id: "2", name: "Night"]]
        // Device 8 has every capability these pickers list; device 9 is a plain switch.
        hubGet.register('/hub2/vrb/devices') { params ->
            JsonOutput.toJson([[id: 8, label: "Multi", capabilities: ["GarageDoorControl", "Valve", "WindowBlind", "Tone", "MusicPlayer", "Thermostat", "TemperatureMeasurement", "Switch"]],
                               [id: 9, label: "Plain", capabilities: ["Switch"]]])
        }
        hubGet.register('/device/fullJson/8') { params -> JsonOutput.toJson([id: 8]) }
    }

    def cleanup() {
        sharedLocation.modes = []
    }

    // Rule Machine's action table: group -> sub-types, as its action picker lists them.
    private static final Map RM_ACT_SUB_TYPES = [
        condActs:    ["getIfThen", "getElseIf", "getElse", "getEndIf", "getCondAct"],
        switchActs:  ["getOnOffSwitch", "getToggleSwitch", "getFlashSwitch", "getModeSwitch", "getChooseSwitch", "getPushButton", "getPushButtonPerMode", "getChooseButton"],
        dimmerActs:  ["getSetDimmer", "getToggleDimmer", "getAdjustDimmer", "getDimmersPerMode", "getFadeDimmer", "getStopFade", "getRLDimmer", "getStopDimmer", "getSetColor",
                      "getToggleColor", "getColorPerMode", "getSetColorTemp", "getToggleColorTemp", "getColorTempPerMode", "getFadeCT", "getStopCTFade"],
        sceneActs:   ["getRLShade", "getShadePosition", "getBlindTilt", "getStopShade", "getFanSpeed", "getAdjustFan", "getActivateScene", "getActivateScenePerMode"],
        lockActs:    ["getSetHSM", "getOCGarage", "getLULock", "getOCValve", "getSetThermostat", "getSetThermostatScheduler", "getSetThermController"],
        messageActs: ["getMsg", "getLogMsg", "getHTTPGet", "getHTTPPost", "getPingIP"],
        soundActs:   ["getSetMusicPlayer", "getSetVolume", "getMuteUnmute", "getTone", "getChime", "getSiren"],
        modeActs:    ["getSetVariable", "getSetMode", "getDefinedAction", "getWriteLocalFile", "getAppendLocalFile", "getDeleteLocalFile", "getReturnValue"],
        rulesActs:   ["getSetPrivateBoolean", "getRuleActions", "getStopActions", "getPauseResumeRules", "getRLActivate", "getRLTurnOff"],
        deviceActs:  ["getCapture", "getRestore", "getRefreshSwitch", "getPollSwitch", "getDisable", "getStartStopZPoll"],
        repeatActs:  ["getRepeat", "getWhile", "getStopRepeat"],
        delayActs:   ["getDelay", "getDelayPerMode", "getCancelDelay", "getWaitEvents", "getWaitRule", "getExitRule", "getComment"]
    ]

    private Set catalogPairs() {
        Set pairs = [] as Set
        (script._rmActionCatalog() as Map).each { cap, Map e ->
            if (e.actions instanceof Map) (e.actions as Map).each { act, Map ae -> pairs << "${e.actType}/${ae.sub ?: e.sub}".toString() }
            else pairs << "${e.actType}/${e.sub}".toString()
        }
        pairs
    }

    def "Rule Machine's action table has 84 sub-types"() {
        expect:
        RM_ACT_SUB_TYPES.values().sum { it.size() } == 84
    }

    def "every Rule Machine action sub-type is modelled under its own group"() {
        when:
        def pairs = catalogPairs()
        def missing = RM_ACT_SUB_TYPES.collectMany { g, subs -> subs.findAll { it != "getCondAct" }.collect { "${g}/${it}".toString() } }.findAll { !(it in pairs) }

        then:
        missing == []
    }

    def "Simple Conditional Action is modelled as the condition field every plain action takes"() {
        when:
        def caps = script._rmActionDiscover(true).capabilities as List
        def takesCondition = { String name -> ((caps.find { it.name == name }.optionalFields as List)*.name).contains("condition") }

        then:
        takesCondition("switch")
        takesCondition("garageDoor")
        takesCondition("returnValue")
        !takesCondition("ifThen")
        !takesCondition("elseIf")
        !takesCondition("repeatWhile")
        !takesCondition("waitExpression")
    }

    @Unroll
    def "mapping: each new entry writes the settings RM takes, in reveal order"() {
        when:
        def out = script._rmMapActionSpec(100, [capability: cap] + (action ? [action: action] : [:]) + spec, cap, action)

        then:
        out.actType == actType
        out.actSubType == subType
        (out.fields as Map).collect { k, v -> [k, v] } == expected.collect { k, v -> [k, v] }

        where:
        cap                    | action            | spec                                                                  || actType      | subType                      | expected
        "garageDoor"           | "open"            | [deviceIds: [8]]                                                      || "lockActs"   | "getOCGarage"                | ["garageRL.@N": false, "garageOpenClose.@N": [8]]
        "garageDoor"           | "close"           | [deviceIds: [8]]                                                      || "lockActs"   | "getOCGarage"                | ["garageRL.@N": true, "garageOpenClose.@N": [8]]
        "valve"                | "open"            | [deviceIds: [8]]                                                      || "lockActs"   | "getOCValve"                 | ["valveRL.@N": false, "valveOpenClose.@N": [8]]
        "valve"                | "close"           | [deviceIds: [8]]                                                      || "lockActs"   | "getOCValve"                 | ["valveRL.@N": true, "valveOpenClose.@N": [8]]
        "blind"                | "setTilt"         | [deviceIds: [8], tilt: 40]                                            || "sceneActs"  | "getBlindTilt"               | ["blindTilt.@N": [8], "tiltLevel.@N": 40]
        "blind"                | "setTilt"         | [deviceIds: [8], tiltVariable: "tv"]                                  || "sceneActs"  | "getBlindTilt"               | ["blindTilt.@N": [8], "uVar.@N": true, "xVar.@N": "tv"]
        "scene"                | "activate"        | [sceneIds: [31, 32]]                                                  || "sceneActs"  | "getActivateScene"           | ["sceneAct.@N": [31, 32]]
        "scene"                | "activatePerMode" | [perMode: [Day: [31], "2": [32]]]                                     || "sceneActs"  | "getActivateScenePerMode"    | ["sceneModes.@N": ["1", "2"], "scene1.@N": [31], "scene2.@N": [32]]
        "thermostatController" | null              | [controllerId: 50, deviceIds: [8]]                                    || "lockActs"   | "getSetThermController"      | ["thisApp.@N": 50, "tcSensors.@N": [8]]
        "thermostatScheduler"  | "setEcoMode"      | [deviceIds: [8], ecoMode: "ON"]                                       || "lockActs"   | "getSetThermostatScheduler"  | ["thisTherm.@N": [8], "thermSched.@N": "setEcoMode", "ecoMode.@N": "on"]
        "thermostatScheduler"  | "setEcoOffset"    | [schedulerId: 60, ecoOffsetVariable: "off"]                           || "lockActs"   | "getSetThermostatScheduler"  | ["tMethod.@N": true, "thisApp.@N": 60, "thermSched.@N": "setEcoOffset", "uVar.@N": true, "xVar.@N": "off"]
        "thermostatScheduler"  | "setEcoOffset"    | [deviceIds: [8], ecoOffset: 2.5]                                      || "lockActs"   | "getSetThermostatScheduler"  | ["thisTherm.@N": [8], "thermSched.@N": "setEcoOffset", "ecoOffset.@N": 2.5]
        "thermostatScheduler"  | "setHold"         | [deviceIds: [8], hold: "off"]                                         || "lockActs"   | "getSetThermostatScheduler"  | ["thisTherm.@N": [8], "thermSched.@N": "setHold", "setHold.@N": "off"]
        "thermostatScheduler"  | "setSched4Mode"   | [deviceIds: [8], modeId: 2]                                           || "lockActs"   | "getSetThermostatScheduler"  | ["thisTherm.@N": [8], "thermSched.@N": "setSched4Mode", "modN.@N": 2]
        "thermostatScheduler"  | "setTimeAway"     | [deviceIds: [8], awayHeat: 60, awayCoolVariable: "cv"]                || "lockActs"   | "getSetThermostatScheduler"  | ["thisTherm.@N": [8], "thermSched.@N": "setTimeAway", "timeAwayHeat.@N": 60, "uVar3.@N": true, "xVar3.@N": "cv"]
        "thermostatScheduler"  | "setThermSched"   | [deviceIds: [8]]                                                      || "lockActs"   | "getSetThermostatScheduler"  | ["thisTherm.@N": [8], "thermSched.@N": "setThermSched"]
        "thermostatScheduler"  | "setSchedDay"     | [deviceIds: [8], day: 0]                                              || "lockActs"   | "getSetThermostatScheduler"  | ["thisTherm.@N": [8], "thermSched.@N": "setSchedDay", "dayN.@N": 0]
        "musicPlayer"          | null              | [deviceIds: [8], command: "set volume", volume: 30]                   || "soundActs"  | "getSetMusicPlayer"          | ["player.@N": [8], "playerCommand.@N": "set volume", "playerLevel.@N": 30]
        "musicPlayer"          | null              | [deviceIds: [8], command: "play track and restore", track: "t", volumeVariable: "vv"] || "soundActs" | "getSetMusicPlayer" | ["player.@N": [8], "playerCommand.@N": "play track and restore", "playerTrack.@N": "t", "uVar.@N": true, "xVar.@N": "vv"]
        "musicPlayer"          | null              | [deviceIds: [8], command: "play text", text: "hello"]                 || "soundActs"  | "getSetMusicPlayer"          | ["player.@N": [8], "playerCommand.@N": "play text", "playerText.@N": "hello"]
        "tone"                 | null              | [deviceIds: [8]]                                                      || "soundActs"  | "getTone"                    | ["toner.@N": [8]]
        "returnValue"          | null              | [type: "integer", value: 5]                                           || "modeActs"   | "getReturnValue"             | ["valType.@N": "Integer", "valInt.@N": 5]
        "returnValue"          | null              | [type: "Decimal", value: "2.5"]                                       || "modeActs"   | "getReturnValue"             | ["valType.@N": "Decimal", "valDec.@N": 2.5]
        "returnValue"          | null              | [type: "Boolean", value: "TRUE"]                                      || "modeActs"   | "getReturnValue"             | ["valType.@N": "Boolean", "valBool.@N": "true"]
        "returnValue"          | null              | [type: "Boolean", value: false]                                       || "modeActs"   | "getReturnValue"             | ["valType.@N": "Boolean", "valBool.@N": "false"]
        "returnValue"          | null              | [type: "String", value: "hi"]                                         || "modeActs"   | "getReturnValue"             | ["valType.@N": "String", "valString.@N": "hi"]
        "returnValue"          | null              | [type: "Decimal", variable: "dv"]                                     || "modeActs"   | "getReturnValue"             | ["valType.@N": "Decimal", "useVar.@N": true, "valVar.@N": "dv"]
        "roomLights"           | "activate"        | [roomLightsId: 70]                                                    || "rulesActs"  | "getRLActivate"              | ["actRL.@N": 70, "modeRL.@N": "X"]
        "roomLights"           | "activate"        | [roomLightsId: 70, modeOrPeriod: "2", useConditions: true]            || "rulesActs"  | "getRLActivate"              | ["actRL.@N": 70, "modeRL.@N": "2", "forceRL.@N": true]
        "roomLights"           | "turnOff"         | [roomLightsIds: [70, 71]]                                             || "rulesActs"  | "getRLTurnOff"               | ["actOffRL.@N": [70, 71]]
    }

    @Unroll
    def "argument refusals fire before any write"() {
        when:
        script._rmMapActionSpec(100, [capability: cap] + (action ? [action: action] : [:]) + spec, cap, action)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(message)

        where:
        cap                   | action            | spec                                                    || message
        "garageDoor"          | "open"            | [:]                                                     || "garageDoor.open requires 'deviceIds'"
        "garageDoor"          | "open"            | [deviceIds: [9]]                                        || "does not have the GarageDoorControl capability"
        "valve"               | "close"           | [deviceIds: [9]]                                        || "does not have the Valve capability"
        "blind"               | "setTilt"         | [deviceIds: [8]]                                        || "blind.setTilt requires one of tilt, tiltVariable"
        "blind"               | "setTilt"         | [deviceIds: [8], tilt: 4, tiltVariable: "tv"]           || "blind.setTilt takes one of tilt, tiltVariable, not tilt and tiltVariable"
        "tone"                | null              | [deviceIds: [9]]                                        || "does not have the Tone capability"
        "musicPlayer"         | null              | [deviceIds: [8], command: "shuffle"]                    || "'command' must be one of"
        "musicPlayer"         | null              | [deviceIds: [8], command: "set volume", volume: 3, volumeVariable: "v"] || "takes one of volume, volumeVariable"
        "scene"               | "activate"        | [:]                                                     || "scene.activate requires 'sceneIds'"
        "scene"               | "activatePerMode" | [perMode: [:]]                                          || "scene.activatePerMode requires perMode"
        "scene"               | "activatePerMode" | [perMode: [Day: []]]                                    || "perMode[1] must be a non-empty list of scene ids"
        "thermostatController"| null              | [deviceIds: [8]]                                        || "requires 'controllerId'"
        "thermostatScheduler" | "setEcoMode"      | [ecoMode: "on"]                                         || "requires one of deviceIds, schedulerId"
        "thermostatScheduler" | "setEcoMode"      | [ecoMode: "on", deviceIds: [8], schedulerId: 60]        || "takes one of deviceIds, schedulerId"
        "thermostatScheduler" | "setEcoMode"      | [deviceIds: [8]]                                        || "requires 'ecoMode'"
        "thermostatScheduler" | "setEcoMode"      | [deviceIds: [8], ecoMode: "auto"]                       || "'ecoMode' must be one of on, off"
        "thermostatScheduler" | "setEcoOffset"    | [deviceIds: [8]]                                        || "requires one of ecoOffset, ecoOffsetVariable"
        "thermostatScheduler" | "setTimeAway"     | [deviceIds: [8]]                                        || "requires one of awayHeat, awayHeatVariable, awayCool, awayCoolVariable"
        "thermostatScheduler" | "setTimeAway"     | [deviceIds: [8], awayCool: 70, awayCoolVariable: "c"]   || "takes one of awayCool, awayCoolVariable"
        "thermostatScheduler" | "setSchedDay"     | [deviceIds: [8]]                                        || "requires 'day'"
        "thermostatScheduler" | "setFanMode"      | [deviceIds: [8]]                                        || "Unknown thermostatScheduler action 'setFanMode'"
        "thermostatScheduler" | "setSchedMode"    | [deviceIds: [8], modeIds: [1]]                          || "Unknown thermostatScheduler action 'setSchedMode'"
        "thermostatScheduler" | "setSchedTime"    | [deviceIds: [8], periods: ["Wake"]]                     || "Unknown thermostatScheduler action 'setSchedTime'"
        "returnValue"         | null              | [type: "Integer"]                                       || "returnValue action requires one of value, variable"
        "returnValue"         | null              | [value: 1]                                              || "returnValue requires type"
        "returnValue"         | null              | [type: "Integer", value: 1, variable: "x"]              || "takes one of value, variable"
        "returnValue"         | null              | [type: "String", variable: "x"]                         || "a variable source is offered for Integer, Decimal and Boolean"
        "returnValue"         | null              | [type: "Boolean", value: "yes"]                         || "a Boolean value is true or false"
        "returnValue"         | null              | [type: "Integer", value: "1.5"]                         || "must be a whole number"
        "returnValue"         | null              | [type: "Decimal", value: "abc"]                         || "a Decimal value must be a number"
        "returnValue"         | null              | [type: "Integer", value: 0]                             || "shows no row for a zero or empty Integer value"
        "returnValue"         | null              | [type: "String", value: ""]                             || "shows no row for a zero or empty String value"
        "roomLights"          | "activate"        | [:]                                                     || "roomLights.activate requires 'roomLightsId'"
        "roomLights"          | "turnOff"         | [:]                                                     || "roomLights.turnOff requires 'roomLightsIds'"
    }

    @Unroll
    def "Simple Conditional Action argument checks refuse before any write"() {
        when:
        script._rmMapActionSpec(100, spec, spec.capability, spec.action)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(message)

        where:
        why                          | spec                                                                                                                    || message
        "not a Map"                  | [capability: "switch", action: "on", deviceIds: [8], condition: "on"]                                                    || "switch.condition must be a condition spec"
        "no capability"              | [capability: "switch", action: "on", deviceIds: [8], condition: [state: "on"]]                                           || "switch.condition needs capability"
        "unknown capability"         | [capability: "switch", action: "on", deviceIds: [8], condition: [capability: "Wind", state: "on"]]                       || "is not a Rule Machine condition capability"
        "left out of the picker"     | [capability: "switch", action: "on", deviceIds: [8], condition: [capability: "Mode", state: "Day"]]                      || "does not offer a Mode condition in a Simple Conditional Action"
        "variable left out"          | [capability: "log", message: "m", condition: [capability: "Variable", variable: "x"]]         || "does not offer a Variable condition"
        "on an IF-THEN"              | [capability: "ifThen", expression: [conditions: [[capability: "Switch", deviceIds: [8], state: "on"]]],
                                        condition: [capability: "Switch", deviceIds: [8], state: "on"]]                                                        || "ifThen cannot carry a condition"
        "on a Repeat While"          | [capability: "repeatWhile", seconds: 5, expression: [conditions: [[capability: "Switch", deviceIds: [8], state: "on"]]],
                                        condition: [capability: "Switch", deviceIds: [8], state: "on"]]                                                        || "repeatWhile cannot carry a condition"
        "on a Wait for Expression"   | [capability: "waitExpression", expression: [conditions: [[capability: "Switch", deviceIds: [8], state: "on"]]],
                                        condition: [capability: "Switch", deviceIds: [8], state: "on"]]                                                        || "waitExpression cannot carry a condition"
    }

    def "a condition the rule defines or a well-formed new one passes the mapping"() {
        expect:
        script._rmMapActionSpec(100, [capability: "switch", action: "on", deviceIds: [8], condition: [definedIndex: 2]], "switch", "on").actSubType == "getOnOffSwitch"
        script._rmMapActionSpec(100, [capability: "log", message: "m", condition: [capability: "switch", deviceIds: [8], state: "on"]], "log", null).actSubType == "getLogMsg"
    }

    // ---- the preamble on doActPage ----

    private List events
    private Map wirePreamble(Map opts = [:]) {
        events = []
        script.metaClass._rmWriteSettingOnPage = { Integer appId, String page, String key, Object value, List applied, String hint = null, List skipped = null, Map cache = null ->
            events << ["write", page, key, value]
            def miss = (opts.notOffered ?: [:])[key]
            if (miss != null) skipped << [key: key, reason: "not_offered", offered: miss]
        }
        script.metaClass._rmClickAppButton = { Integer id, String name, String attr = null, String page = null, Map cache = null -> events << ["click", page, name] }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([app: [id: 100], configPage: [name: "doActPage", sections: [[input: [[name: "rCapab_4", type: "enum", options: opts.capabilities ?: ["Switch", "Contact"]]]]]]])
        }
        [applied: [], skipped: []]
    }

    def "preamble with a new condition: conditional type, then the condition walk on its own slot; the walk closes it"() {
        given:
        def acc = wirePreamble()

        when:
        script._rmWriteSimpleConditionalPreamble(100, 3, "switch", [capability: "switch", deviceIds: [8], state: "on"], acc.applied, acc.skipped)

        then:
        // The walk is RM's inputCapab reveal on the slot the page opened (rCapab_4); the walker's own
        // hasAll closes the condition and the preamble clicks nothing more.
        events == [["write", "doActPage", "actType.3", "condActs"],
                   ["write", "doActPage", "actSubType.3", "getCondAct"],
                   ["write", "doActPage", "rCapab_4", "Switch"],
                   ["write", "doActPage", "rDev_4", [8]],
                   ["write", "doActPage", "state_4", "on"],
                   ["click", "doActPage", "hasAll"]]
    }

    def "preamble with a defined condition: useCond, the index, then Done with this condition"() {
        given:
        def acc = wirePreamble()

        when:
        script._rmWriteSimpleConditionalPreamble(100, 3, "log", [definedIndex: 2], acc.applied, acc.skipped)

        then:
        events == [["write", "doActPage", "actType.3", "condActs"],
                   ["write", "doActPage", "actSubType.3", "getCondAct"],
                   ["write", "doActPage", "useCond.3", true],
                   ["write", "doActPage", "useCondNdx.3", 2],
                   ["click", "doActPage", "hasAll"]]
    }

    def "preamble refuses a defined index the rule does not have, before Done"() {
        given:
        def acc = wirePreamble(notOffered: ["useCondNdx.3": ["1", "5"]])

        when:
        script._rmWriteSimpleConditionalPreamble(100, 3, "log", [definedIndex: 2], acc.applied, acc.skipped)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("log.condition.definedIndex 2 is not a condition this rule defines (defined: 1, 5)")
        !events.any { it[0] == "click" }
    }

    def "preamble refuses a condition capability RM's picker does not list on this hub"() {
        given:
        def acc = wirePreamble(capabilities: ["Contact", "Motion"])

        when:
        script._rmWriteSimpleConditionalPreamble(100, 3, "switch", [capability: "Switch", deviceIds: [8], state: "on"], acc.applied, acc.skipped)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("switch.condition.capability 'Switch' is not in Rule Machine's condition list on this hub. RM lists: Contact, Motion.")
        !events.any { it[2] == "rCapab_4" || it == ["click", "doActPage", "hasAll"] }
    }

    def "a picker without the conditional sub-type is refused by the picker check, naming what it offers"() {
        given:
        def acc = wirePreamble(notOffered: ["actSubType.3": ["getIfThen", "getEndIf"]])

        when:
        script._rmWriteSimpleConditionalPreamble(100, 3, "switch", [capability: "Switch", deviceIds: [8], state: "on"], acc.applied, acc.skipped)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("switch.condition: Rule Machine does not offer a Simple Conditional Action on this row. This row's picker offers: getIfThen, getEndIf.")
        e.message.contains("This row's picker offers: getIfThen, getEndIf.")
    }

    @Unroll
    def "the picker check names what the hub lacks for an app-gated sub-type"() {
        when:
        script._rmRequireOfferedActionSubType(cap, subType, 4, [[key: "actSubType.4", reason: "not_offered", offered: ["getSetMode", "getLogMsg"]]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("does not offer '${cap}' (${subType})")
        e.message.contains("This hub's picker offers: getSetMode, getLogMsg.")

        where:
        cap                    | subType
        "garageDoor"           | "getOCGarage"
        "scene"                | "getActivateScene"
        "returnValue"          | "getReturnValue"
        "roomLights"           | "getRLActivate"
        "thermostatScheduler"  | "getSetThermostatScheduler"
        "thermostatController" | "getSetThermController"
    }

    // ---- choices RM's picker does not list ----

    private void doActPageWith(List inputs) {
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([app: [id: 100], configPage: [name: "doActPage", sections: [[input: inputs]]]])
        }
    }

    def "a scalar choice the writer skipped as not offered is refused with what the picker offers"() {
        when:
        script._rmRequireOfferedCatalogChoice(100, "musicPlayer", null, 4, ["player.@N": [8], "playerCommand.@N": "play text and restore"],
            [[key: "playerCommand.4", reason: "not_offered", value: "play text and restore", offered: ["play", "pause"]]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "musicPlayer: Rule Machine does not offer 'play text and restore' for command on this hub; it offers: play, pause."
    }

    def "a fixed command the picker does not list names the action"() {
        when:
        script._rmRequireOfferedCatalogChoice(100, "thermostatScheduler", "setTimeAway", 4, ["thermSched.@N": "setTimeAway"],
            [[key: "thermSched.4", reason: "not_offered", value: "setTimeAway", offered: ["setEcoMode", "setSched4Mode"]]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "thermostatScheduler.setTimeAway: Rule Machine does not offer setTimeAway on this hub; it offers: setEcoMode, setSched4Mode."
    }

    def "a list value is checked against the live picker"() {
        given:
        doActPageWith([[name: "sceneAct.4", type: "enum", multiple: true, options: [["91": "Hall"]]]])

        when:
        script._rmRequireOfferedCatalogChoice(100, "scene", "activate", 4, ["sceneAct.@N": [91, 859]], [])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "scene.activate: Rule Machine does not offer 859 for sceneIds on this hub; it offers: 91."
    }

    def "an app-data picker that lists nothing refuses the value"() {
        given:
        doActPageWith([[name: "modN.4", type: "enum", options: []]])

        when:
        script._rmRequireOfferedCatalogChoice(100, "thermostatScheduler", "setSched4Mode", 4, ["thermSched.@N": "setSched4Mode", "modN.@N": 1], [])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("does not offer 1 for modeId on this hub; it offers: (nothing).")
    }

    def "offered choices pass, and device and rule lists are left to their own checks without a page read"() {
        given:
        doActPageWith([[name: "sceneAct.4", type: "enum", multiple: true, options: [["91": "Hall"]]]])

        when:
        script._rmRequireOfferedCatalogChoice(100, "scene", "activate", 4, ["sceneAct.@N": [91]], [])
        script._rmRequireOfferedCatalogChoice(100, "garageDoor", "open", 4, ["garageRL.@N": false, "garageOpenClose.@N": [83]], [])

        then:
        noExceptionThrown()
    }

    // ---- the bake check ----

    @Unroll
    def "an add whose row RM leaves out of state.actionList is rolled back even when other rows render"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/installedapp/configure/json/100') { p ->
            JsonOutput.toJson([app: [id: 100, name: "Rule-5.1", label: "r", installed: true], configPage: [name: "mainPage", sections: [[input: []]]], settings: [:]])
        }
        hubGet.register('/installedapp/configure/json/100/selectActions') { p ->
            JsonOutput.toJson([app: [id: 100], configPage: [name: "selectActions", sections: [[input: [[name: "N", type: "button"]]]]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { p ->
            JsonOutput.toJson([app: [id: 100], configPage: [name: "doActPage", error: null, sections: [[input: [
                [name: "actType.2", type: "enum", options: ["messageActs"]], [name: "actSubType.2", type: "enum", options: ["getLogMsg"]], [name: "logmsg.2", type: "text"]]]]]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { p ->
            JsonOutput.toJson([app: [id: 100], configPage: [name: "mainPage", sections: [[body: [[element: "paragraph", description: "Log: 'other'"]]]]]])
        }
        hubGet.register('/installedapp/statusJson/100') { p ->
            JsonOutput.toJson([appSettings: [[name: "actType.1", value: "messageActs"], [name: "actSubType.1", value: "getLogMsg"]], appState: [[name: "actionList", value: listed]]])
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> [status: 200, location: null, data: ''] }
        script.metaClass._rmCheckRuleHealth = { Integer id, String src = "auto", String activePage = null -> [ok: true] }

        when:
        def r = script._rmAddAction(100, [capability: "log", message: "x"])

        then:
        r.actionIndex == 2
        r.rolledBack == rolled
        !rolled || (r.repairHints as List).any { it.toString().contains("it is not in the rule's action list") }

        where:
        listed     || rolled
        ["1"]      || true
        ["1", "2"] || false
    }

    // ---- whenSet ----

    def "whenSet: the toggle is written as true only when its field is given, before the field's picker"() {
        expect:
        !(script._rmMapActionSpec(100, [capability: "blind", action: "setTilt", deviceIds: [8], tilt: 10], "blind", "setTilt").fields as Map).containsKey("uVar.@N")
        (script._rmMapActionSpec(100, [capability: "blind", action: "setTilt", deviceIds: [8], tiltVariable: "tv"], "blind", "setTilt").fields as Map).keySet().toList() == ["blindTilt.@N", "uVar.@N", "xVar.@N"]
    }

    def "whenSet: an in-place edit of a variable field writes the toggle with it"() {
        given:
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([appSettings: [[name: "actType.3", value: "sceneActs"], [name: "actSubType.3", value: "getBlindTilt"],
                                             [name: "blindTilt.3", value: "8"], [name: "tiltLevel.3", value: "20"]], appState: []])
        }

        when:
        def plan = script._rmPlanModifyAction(100, 3, [tiltVariable: "tv"])

        then:
        plan.cap == "blind"
        (plan.writes as Map) == ["uVar.3": true, "xVar.3": "tv"]
    }

    // ---- discover ----

    @Unroll
    def "discover serves each new entry"() {
        when:
        def out = script._rmAddAction(100, [discover: [capability: cap]])
        def entry = out.capability as Map

        then:
        entry.name == cap
        entry.actType == actType
        entry.actSubType == subTypes
        (entry.actions ?: null) == actions
        required.every { r -> (entry.requiredFields as List)*.name.contains(r) }

        where:
        cap                    | actType     | subTypes                                                                        | actions                         | required
        "garageDoor"           | "lockActs"  | [open: "getOCGarage", close: "getOCGarage"]                                     | ["open", "close"]               | ["action", "deviceIds"]
        "valve"                | "lockActs"  | [open: "getOCValve", close: "getOCValve"]                                       | ["open", "close"]               | ["action", "deviceIds"]
        "blind"                | "sceneActs" | [setTilt: "getBlindTilt"]                                                       | ["setTilt"]                     | ["action", "deviceIds"]
        "scene"                | "sceneActs" | [activate: "getActivateScene", activatePerMode: "getActivateScenePerMode"]      | ["activate", "activatePerMode"] | ["action"]
        "thermostatController" | "lockActs"  | "getSetThermController"                                                         | null                            | ["controllerId", "deviceIds"]
        "tone"                 | "soundActs" | "getTone"                                                                       | null                            | ["deviceIds"]
        "musicPlayer"          | "soundActs" | "getSetMusicPlayer"                                                             | null                            | ["deviceIds", "command"]
        "returnValue"          | "modeActs"  | "getReturnValue"                                                                | null                            | ["type"]
        "roomLights"           | "rulesActs" | [activate: "getRLActivate", turnOff: "getRLTurnOff"]                            | ["activate", "turnOff"]         | ["action"]
    }

    def "discover serves thermostatScheduler with its commands, the one-of picker choice and each command's fields"() {
        when:
        def entry = script._rmAddAction(100, [discover: [capability: "THERMOSTATSCHEDULER"]]).capability as Map

        then:
        entry.name == "thermostatScheduler"
        entry.actions == ["setEcoMode", "setEcoOffset", "setHold", "setSched4Mode", "setTimeAway", "setThermSched", "setSchedDay"]
        (entry.actSubType as Map).values().toSet() == ["getSetThermostatScheduler"] as Set
        entry.requires == [["deviceIds", "schedulerId"]]
        (entry.exclusive as List).contains(["deviceIds", "schedulerId"])
        (entry.exclusive as List).contains(["ecoOffset", "ecoOffsetVariable"])
        (entry.conditionalRequired as Map).setEcoMode == ["ecoMode"]
        (entry.conditionalRequired as Map).setEcoOffset == [["ecoOffset", "ecoOffsetVariable"]]
        (entry.conditionalRequired as Map).setSchedDay == ["day"]
        (entry.optionalFields as List)*.name.containsAll(["schedulerId", "deviceIds", "condition", "delay"])
    }

    def "discover with an action selector serves one new action"() {
        when:
        def out = script._rmAddAction(100, [discover: [capability: "garageDoor", action: "close"]])

        then:
        out.action == "close"
        (out.capability as Map).name == "garageDoor"
    }

    def "Rule Machine's sub-type capability table is unchanged"() {
        expect:
        (script._rmSubActCapab() as Map).subMap(["getBlindTilt", "getOCGarage", "getOCValve", "getSetMusicPlayer", "getTone"]) ==
            [getBlindTilt: "WindowBlind", getOCGarage: "GarageDoorControl", getOCValve: "Valve", getSetMusicPlayer: "MusicPlayer", getTone: "Tone"]
        (script._rmSubActCapab() as Map).size() == 38
    }
}
