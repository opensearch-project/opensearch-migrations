import re
from typing import Any, Dict, List, Optional

from rich.markup import escape
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Container, Horizontal, Vertical
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Static


PICKER_PAGE_SIZE = 10


class ExternalResourcePickerModal(ModalScreen[Optional[Dict[str, Any]]]):
    CSS = """
    ExternalResourcePickerModal { align: center middle; background: $background 60%; }
    #dialog { width: 82; max-height: 22; border: thick $primary; background: $surface; padding: 0 1; }
    #title { text-align: center; margin-bottom: 0; }
    #rows { height: auto; max-height: 10; overflow-y: auto; }
    #row-doc { color: gray; margin-top: 0; min-height: 2; }
    #actions { align: center middle; height: 1; margin-top: 0; }
    Button { margin: 0 1 0 0; min-width: 5; height: 1; min-height: 1; border: none; padding: 0 1; }
    #rows Button { width: 100%; text-align: left; }
    """
    BINDINGS = [
        Binding("enter", "select", "Select", show=False, priority=True),
        Binding("c", "create", "Create"),
        Binding("m", "manual", "Manual"),
        Binding("v", "view", "View"),
        Binding("u", "update", "Update"),
        Binding("a", "toggle_show_all", "Show All"),
        Binding("n", "next_page", "Next"),
        Binding("p", "previous_page", "Prev"),
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
        external_ref: Optional[Dict[str, Any]] = None,
    ):
        super().__init__()
        self.title_text = title
        self.rows = rows
        self.current_value = current_value
        self.documentation = documentation
        self.can_create = can_create
        self.external_ref = external_ref or {}
        self.show_all = False
        self.page_index = 0

    def compose(self) -> ComposeResult:
        with Container(id="dialog"):
            yield Static(escape(self.title_text), id="title")
            with Vertical(id="rows"):
                for index in range(PICKER_PAGE_SIZE):
                    yield Button("", id=f"row-{index}")
                yield Static("", id="empty")
            yield Static("", id="row-doc")
            with Horizontal(id="actions"):
                yield Button("Select", id="select")
                yield Button("c Create", id="create", variant="success", disabled=not self.can_create)
                yield Button("m Manual", id="manual")
                yield Button("v View", id="view", disabled=not bool(self.rows))
                yield Button("u Update", id="update", disabled=not bool(self.rows))
                yield Button("p Prev", id="previous-page")
                yield Button("n Next", id="next-page")
                yield Button("a All", id="toggle-show-all")
                yield Button("Cancel", id="cancel", variant="error")

    def on_mount(self) -> None:
        self._render_rows()
        if self._displayed_rows():
            self.query_one("#row-0", Button).focus()
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

    def action_toggle_show_all(self) -> None:
        if not self._has_hidden_rows():
            return
        self.show_all = not self.show_all
        self.page_index = 0
        self._render_rows()
        self._focus_first_row_or_action()

    def action_next_page(self) -> None:
        if self.page_index >= self._page_count() - 1:
            return
        self.page_index += 1
        self._render_rows()
        self._focus_first_row_or_action()

    def action_previous_page(self) -> None:
        if self.page_index <= 0:
            return
        self.page_index -= 1
        self._render_rows()
        self._focus_first_row_or_action()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        button_id = event.button.id or ""
        if button_id.startswith("row-"):
            row = self._displayed_rows()[int(button_id.removeprefix("row-"))]
            self.dismiss({"action": "select", "row": row})
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
        elif button_id == "toggle-show-all":
            self.action_toggle_show_all()
        elif button_id == "next-page":
            self.action_next_page()
        elif button_id == "previous-page":
            self.action_previous_page()
        elif button_id == "cancel":
            self.dismiss(None)

    def _focused_row(self) -> Optional[Dict[str, Any]]:
        focused = self.focused
        if isinstance(focused, Button) and focused.id and focused.id.startswith("row-"):
            displayed = self._displayed_rows()
            index = int(focused.id.removeprefix("row-"))
            return displayed[index] if index < len(displayed) else None
        displayed = self._displayed_rows()
        return displayed[0] if displayed else None

    def _update_row_doc(self) -> None:
        row = self._focused_row()
        summary = self._page_summary()
        requirement = _requirement_hint(self.external_ref)
        if not row:
            pieces = [_empty_picker_hint(self.rows, self._has_hidden_rows()), requirement, summary]
            self.query_one("#row-doc", Static).update(escape("\n".join(part for part in pieces if part).strip()))
            return
        message = _row_hint(row)
        pieces = [message, requirement, summary]
        self.query_one("#row-doc", Static).update(escape("\n".join(part for part in pieces if part).strip()))

    def _visible_rows(self) -> List[Dict[str, Any]]:
        if self.show_all:
            return list(self.rows)
        return [
            row for row in self.rows
            if row.get("status") == "matching" or row.get("current")
        ]

    def _displayed_rows(self) -> List[Dict[str, Any]]:
        start = self.page_index * PICKER_PAGE_SIZE
        return self._visible_rows()[start:start + PICKER_PAGE_SIZE]

    def _page_count(self) -> int:
        visible = len(self._visible_rows())
        return max(1, (visible + PICKER_PAGE_SIZE - 1) // PICKER_PAGE_SIZE)

    def _has_hidden_rows(self) -> bool:
        return len(self._visible_rows()) < len(self.rows) or self.show_all

    def _render_rows(self) -> None:
        displayed = self._displayed_rows()
        for index in range(PICKER_PAGE_SIZE):
            button = self.query_one(f"#row-{index}", Button)
            if index < len(displayed):
                button.label = _row_label(displayed[index], self.show_all)
                button.disabled = False
                button.display = True
            else:
                button.disabled = True
                button.display = False
        empty = self.query_one("#empty", Static)
        empty.update(_empty_picker_text(self.rows, self.show_all))
        empty.display = not bool(displayed)
        self._update_action_buttons()
        self._update_row_doc()

    def _update_action_buttons(self) -> None:
        has_displayed_rows = bool(self._displayed_rows())
        self.query_one("#select", Button).disabled = not has_displayed_rows
        self.query_one("#view", Button).disabled = not has_displayed_rows
        self.query_one("#update", Button).disabled = not has_displayed_rows
        self.query_one("#previous-page", Button).disabled = self.page_index <= 0
        self.query_one("#next-page", Button).disabled = self.page_index >= self._page_count() - 1
        toggle = self.query_one("#toggle-show-all", Button)
        toggle.disabled = not self._has_hidden_rows()
        toggle.label = "a Matches" if self.show_all else "a All"

    def _focus_first_row_or_action(self) -> None:
        if self._displayed_rows():
            self.query_one("#row-0", Button).focus()
        elif self.can_create:
            self.query_one("#create", Button).focus()
        else:
            self.query_one("#manual", Button).focus()
        self._update_row_doc()

    def _page_summary(self) -> str:
        visible_count = len(self._visible_rows())
        if visible_count == 0:
            hidden_count = len(self.rows)
            hidden = f" {hidden_count} hidden (a all)." if hidden_count and not self.show_all else ""
            return f"0/{len(self.rows)} shown.{hidden}"
        start = self.page_index * PICKER_PAGE_SIZE + 1
        end = min(start + PICKER_PAGE_SIZE - 1, visible_count)
        hidden_count = len(self.rows) - visible_count
        hidden = f" {hidden_count} hidden (a all)." if hidden_count and not self.show_all else ""
        return f"{start}-{end}/{visible_count} shown.{hidden}"


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


def _row_label(row: Dict[str, Any], show_missing_keys: bool = False) -> str:
    name = str(row.get("name") or "")
    current = " (current)" if row.get("current") else ""
    missing = _row_missing_keys(row)
    missing_text = f" (missing {', '.join(missing)})" if show_missing_keys and missing else ""
    return escape(f"{name or '<unnamed>'}{current}{missing_text}")


def _row_hint(row: Dict[str, Any]) -> str:
    status = str(row.get("status") or "warn")
    message = str(row.get("message") or "")
    missing = _row_missing_keys(row)
    if missing:
        return f"Missing keys: {', '.join(missing)}"
    if message:
        return message
    if status == "warn":
        return "May not satisfy this reference."
    return ""


def _row_missing_keys(row: Dict[str, Any]) -> List[str]:
    message = str(row.get("message") or "")
    match = re.search(r"(?:^|;\s*)missing\s+([^.;]+)", message)
    if not match:
        return []
    return [
        key.strip()
        for key in re.split(r",\s*|\s+and\s+", match.group(1))
        if key.strip()
    ]


def _requirement_hint(external_ref: Dict[str, Any]) -> str:
    k8s_hint = external_ref.get("k8s") or {}
    required_keys = [str(key) for key in k8s_hint.get("requiredKeys") or []]
    recommended_keys = [str(key) for key in k8s_hint.get("recommendedKeys") or []]
    if required_keys:
        return f"Keys: {', '.join(required_keys)}."
    if recommended_keys:
        return f"Recommended: {', '.join(recommended_keys)}."
    description = str(external_ref.get("description") or "").strip()
    if description:
        return description
    return ""


def _empty_picker_text(rows: List[Dict[str, Any]], show_all: bool) -> str:
    if rows and not show_all:
        return "No matching resources on this page."
    return "No Kubernetes resources found."


def _empty_picker_hint(rows: List[Dict[str, Any]], has_hidden_rows: bool) -> str:
    if rows and has_hidden_rows:
        return "No matching resources are shown by default."
    if rows:
        return "No resources on this page."
    return "No Kubernetes resources found."


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
