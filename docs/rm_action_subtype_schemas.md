# Per-subtype field map (action wizard schemas captured live 2026-04-25)

## switchActs/getOnOffSwitch
  baked=True
  fields:
    onOffSwitch.<N> | capability.switch | Switches to turn on | multi=True
    onOff.<N> | bool | Turn <b>on</b> or off? | multi=False
    trackSwitch.<N> | bool | Track event switch? | multi=False
    optSwitch.<N> | bool | Command only switches that are off? | multi=False
    delayAct.<N> | enum | Delay? | multi=False

## switchActs/getToggleSwitch
  baked=True
  fields:
    toggleSwitch.<N> | capability.switch | Toggle switches | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## switchActs/getFlashSwitch
  baked=True
  fields:
    flashSwitch.<N> | capability.switch | Flash switches | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## switchActs/getModeSwitch
  ERROR: No signature of method: java.lang.String.sort() is applicable for argument types: () values: []
Possible solutions: drop(int), tr(java.lang.CharSequence, java.lang.CharSequence), wait(), strip(), next

## switchActs/getChooseSwitch
  ERROR: No signature of method: java.lang.String.sort() is applicable for argument types: () values: []
Possible solutions: drop(int), tr(java.lang.CharSequence, java.lang.CharSequence), wait(), strip(), next

## switchActs/getPushButton
  ERROR: Command 'hasCapability' is not supported by device 'BAT-RM-Btn1' (298).

## switchActs/getPushButtonPerMode
  ERROR: Command 'hasCapability' is not supported by device 'BAT-RM-Btn1' (298).

## switchActs/getChooseButton
  ERROR: No signature of method: java.lang.String.sort() is applicable for argument types: () values: []
Possible solutions: drop(int), tr(java.lang.CharSequence, java.lang.CharSequence), wait(), strip(), next

## dimmerActs/getSetDimmer
  baked=True
  fields:
    dimA.<N> | capability.switchLevel | Select dimmer device to set level | multi=True
    uVar.<N> | bool | Variable level? | multi=False
    uVar2.<N> | bool | Variable fade? | multi=False
    dimTrack.<N> | bool | Track event dimmer? | multi=False
    dimLA.<N> | number | To this level (0..100) | multi=False
    dimRA.<N> | number | With this fade (seconds) | multi=False
    xVar.<N> | enum | To this variable level | multi=False
    xVar2.<N> | enum | With this variable fade (seconds) | multi=False
    delayAct.<N> | enum | Delay? | multi=False

## dimmerActs/getToggleDimmer
  baked=False
  fields:
    dimA.<N> | capability.switchLevel | Select dimmer device to toggle | multi=True
    uVar.<N> | bool | Variable level? | multi=False
    uVar2.<N> | bool | Variable fade? | multi=False
    dimLA.<N> | number | To this level (0..100) | multi=False
    dimRA.<N> | number | With this fade (seconds) | multi=False
    xVar.<N> | enum | To this variable level | multi=False
    xVar2.<N> | enum | With this variable fade (seconds) | multi=False

## dimmerActs/getAdjustDimmer
  baked=False
  fields:
    dimA.<N> | capability.switchLevel | Select dimmer device to adjust level | multi=True
    uVar.<N> | bool | Variable adjustment? | multi=False
    uVar2.<N> | bool | Variable fade? | multi=False
    dimAdj.<N> | number | By this amount (-100..100) | multi=False
    dimAdjR.<N> | number | With this fade (seconds) | multi=False
    xVar.<N> | enum | By this variable amount (-100..100) | multi=False
    xVar2.<N> | enum | With this variable fade (seconds) | multi=False

## dimmerActs/getDimmersPerMode
  baked=False
  fields:
    dimM.<N> | capability.switchLevel | Select dimmer for level per mode | multi=True
    dimmerModes.<N> | enum | Select modes | multi=True
    uVarM4.<N> | bool | Variable level? | multi=False
    level4.<N> | number | Level for <b>Away</b> | multi=False
    uVar2.<N> | bool | Variable fade? | multi=False
    dimMR.<N> | number | With this fade (seconds) | multi=False
    xVarM4.<N> | enum | Variable level for <b>Away</b> | multi=False
    xVar2.<N> | enum | With this variable fade (seconds) | multi=False

## dimmerActs/getFadeDimmer
  baked=False
  fields:
    dimFade.<N> | capability.switchLevel | Select dimmer device to fade over time | multi=True
    dimFadeUp.<N> | bool | Raise or <b>Lower</b> Dimmer | multi=False
    uVar.<N> | bool | Variable target level? | multi=False
    uVar2.<N> | bool | Variable minutes? | multi=False
    dimFadeTarget.<N> | number | Lower to this level | multi=False
    dimFadeTime.<N> | number | Over this many minutes | multi=False
    dimFadeInterval.<N> | decimal | Using this interval (1..60) | multi=False
    xVar.<N> | enum | Raise to this variable level | multi=False
    xVar2.<N> | enum | Over this variable number of minutes | multi=False

## dimmerActs/getStopFade
  baked=True
  fields:
    delayAct.<N> | enum | Delay? | multi=False

## dimmerActs/getRLDimmer
  baked=True
  fields:
    dimRL.<N> | bool | <b>Raise</b> or Lower dimmer? | multi=False
    dimRaiseLower.<N> | capability.switchLevel | Select dimmer device to start raising level | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## dimmerActs/getStopDimmer
  baked=True
  fields:
    dimStop.<N> | capability.switchLevel | Select dimmer device to stop changing | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## dimmerActs/getSetColor
  baked=True
  fields:
    bulbs.<N> | capability.colorControl | Set color for these bulbs | multi=True
    color.<N> | enum | Bulb color? | multi=False
    uVar.<N> | bool | Variable level? | multi=False
    colorLevel.<N> | number | Bulb level? | multi=False
    colorH.<N> | color | Pick a color | multi=False
    delayAct.<N> | enum | Delay? | multi=False

## dimmerActs/getToggleColor
  baked=True
  fields:
    bulbsTog.<N> | capability.colorControl | Toggle color for these bulbs | multi=True
    colorTog.<N> | enum | Bulb color? | multi=False
    uVar.<N> | bool | Variable level? | multi=False
    colorTogLevel.<N> | number | Bulb level? | multi=False
    colorH.<N> | color | Pick a color | multi=False
    delayAct.<N> | enum | Delay? | multi=False

## dimmerActs/getColorPerMode
  baked=True
  fields:
    bulbsM.<N> | capability.colorControl | Set color per mode for these bulbs | multi=True
    colorModes.<N> | enum | Select color by mode | multi=True
    color4.<N> | enum | Bulb color for <b>Away</b> | multi=False
    uVar4.<N> | bool | Variable level? | multi=False
    colorLevel4.<N> | number | Bulb level? | multi=False
    colorH4.<N> | color | Pick a color | multi=False
    delayAct.<N> | enum | Delay? | multi=False

## dimmerActs/getSetColorTemp
  baked=False
  fields:
    ct.<N> | capability.colorTemperature | Set color temperature for these bulbs | multi=True
    uVar.<N> | bool | Variable color temperature? | multi=False
    uVar2.<N> | bool | Variable level? | multi=False
    ctL.<N> | number | To this color temperature | multi=False
    ctLevel.<N> | number | Bulb level? | multi=False
    xVar.<N> | enum | To this variable color temperature | multi=False
    uVar3.<N> | bool | Variable fade? | multi=False
    xVar2.<N> | enum | Variable bulb level? | multi=False

## dimmerActs/getToggleColorTemp
  baked=False
  fields:
    ctTog.<N> | capability.colorTemperature | Toggle color temperature for these bulbs | multi=True
    uVar.<N> | bool | Variable color temperature? | multi=False
    uVar2.<N> | bool | Variable level? | multi=False
    ctLTog.<N> | number | To this color temperature | multi=False
    ctTogLevel.<N> | number | Bulb level? | multi=False
    xVar.<N> | enum | To this variable color temperature | multi=False
    uVar3.<N> | bool | Variable fade? | multi=False
    xVar2.<N> | enum | Variable bulb level? | multi=False

## dimmerActs/getColorTempPerMode
  baked=True
  fields:
    ctM.<N> | capability.colorTemperature | Set color temperature per mode for these bulbs | multi=True
    ctModes.<N> | enum | Select color temperature by mode | multi=True
    uVar4.<N> | bool | Variable color temperature? | multi=False
    uVar24.<N> | bool | Variable level? | multi=False
    uVar34.<N> | bool | Variable fade? | multi=False
    ctMode4.<N> | number | Color temperature for <b>Away</b> | multi=False
    ctMode4.35Level | number | Bulb level for <b>Away</b>? | multi=False
    delayAct.<N> | enum | Delay? | multi=False
    xVar4.<N> | enum | Variable color temperature for <b>Away</b> | multi=False
    xVar24.<N> | enum | Variable bulb level for <b>Away</b>? | multi=False

## dimmerActs/getFadeCT
  baked=False
  fields:
    ctFade.<N> | capability.colorTemperature | Select color temperature bulb to change over time | multi=True
    ctFadeUp.<N> | bool | Raise or <b>Lower</b> temperature? | multi=False
    uVar.<N> | bool | Variable target temperature? | multi=False
    uVar2.<N> | bool | Variable minutes? | multi=False
    ctFadeTarget.<N> | number | Lower to this termperature | multi=False
    ctFadeTime.<N> | number | Over this many minutes | multi=False
    ctFadeInterval.<N> | decimal | Using this interval (1..60) | multi=False
    bothCTandL.<N> | bool | Lower level with temperature? | multi=False
    xVar.<N> | enum | Raise to this variable temperature | multi=False
    xVar2.<N> | enum | Over this variable number of minutes | multi=False
    ctFadeLevel.<N> | number | Change to this level | multi=False

## dimmerActs/getStopCTFade
  baked=True
  fields:
    delayAct.<N> | enum | Delay? | multi=False

## sceneActs/getRLShade
  baked=True
  fields:
    shadeRL.<N> | bool | <b>Open</b> or Close shades? | multi=False
    shadeOpenClose.<N> | capability.windowShade,capability.windowBlind | Select shade to open | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## sceneActs/getShadePosition
  baked=False
  fields:
    shadePosition.<N> | capability.windowShade,capability.windowBlind | Set position for these shades | multi=True
    uVar.<N> | bool | Use variable position? | multi=False
    shadeLevel.<N> | number | Select position | multi=False
    xVar.<N> | enum | Select variable position | multi=False

## sceneActs/getStopShade
  baked=True
  fields:
    shadeStop.<N> | capability.windowShade,capability.windowBlind | Stop these shades | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## sceneActs/getFanSpeed
  baked=True
  fields:
    fanDevice.<N> | capability.fanControl | Set speed on these fans | multi=True
    fanSpeed.<N> | enum | Select fan speed | multi=False
    delayAct.<N> | enum | Delay? | multi=False

## sceneActs/getAdjustFan
  baked=True
  fields:
    fanAdjust.<N> | capability.fanControl | Cycle fans -  Low ... Med ... High then off | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## lockActs/getLULock
  baked=True
  fields:
    lockRL.<N> | bool | <b>Lock</b> or Unlock lock? | multi=False
    lockLockUnlock.<N> | capability.lock | Lock these locks | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## lockActs/getSetThermostat
  baked=True
  fields:
    thermo.<N> | capability.thermostat | Set these thermostats | multi=True
    thermoMode.<N> | enum | Select thermostat mode | multi=False
    thermoFan.<N> | enum | Fan setting | multi=False
    uVar.<N> | bool | Variable heating point? | multi=False
    thermoSetHeat.<N> | decimal | Set heating point | multi=False
    uVar2.<N> | bool | Variable adjust heating point? | multi=False
    thermoAdjHeat.<N> | decimal | Adjust heating point +/- | multi=False
    uVar3.<N> | bool | Variable cooling point? | multi=False
    thermoSetCool.<N> | decimal | Set cooling point | multi=False
    uVar4.<N> | bool | Variable adjust cooling point? | multi=False
    thermoAdjCool.<N> | decimal | Adjust cooling point +/- | multi=False
    delayAct.<N> | enum | Delay? | multi=False
    xVar.<N> | enum | Variable heating point | multi=False
    xVar2.<N> | enum | Variable adjust heating point | multi=False
    xVar3.<N> | enum | Variable cooling point | multi=False
    xVar4.<N> | enum | Variable adjust cooling point | multi=False

## messageActs/getMsg
  baked=True
  fields:
    msg.<N> | textarea |  | multi=False
    ranMsg.<N> | bool | Random message? | multi=False
    note.<N> | capability.notification | On these notification devices | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## messageActs/getLogMsg
  baked=True
  fields:
    logmsg.<N> | textarea |  | multi=False
    delayAct.<N> | enum | Delay? | multi=False

## messageActs/getHTTPGet
  baked=True
  fields:
    httper.<N> | text | Enter URL to send request to | multi=False
    delayAct.<N> | enum | Delay? | multi=False

## messageActs/getHTTPPost
  baked=True
  fields:
    httpPostType.<N> | enum | Select content type | multi=False
    httper.<N> | text | Enter URL to send request to | multi=False
    httpPostBody.<N> | textarea | Enter body for POST | multi=False
    delayAct.<N> | enum | Delay? | multi=False

## messageActs/getPingIP
  baked=True
  fields:
    pingIP.<N> | text | Enter IP address to ping | multi=False
    delayAct.<N> | enum | Delay? | multi=False

## soundActs/getSetVolume
  baked=False
  fields:
    volume.<N> | capability.audioVolume | Select audio device | multi=True
    uVar.<N> | bool | Set variable player level? | multi=False
    volumeVal.<N> | number | Select volume | multi=False
    xVar.<N> | enum | Variable player level | multi=False

## soundActs/getMuteUnmute
  baked=True
  fields:
    mU.<N> | bool | <b>Mute</b> or Unmute? | multi=False
    muteUnmute.<N> | capability.audioVolume | Mute this audio device | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## soundActs/getChime
  baked=True
  fields:
    chime.<N> | capability.chime | Select chime devices | multi=True
    chimePlayStop.<N> | enum | Select Play or Stop | multi=False
    chimePlaySound.<N> | text | Select sound number | multi=False
    delayAct.<N> | enum | Delay? | multi=False

## soundActs/getSiren
  baked=True
  fields:
    siren.<N> | capability.alarm | Select siren/strobe device | multi=True
    sirenAct.<N> | enum | Select Siren Action | multi=False
    delayAct.<N> | enum | Delay? | multi=False

## modeActs/getSetMode
  baked=True
  fields:
    mode.<N> | enum | Set the mode | multi=False
    delayAct.<N> | enum | Delay? | multi=False

## modeActs/getDefinedAction
  baked=False
  fields:
    useLastDev.<N> | bool | Use Last Event Device? | multi=False
    myCapab.<N> | enum | Select capability of action device | multi=False

## rulesActs/getSetPrivateBoolean
  baked=True
  fields:
    pvRuleType.<N> | enum | Select rule type for Set Private Boolean | multi=False
    pvTF.<N> | bool | Set <b>True</b> or False? | multi=False
    privateT.<N> | enum | Set Private Boolean for these rules | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## rulesActs/getRuleActions
  baked=True
  fields:
    runRuleType.<N> | enum | Select rule type to run action | multi=False
    ruleAct.<N> | enum | Run Actions of these rules | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## rulesActs/getStopActions
  baked=True
  fields:
    stopRuleType.<N> | enum | Select rule type to stop | multi=False
    stopAct.<N> | enum | Cancel timed actions of these rules (cancels Periodic, all Delayed Actions, Repeated Actions, Dimmer Fade, Wait for Events and Wait for Truth) | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## rulesActs/getPauseResumeRules
  baked=True
  fields:
    pR.<N> | bool | <b>Pause</b> or Resume? | multi=False
    pauseRuleType.<N> | enum | Select rule type to pause | multi=False
    pauseRule.<N> | enum | Pause these rules | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## deviceActs/getCapture
  baked=True
  fields:
    capture.<N> | capability.switch | Capture the state of these devices | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## deviceActs/getRestore
  baked=True
  fields:
    delayAct.<N> | enum | Delay? | multi=False

## deviceActs/getRefreshSwitch
  baked=True
  fields:
    refresh.<N> | capability.refresh | Refresh these devices | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## deviceActs/getPollSwitch
  baked=True
  fields:
    poll.<N> | capability.polling | Poll these devices | multi=True
    delayAct.<N> | enum | Delay? | multi=False

## deviceActs/getDisable
  baked=False
  fields:
    disEn.<N> | bool | <b>Disable</b> or Enable Device? | multi=False
    devDisable.<N> | capability.* | Select Device | multi=False

## repeatActs/getRepeat
  baked=False
  fields:
    uVar.<N> | bool | Variable repeat interval? | multi=False
    repeatHour.<N> | number | Hours | multi=False
    repeatMinute.<N> | number | Minutes | multi=False
    repeatSecond.<N> | decimal | Seconds | multi=False
    uVar2.<N> | bool | Repeat variable n times? | multi=False
    repeatN.<N> | number | Repeat n times | multi=False
    stopRepeat.<N> | bool | Stoppable? | multi=False
    xVar.<N> | enum | Variable repeat interval | multi=False
    xVar2.<N> | enum | Repeat variable n times | multi=False

## repeatActs/getStopRepeat
  baked=True
  fields:
    delayAct.<N> | enum | Delay? | multi=False

## delayActs/getDelay
  baked=False
  fields:
    uVar.<N> | bool | Set delay seconds from variable? | multi=False
    delayHour.<N> | number | Hours | multi=False
    delayMinute.<N> | number | Minutes | multi=False
    delaySecond.<N> | decimal | Seconds | multi=False
    cancelAct.<N> | bool | Cancelable? | multi=False
    randomAct.<N> | bool | Random? | multi=False
    xVar.<N> | enum | Select variable | multi=False

## delayActs/getCancelDelay
  baked=True
  fields:
    delayAct.<N> | enum | Delay? | multi=False

## delayActs/getExitRule
  baked=True
  fields:

## delayActs/getComment
  baked=True
  fields:
    comment.<N> | textarea | Comment | multi=False

