"""The shipped gesture blueprint loads with HA's blueprint schema and yields a valid automation."""

from __future__ import annotations

from pathlib import Path

from homeassistant.components.automation.config import AUTOMATION_BLUEPRINT_SCHEMA, async_validate_config_item
from homeassistant.components.blueprint import models
from homeassistant.core import HomeAssistant
from homeassistant.setup import async_setup_component
from homeassistant.util import yaml as yaml_util

BLUEPRINT = Path(__file__).resolve().parents[2] / "blueprints/automation/kioskclock/gesture_action.yaml"


async def test_gesture_blueprint_is_valid(hass: HomeAssistant) -> None:
    assert await async_setup_component(hass, "automation", {})
    bp = models.Blueprint(yaml_util.load_yaml(BLUEPRINT), expected_domain="automation", schema=AUTOMATION_BLUEPRINT_SCHEMA)
    inputs = models.BlueprintInputs(
        bp,
        {
            "use_blueprint": {
                "path": "kioskclock/gesture_action.yaml",
                "input": {
                    "gesture_entity": "event.smart_clock_2_ab12_tap",
                    "gesture_type": "double",
                    "gesture_actions": [{"action": "light.toggle", "target": {"entity_id": "light.bedroom"}}],
                },
            }
        },
    )
    config = inputs.async_substitute()
    config["id"] = "test"
    config["alias"] = "test"
    validated = await async_validate_config_item(hass, "test", config)
    assert validated is not None and validated.validation_error is None, getattr(validated, "validation_error", None)
