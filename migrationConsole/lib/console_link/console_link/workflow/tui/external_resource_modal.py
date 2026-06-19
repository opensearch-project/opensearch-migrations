import re
from typing import Any, Dict, List, Optional

from rich.markup import escape
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Container, Horizontal, Vertical
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Static


class ExternalResourcePickerModal(ModalScreen[Optional[Dict[str, Any]]]):
    CSS = """
    ExternalResourcePickerModal { align: center middle; background: $background 60%; }
    #dialog { width: 92; max-height: 36; border: thick $primary; background: $surface; padding: 1 2; }
    #title { text-align: center; margin-bottom: 1; }
    #documentation { color: gray; margin-bottom: 1; }
    #rows { height: auto; max-height: 20; overflow-y: auto; }
    #row-doc { color: gray; margin-top: 1; min-height: 1; }
    #actions { align: center middle; height: 3; margin-top: 1; }
    Button { margin: 0 1 1 0; }
    #rows Button { width: 100%; text-align: left; }
    """
    BINDINGS = [
        Binding("enter", "select", "Select", show=False, priority=True),
        Binding("c", "create", "Create"),
        Binding("m", "manual", "Manual"),
        Binding("v", "view", "View"),
        Binding("u", "update", "Update"),
        Binding("up", "focus_previous", "Up", show=False),
        Binding("down", "focus_next", "Down", show=False),
        Binding("escape", "cancel", "Cancel", show=False),
    ]

    def __init__(
        self,
        title: str,
        rows: List[Dict[str, Any]],
        current_value: Any = None,
        documentation: str = "",
        can_create: bool = False,
    ):
        super().__init__()
        self.title_text = title
        self.rows = rows
        self.current_value = current_value
        self.documentation = documentation
        self.can_create = can_create

    def compose(self) -> ComposeResult:
        with Container(id="dialog"):
            yield Static(escape(self.title_text), id="title")
            yield Static(escape(self.documentation), id="documentation")
            with Vertical(id="rows"):
                if self.rows:
                    for index, row in enumerate(self.rows):
                        yield Button(_row_label(row), id=f"row-{index}")
                else:
                    yield Static("No Kubernetes resources found.", id="empty")
            yield Static("", id="row-doc")
            with Horizontal(id="actions"):
                yield Button("Select (Enter)", id="select")
                yield Button("Create (c)", id="create", variant="success", disabled=not self.can_create)
                yield Button("Manual (m)", id="manual")
                yield Button("View (v)", id="view", disabled=not bool(self.rows))
                yield Button("Update (u)", id="update", disabled=not bool(self.rows))
                yield Button("Cancel", id="cancel", variant="error")

    def on_mount(self) -> None:
        if self.rows:
            index = next(
                (i for i, row in enumerate(self.rows) if row.get("name") == self.current_value),
                0,
            )
            self.query_one(f"#row-{index}", Button).focus()
        elif self.can_create:
            self.query_one("#create", Button).focus()
        else:
            self.query_one("#manual", Button).focus()
        self._update_row_doc()

    def action_focus_previous(self) -> None:
        self.focus_previous()
        self._update_row_doc()

    def action_focus_next(self) -> None:
        self.focus_next()
        self._update_row_doc()

    def action_cancel(self) -> None:
        self.dismiss(None)

    def action_create(self) -> None:
        if self.can_create:
            self.dismiss({"action": "create"})

    def action_manual(self) -> None:
        self.dismiss({"action": "manual"})

    def action_select(self) -> None:
        row = self._focused_row()
        if row:
            self.dismiss({"action": "select", "row": row})

    def action_view(self) -> None:
        row = self._focused_row()
        if row:
            self.dismiss({"action": "view", "row": row})

    def action_update(self) -> None:
        row = self._focused_row()
        if row:
            self.dismiss({"action": "update", "row": row})

    def on_button_pressed(self, event: Button.Pressed) -> None:
        button_id = event.button.id or ""
        if button_id.startswith("row-"):
            self.dismiss({"action": "select", "row": self.rows[int(button_id.removeprefix("row-"))]})
        elif button_id == "select":
            self.action_select()
        elif button_id == "create":
            self.action_create()
        elif button_id == "manual":
            self.action_manual()
        elif button_id == "view":
            self.action_view()
        elif button_id == "update":
            self.action_update()
        elif button_id == "cancel":
            self.dismiss(None)

    def _focused_row(self) -> Optional[Dict[str, Any]]:
        focused = self.focused
        if isinstance(focused, Button) and focused.id and focused.id.startswith("row-"):
            return self.rows[int(focused.id.removeprefix("row-"))]
        return self.rows[0] if self.rows else None

    def _update_row_doc(self) -> None:
        row = self._focused_row()
        if not row:
            self.query_one("#row-doc", Static).update("")
            return
        message = str(row.get("message") or "")
        current = "Current value. " if row.get("current") else ""
        self.query_one("#row-doc", Static).update(escape(f"{current}{message}".strip()))


class ExternalResourceViewModal(ModalScreen[Optional[Dict[str, Any]]]):
    CSS = """
    ExternalResourceViewModal { align: center middle; background: $background 60%; }
    #dialog { width: 72; height: auto; border: thick $primary; background: $surface; padding: 1 2; }
    #title { text-align: center; margin-bottom: 1; }
    #contents { margin-bottom: 1; }
    #actions { align: center middle; height: 3; }
    Button { margin: 0 1; min-width: 12; }
    """
    BINDINGS = [
        Binding("u", "update", "Update"),
        Binding("escape", "cancel", "Back", show=False),
    ]

    def __init__(self, external_ref: Dict[str, Any], resource: Dict[str, Any]):
        super().__init__()
        self.external_ref = external_ref
        self.resource = resource

    def compose(self) -> ComposeResult:
        title = f"{self.resource.get('kind', 'Resource')} {self.resource.get('name', '')}".strip()
        with Container(id="dialog"):
            yield Static(escape(title), id="title")
            yield Static(_resource_view_text(self.external_ref, self.resource), id="contents")
            with Horizontal(id="actions"):
                yield Button("Update (u)", id="update", variant="primary")
                yield Button("Back", id="back")

    def on_mount(self) -> None:
        self.query_one("#update", Button).focus()

    def action_update(self) -> None:
        self.dismiss({"action": "update"})

    def action_cancel(self) -> None:
        self.dismiss(None)

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "update":
            self.action_update()
        else:
            self.dismiss(None)


class ExternalResourceFormModal(ModalScreen[Optional[Dict[str, str]]]):
    CSS = """
    ExternalResourceFormModal { align: center middle; background: $background 60%; }
    #dialog { width: 76; height: auto; border: thick $primary; background: $surface; padding: 1 2; }
    #title { text-align: center; margin-bottom: 1; }
    #documentation { color: gray; margin-bottom: 1; }
    .field-label { margin-top: 1; }
    #validation { color: $error; margin: 1 0; min-height: 1; }
    #actions { align: center middle; height: 3; }
    Button { margin: 0 1; min-width: 12; }
    Input { width: 100%; }
    """
    BINDINGS = [
        Binding("enter", "submit", "Save", show=False, priority=True),
        Binding("escape", "cancel", "Cancel", show=False),
    ]

    def __init__(
        self,
        external_ref: Dict[str, Any],
        mode: str,
        initial_values: Optional[Dict[str, str]] = None,
        existing_keys: Optional[List[str]] = None,
        documentation: str = "",
    ):
        super().__init__()
        self.external_ref = external_ref
        self.mode = mode
        self.initial_values = initial_values or {}
        self.existing_keys = set(existing_keys or [])
        self.documentation = documentation
        self.fields = list(((external_ref.get("create") or {}).get("fields") or []))
        self._field_input_ids: Dict[str, str] = {}
        self._confirm_input_ids: Dict[str, str] = {}

    def compose(self) -> ComposeResult:
        create = self.external_ref.get("create") or {}
        verb = "Update" if self.mode == "update" else "Create"
        with Container(id="dialog"):
            yield Static(escape(f"{verb} {create.get('label') or self.external_ref.get('displayName') or 'Resource'}"), id="title")
            yield Static(escape(self.documentation), id="documentation")
            for index, field in enumerate(self.fields):
                field_id = f"field-{index}"
                self._field_input_ids[field["name"]] = field_id
                yield Static(escape(str(field.get("label") or field["name"])), classes="field-label")
                yield Input(
                    value=self._initial_value_for_field(field),
                    id=field_id,
                    password=_field_is_sensitive(field),
                    placeholder=self._placeholder_for_field(field),
                    disabled=self.mode == "update" and field["name"] == self._name_field(),
                )
                if field.get("confirm"):
                    confirm_id = f"field-{index}-confirm"
                    self._confirm_input_ids[field["name"]] = confirm_id
                    yield Static(escape(f"Confirm {field.get('label') or field['name']}"), classes="field-label")
                    yield Input(
                        value="",
                        id=confirm_id,
                        password=True,
                        placeholder=self._placeholder_for_field(field),
                    )
            yield Static("", id="validation")
            with Horizontal(id="actions"):
                yield Button(verb, id="save", variant="success")
                yield Button("Cancel", id="cancel", variant="error")

    def on_mount(self) -> None:
        first_id = next(iter(self._field_input_ids.values()), None)
        if first_id:
            first = self.query_one(f"#{first_id}", Input)
            if first.disabled:
                self.focus_next()
            else:
                first.focus()

    def action_cancel(self) -> None:
        self.dismiss(None)

    def action_submit(self) -> None:
        values = {
            field["name"]: self.query_one(f"#{self._field_input_ids[field['name']]}", Input).value
            for field in self.fields
        }
        message = self._validation_message(values)
        self.query_one("#validation", Static).update(escape(message or ""))
        if message:
            return
        self.dismiss(values)

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "save":
            self.action_submit()
        else:
            self.dismiss(None)

    def _validation_message(self, values: Dict[str, str]) -> Optional[str]:
        for field in self.fields:
            name = field["name"]
            value = values.get(name, "")
            if self.mode == "update" and _field_is_sensitive(field) and value == "":
                output_key = _output_key_for_field(self.external_ref, name)
                if output_key in self.existing_keys:
                    continue
            if field.get("required") and not value.strip():
                return f"{field.get('label') or name} is required."
            if "k8s-name" in (field.get("validationIds") or []) and value and not _is_k8s_name(value):
                return f"{field.get('label') or name} must be a valid Kubernetes DNS name."
            confirm_id = self._confirm_input_ids.get(name)
            if confirm_id:
                confirm_value = self.query_one(f"#{confirm_id}", Input).value
                if value != confirm_value:
                    return f"{field.get('label') or name} and confirmation do not match."
        return None

    def _initial_value_for_field(self, field: Dict[str, Any]) -> str:
        if self.mode == "update" and _field_is_sensitive(field):
            return ""
        return str(self.initial_values.get(field["name"], field.get("default") or ""))

    def _placeholder_for_field(self, field: Dict[str, Any]) -> str:
        if self.mode == "update" and _field_is_sensitive(field):
            output_key = _output_key_for_field(self.external_ref, field["name"])
            return "leave unchanged" if output_key in self.existing_keys else "required"
        return ""

    def _name_field(self) -> Optional[str]:
        return ((self.external_ref.get("create") or {}).get("apply") or {}).get("nameField")


def _row_label(row: Dict[str, Any]) -> str:
    status = str(row.get("status") or "warn")
    name = str(row.get("name") or "")
    type_name = str(row.get("type") or row.get("kind") or "")
    keys = ",".join(str(key) for key in row.get("keys") or [])
    current = " (current)" if row.get("current") else ""
    message = f"  {row.get('message')}" if row.get("message") else ""
    return escape(f"{status:<9} {name:<28} {type_name:<28} {keys}{current}{message}")


def _resource_view_text(external_ref: Dict[str, Any], resource: Dict[str, Any]) -> str:
    values = resource.get("values") or {}
    lines = [
        f"Name: {escape(str(resource.get('name') or ''))}",
        f"Type: {escape(str(resource.get('type') or resource.get('kind') or ''))}",
        "",
    ]
    for key, field in _output_key_fields(external_ref).items():
        value = "<hidden>" if _field_is_sensitive(field) else values.get(key, "")
        lines.append(f"{escape(key)}: {escape(str(value))}")
    extra_keys = sorted(set(values.keys()) - set(_output_key_fields(external_ref).keys()))
    if extra_keys:
        lines.append("")
        lines.append(f"Additional keys: {escape(', '.join(extra_keys))}")
    return "\n".join(lines)


def _output_key_fields(external_ref: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    create = external_ref.get("create") or {}
    output = create.get("output") or {}
    fields = {field.get("name"): field for field in create.get("fields") or []}
    mappings = output.get("stringData") or output.get("data") or {}
    return {
        key: fields.get(source.get("fromField"), {"name": source.get("fromField"), "sensitive": True})
        for key, source in mappings.items()
    }


def _output_key_for_field(external_ref: Dict[str, Any], field_name: str) -> Optional[str]:
    create = external_ref.get("create") or {}
    output = create.get("output") or {}
    mappings = output.get("stringData") or output.get("data") or {}
    for key, source in mappings.items():
        if source.get("fromField") == field_name:
            return key
    return None


def values_for_form(external_ref: Dict[str, Any], resource: Optional[Dict[str, Any]]) -> Dict[str, str]:
    values: Dict[str, str] = {}
    if resource and resource.get("name"):
        name_field = ((external_ref.get("create") or {}).get("apply") or {}).get("nameField")
        if name_field:
            values[name_field] = str(resource.get("name") or "")
    resource_values = (resource or {}).get("values") or {}
    create = external_ref.get("create") or {}
    output = create.get("output") or {}
    mappings = output.get("stringData") or output.get("data") or {}
    for key, source_map in mappings.items():
        field_name = source_map.get("fromField")
        if field_name and key in resource_values:
            values[field_name] = str(resource_values.get(key) or "")
    return values


def _field_is_sensitive(field: Dict[str, Any]) -> bool:
    if "sensitive" in field:
        return bool(field.get("sensitive"))
    return field.get("input") in {"password", "secretMultilineText"}


def _is_k8s_name(value: str) -> bool:
    return bool(re.fullmatch(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*", value))
