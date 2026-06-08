Handlebars.logger.level = 0; //3 for production

Handlebars.registerHelper('is', function(left, right, options) {
    if((left && right) && (left.toString() == right.toString())) {
        return options.fn(this)
    } else {
        return options.inverse(this)
    }
});

Handlebars.registerHelper('isOr', function(first, second, third, fourth, options) {
    if(first && second && third && fourth) {
        if(first == second || third == fourth) {
            return options.fn(this)
        } else {
            return options.inverse(this)
        }
    }
});

Handlebars.registerHelper('isNot', function(left, right, options) {
    if(left == undefined || right == undefined) {
        return options.fn(this)
    } else {
        if((left && right) && (left.toString() != right.toString())) {
            return options.fn(this)
        } else {
            return options.inverse(this)
        }
    }

});

Handlebars.registerHelper('isSetOrTrue', function(left, options) {
    if(left == undefined || left == true) {
            return options.fn(this)
        } else {
            return options.inverse(this)
        }

});

Handlebars.registerHelper('isGreater', function(left, right, options) {
    if(parseInt(left) < parseInt(right) ) {
        return options.fn(this)
    } else {
        return options.inverse(this)
    }
});

Handlebars.registerHelper('isLessThan', function(left, right, options) {
    if(left === "*") left = "-99999999999";
    if(right === "*") right = "99999999999";
    if(parseInt(left) < parseInt(right) ) {
        return options.fn(this)
    } else {
        return options.inverse(this)
    }
});

Handlebars.registerHelper('ifOr', function(first, second, options) {
    if(first || second) {
        return options.fn(this)
    } else {
        return options.inverse(this)
    }
});

Handlebars.registerHelper('ifNot', function(first, options) {
    if(first) {
        return options.inverse(this)
    } else {
        return options.fn(this)
    }
});

Handlebars.registerHelper('contains', function(obj, value, options) {
    if(obj && obj.indexOf(parseInt(value)) >= 0) {
        return options.fn(this)
    } else {
        return options.inverse(this)
    }
});

Handlebars.registerHelper('startsWith', function(left, right, options) {
    if(left.toString().startsWith(right.toString())) {
        return options.fn(this)
    } else {
        return options.inverse(this)
    }
});

Handlebars.registerHelper('startsWithOr', function(left, middle, right, options) {
    if(left.toString().startsWith(middle.toString()) || left.toString().startsWith(right)) {
        return options.fn(this)
    } else {
        return options.inverse(this)
    }
});

Handlebars.registerHelper('capitalizeFirst', function(value, options) {
    return value.toString().capitalize()
});

Handlebars.registerHelper('join', function(value, options) {
    return value.join(", ").toString()
});

Handlebars.registerHelper('formatDate', function(data, options) {
    return new DateTool().format('yyy-MM-dd hh:mm:ss z', date)
});

Handlebars.registerHelper('integerToHexString', function(value, count) {
   if(value) {
       return new HexUtils().integerToHexString(value, count)
   } else {
       return ""
   }
});

Handlebars.registerHelper('replace', function(value, find, replaceWith) {
    var x = value.toString().replace(find, replaceWith)
    return x
});

Handlebars.registerHelper('addOne', function(value) {
    return parseInt(value) + 1
});

Handlebars.registerHelper('stripSpaces', function(value) {
    if(value) {
        return value.toString().replaceAll(" ", "")
    }
});

Handlebars.registerHelper('getClassName', function(value) {
    if(value) {
        if (value instanceof Array) return "array"
        if (value instanceof Object) return "object"
        if (typeof value == "string") return "string"
        return "unknown"
    }
});

Handlebars.registerHelper('findSize', function(value) {
    if(value) {
        return value.size()
    }
});

Handlebars.registerHelper('findLength', function(value) {
    if (value) {
        var x = new JSONObject(value)
        if (x) {
            return x.map.length()
        } else {
            return 0
        }
    }
});

Handlebars.registerHelper('splitLeft', function(value) {
    if (value) {
        var left = value.split("..")
        return left[0]
    } else {
        return value
    }
});

Handlebars.registerHelper('splitRight', function(value) {
    if (value) {
        var right = value.split("..")
        return right[1]
    } else {
        return value
    }
});

Handlebars.registerHelper('lookup2', function(val, find) {
    if(val) {
        var x = val[find]
        return x
    }
});

Handlebars.registerHelper('stripBrackets', function(value) {
    if (!value.toString().contains(",")) {
        var x = value.toString().replace("[", "").replace("]", "")
        return x
    } else {
        return value
    }
});

Handlebars.registerHelper('toArray', function(val, options) {
    if (val) {
        var json = val.toString().split(",")
        return json
    }
});

Handlebars.registerHelper('encode', function(val, options) {
    var x = encodeURIComponent(JSON.stringify(val))
    return x
});

Handlebars.registerHelper('deviceNames', function(val, find, multiple) {
    if (val) {
        var x = val[find]
        if (x) {
            let result = Object.values(x).sort(Intl.Collator().compare)
            if (multiple === false || multiple === 'false') result.length = Math.min(result.length, 1)

            return result.toString().replaceAll(',','<br/>')
        }
    }
});

Handlebars.registerHelper('deviceIds', function(val, find, multiple) {
    if (val) {
        var x = val[find]
        if (x) {
            var ids = []
            let length = Object.keys(x).length
            if (multiple === false || multiple === 'false') length = Math.min(length, 1)

            for (var i = 0; i < length; i++) {
                var id = Object.keys(x)[i];
                ids.push(id);
            }
            return ids
        }
    }
});

Handlebars.registerHelper('buildCols', function(val, newLine) {
    if (val == undefined) {val = 12}
    var cssClass = ""
    if (newLine == true) {
        cssClass = "mdl-cell mdl-cell--" + val + "-col newLine"
    } else {
        cssClass = "mdl-cell mdl-cell--" + val + "-col"
    }
    return cssClass
})

Handlebars.registerHelper('convertToTime', function(val) {
    if(val) {
        var x = new moment(val);
        if (!isNaN(x.hours())) {
            return ("0" + x.hour()).slice(-2) + ":" + ("0" + x.minute()).slice(-2)
        } else {
            return val
        }
    } else {
        return val
    }
});

Handlebars.registerHelper('timeToAMPM', function(val) {
    if(val) {
        var x = new moment(val);
        return x.format("h:mm A")
    } else {
        return val
    }
});

Handlebars.registerHelper('getKey', function(val) {
    var x =  Object.keys(val)[0]
    return x.toString()
});

Handlebars.registerHelper('buttonSOC', function(val) {
    if (val == undefined || val == true) {
        return "submitOnChange"
    } else {
        return ""
    }
});


Handlebars.registerHelper('getValue', function(val, index) {
    return val[index]
});

Handlebars.registerHelper('processReturnPath', function(referrer, returnPath) {
    if (returnPath != undefined) {
        if (returnPath === "list") {
            return "/installedapp/list?section=" + (globalMenuSelection || "").toLowerCase();
        } else {
            return referrer
        }
    } else {
        return referrer
    }
});

Handlebars.registerHelper('setOptions', function(elemName, elemOptions, defaultValues, inputSettings) {
    var opts = ""
    $.each(elemOptions, function(key, option) {
        var selected = ""
        if (option && (typeof option) == "object") {
            var optionKey = Object.keys(option)[0]
            var optionValue = option[optionKey]
            if(inputSettings[elemName]) {
                if(inputSettings[elemName] == optionKey) {
                    selected = "selected=\"selected\""
                } else
                if (Object.keys(inputSettings[elemName]).length != 0) {
                    if((typeof inputSettings[elemName]) == "object" && inputSettings[elemName].filter(d => (d == optionKey)).length > 0) {
                        selected = "selected=\"selected\""
                    }
                }

            //} && Object.keys(inputSettings[elemName]).length != 0 && (inputSettings[elemName].filter(d => (d == key)).length > 0 || inputSettings[elemName].filter(d => (d == key)).length > 0)) {
            } else
            if(defaultValues != undefined) {
                if (defaultValues == optionValue || defaultValues == optionKey) {
                    selected = "selected=\"selected\""
                } else
                if((typeof defaultValues) == "object" && defaultValues.filter(d => (d == optionKey)).length > 0) {
                    selected = "selected=\"selected\""
                }
            }
                //&& (defaultValues == optionKey || (Object.keys(defaultValues).length != 0 && defaultValues.filter(d => (d == key)).length > 0 ))){
                //selected = "selected=\"selected\""
            var opt = `<option value="${optionKey}" ${selected}>${optionValue}</option>\r\n`
            opts += opt
        }
        if ((typeof option) == "string" || (typeof option) == "number") {
            if(inputSettings[elemName]) {
                if (inputSettings[elemName] == option) {
                    selected = "selected=\"selected\""
                } else
                if (typeof inputSettings[elemName] == "object")  {
                    if (inputSettings[elemName].filter(d => (d == option)).length > 0) {
                        selected = "selected=\"selected\""
                    } else
                      if ((typeof key) == 'string' && inputSettings[elemName].filter(d => (d == key)).length > 0) {
                          selected = "selected=\"selected\""
                      }
                } else
                if ((typeof key) == 'string' && inputSettings[elemName] == key) {
                    selected = "selected=\"selected\""
                }
            } else {// in default Values
                if(defaultValues != undefined) {
                    if (defaultValues == option) {
                        selected = "selected=\"selected\""
                    } else
                    if((typeof defaultValues) == "object" && (defaultValues.filter(d => (d == option)).length > 0 || defaultValues.filter(d => (d == key)).length > 0)) {
                        selected = "selected=\"selected\""
                    }
                }
            }
            var value = ((typeof key) == 'number') ? option : key
            var displayValue = option
            var opt = `<option value="${value}" ${selected}>${displayValue}</option>\r\n`
            opts += opt
        }
    });
    return opts
})


Handlebars.registerHelper('setCheckBoxes', function(elemName, elemOptions, defaultValues, inputSettings) {
    var opts = ""
    $.each(elemOptions, function(key, option) {
        var selected = ""
        if (option && (typeof option) == "object") {
            var optionKey = Object.keys(option)[0]
            var optionValue = option[optionKey]
            if(inputSettings[elemName]) {
                if(inputSettings[elemName] == optionKey) {
                    selected = "checked"
                } else
                if (Object.keys(inputSettings[elemName]).length != 0) {
                    if((typeof inputSettings[elemName]) == "object" && inputSettings[elemName].filter(d => (d == optionKey)).length > 0) {
                        selected = "checked"
                    }
                }

                //} && Object.keys(inputSettings[elemName]).length != 0 && (inputSettings[elemName].filter(d => (d == key)).length > 0 || inputSettings[elemName].filter(d => (d == key)).length > 0)) {
            } else
            if(defaultValues != undefined) {
                if (defaultValues == optionValue || defaultValues == optionKey) {
                    selected = "checked"
                } else
                if((typeof defaultValues) == "object" && defaultValues.filter(d => (d == optionKey)).length > 0) {
                    selected = "checked"
                }
            }
            //&& (defaultValues == optionKey || (Object.keys(defaultValues).length != 0 && defaultValues.filter(d => (d == key)).length > 0 ))){
            //selected = "selected=\"selected\""
            var opt = `<input type="checkbox" value="${optionKey}" ${selected}>${optionValue}</input><br>\r\n`
            opts += opt
        }
        if ((typeof option) == "string" || (typeof option) == "number") {
            if(inputSettings[elemName]) {
                if (inputSettings[elemName] == option) {
                    selected = "checked"
                } else
                if (typeof inputSettings[elemName] == "object")  {
                    if (inputSettings[elemName].filter(d => (d == option)).length > 0) {
                        selected = "checked"
                    } else
                    if ((typeof key) == 'string' && inputSettings[elemName].filter(d => (d == key)).length > 0) {
                        selected = "checked"
                    }
                } else
                if ((typeof key) == 'string' && inputSettings[elemName] == key) {
                    selected = "checked"
                }
            } else {// in default Values
                if(defaultValues != undefined) {
                    if (defaultValues == option || defaultValues == key) {
                        selected = "checked"
                    } else
                    if((typeof defaultValues) == "object" && (defaultValues.filter(d => (d == option)).length > 0 || defaultValues.filter(d => (d == key)).length > 0)) {
                        selected = "checked"
                    }
                }
            }
            var value = ((typeof key) == 'number') ? option : key
            var displayValue = option
            var opt = `<input type="checkbox" value="${value}" ${selected}>${displayValue}</input><br>\r\n`
            opts += opt
        }
    });
    return opts
})

Handlebars.registerHelper('setMenuOptions', function(elemOptions) {
    var opts = ""
    $.each(elemOptions, function(key, option) {
        var selected = ""
        if (option && (typeof option) == "object") {
            var optionKey = Object.keys(option)[0]
            var optionValue = option[optionKey]
            var opt = `<a href="#" class="dd" data-value="${optionKey}">${optionValue}</a>\r\n`
            //var opt = `<option value="${optionKey}" ${selected}>${optionValue}</option>\r\n`
            opts += opt
        }
        if ((typeof option) == "string" || (typeof option) == "number") {
            var value = ((typeof key) == 'number') ? option : key
            var displayValue = option
            var opt = `<a href="#" class="dd" data-value="${value}">${displayValue}</a>\r\n`
            //var opt = `<option value="${value}" ${selected}>${displayValue}</option>\r\n`
            opts += opt
        }
    });
    return opts
});

Handlebars.registerHelper('toJson', function(object) {
    return JSON.stringify(object);
});

Handlebars.registerHelper('concat', function() {
    let outStr = '';
    for (let arg in arguments){
        if (typeof arguments[arg] != 'object') {
            outStr += arguments[arg];
        }
    }
    return outStr;
});

String.prototype.replaceAll = function(search, replacement) {
    var target = this;
    return target.split(search).join(replacement);
};

Handlebars.partials = Handlebars.templates;
