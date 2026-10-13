"""Decode BAT529 catalog constants for source-based metadata guards, without executing Groovy."""

import json
import re

READER = "_bat529EmbeddedCatalogRead"
CHUNKS = re.compile(
    rf"{READER}\(\s*\[\s*(?P<chunks>'(?:\\.|[^'\\])*'"
    r"(?:\s*,\s*'(?:\\.|[^'\\])*')*)\s*\]\s*\)",
    re.S,
)
QUOTED = re.compile(r"'(?:\\.|[^'\\])*'", re.S)


def _groovy_string(literal: str) -> str:
    text = literal[1:-1]
    out = []
    index = 0
    escapes = {"\\": "\\", "'": "'", "n": "\n", "r": "\r", "t": "\t", "b": "\b", "f": "\f"}
    while index < len(text):
        char = text[index]
        if char == "\\":
            index += 1
            if index >= len(text) or text[index] not in escapes:
                raise ValueError("Unsupported Groovy escape in embedded catalog chunk")
            char = escapes[text[index]]
        out.append(char)
        index += 1
    return "".join(out)


def _decode(value):
    if isinstance(value, list):
        if len(value) != 2 or value[0] not in ("m", "l") or not isinstance(value[1], list):
            raise ValueError("Malformed embedded catalog container")
        if value[0] == "l":
            return [_decode(child) for child in value[1]]
        result = {}
        for entry in value[1]:
            if not isinstance(entry, list) or len(entry) != 2 or not isinstance(entry[0], str):
                raise ValueError("Malformed embedded catalog Map entry")
            result[entry[0]] = _decode(entry[1])
        return result
    if value is None or isinstance(value, (str, bool)):
        return value
    if type(value) is int and -(2**31) <= value <= 2**31 - 1:
        return value
    raise ValueError("Unsupported embedded catalog scalar")


def _literal(value, depth=1):
    indent = "    " * depth
    child_indent = "    " * (depth + 1)
    if isinstance(value, dict):
        if not value:
            return "[:]"
        entries = []
        for key, child in value.items():
            name = key if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) else json.dumps(key, ensure_ascii=True)
            entries.append(child_indent + name + ": " + _literal(child, depth + 1))
        return "[\n" + ",\n".join(entries) + "\n" + indent + "]"
    if isinstance(value, list):
        if not value:
            return "[]"
        return "[\n" + ",\n".join(child_indent + _literal(child, depth + 1) for child in value) + "\n" + indent + "]"
    return json.dumps(value, ensure_ascii=True)


def expand_embedded_catalogs(source: str) -> str:
    """Provide literal metadata to existing extractors; preserve raw sandbox scans separately.

    Unknown/malformed embedded representations fail closed. This reads current source constants,
    never a saved pre-conversion source, a manifest, or a network resource.
    """
    if READER not in source:
        return source

    def expand_call(call):
        encoded = "".join(_groovy_string(item.group(0)) for item in QUOTED.finditer(call.group("chunks")))
        try:
            graph = _decode(json.loads(encoded))
        except (ValueError, TypeError) as error:
            raise ValueError("Invalid embedded catalog: " + str(error)) from error
        if not isinstance(graph, (dict, list)):
            raise ValueError("Embedded catalog root must be a Map or List")
        return _literal(graph)

    return CHUNKS.sub(expand_call, source)
