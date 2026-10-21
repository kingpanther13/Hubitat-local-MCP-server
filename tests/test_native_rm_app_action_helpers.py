"""Offline catalog selection coverage for native app-action fixtures."""

import native_rm_parity_app_actions_e2e as native_apps
import pytest


def catalog_row(**overrides):
    return {"id": "92", "name": "Thermostat Controllers", "system": True,
            "isBuiltIn": True, "allowNewInstances": True, **overrides}


def test_creatable_native_type_accepts_real_public_catalog_without_namespace():
    row = catalog_row()
    assert "namespace" not in row
    assert native_apps.creatable_native_type([row], "Thermostat Controllers") is row


@pytest.mark.parametrize("overrides", [
    {"system": False, "isBuiltIn": False},
    {"system": False}, {"isBuiltIn": False},
    {"allowNewInstances": False}, {"allowNewInstances": None},
    {"name": "Thermostat Controller Legacy"},
])
def test_creatable_native_type_refuses_community_noncreatable_and_wrong_names(overrides):
    with pytest.raises(AssertionError, match="Expected one creatable built-in type"):
        native_apps.creatable_native_type([catalog_row(**overrides)], "Thermostat Controllers")


def test_creatable_native_type_does_not_resolve_ambiguity_by_picking_first():
    with pytest.raises(AssertionError, match="found 2"):
        native_apps.creatable_native_type([catalog_row(), catalog_row(id="99")], "Thermostat Controllers")
