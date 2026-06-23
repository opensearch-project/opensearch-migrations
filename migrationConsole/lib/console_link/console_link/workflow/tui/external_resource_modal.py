import json
import re
from typing import Any, Dict, List, Optional

from rich.markup import escape
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Container, Horizontal, Vertical
from textual.screen import ModalScreen
from textual.widget import Widget
from textual.widgets import Button, Input, Static, TextArea

from .modal_button_navigation import BUTTON_ARROW_BINDINGS, ButtonArrowNavigationMixin, ModalButton


PICKER_PAGE_SIZE = 10


class MouseOnlyModalButton(ModalButton, can_focus=False):
    pass


class ExternalResourcePickerModal(ButtonArrowNavigationMixin, ModalScreen[Optional[Dict[str, Any]]]):
    CSS = """
    ExternalResourcePickerModal { align: center middle; background: $background 60%; }
    #dialog { width: 82; height: auto; max-height: 22; border: thick $primary; background: $surface; padding: 0 1; }
    #title { text-align: center; margin-bottom: 0; }
    #requirement { text-align: center; color: gray; margin-bottom: 1; }
    #rows { height: auto; max-height: 10; overflow-y: auto; margin-bottom: 1; }
    #row-doc { color: gray; margin-bottom: 1; }
    #actions { height: auto; }
    .action-row { align: center middle; height: 1; }
    Button { margin: 0 1 0 0; min-width: 5; height: 1; min-height: 1; border: none; padding: 0 1; }
    #rows Button { width: 100%; text-align: left; content-align: left middle; }
    """
    BUTTON_NAV_SELECTOR = "#actions Button"
    BINDINGS = [
        *BUTTON_ARROW_BINDINGS,
        Binding("enter", "select", "Select", show=False, priority=True),
        Binding("c", "create", "Create"),
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
            yield Static(escape(_requirement_title(self.external_ref)), id="requirement")
            with Vertical(id="rows"):
                for index in range(PICKER_PAGE_SIZE):
                    yield ModalButton("", id=f"row-{index}")
                yield Static("", id="empty")
            yield Static("", id="row-doc")
            with Vertical(id="actions"):
                with Horizontal(classes="action-row"):
                    yield MouseOnlyModalButton("Select (<Enter>)", id="select", variant="primary")
                    yield MouseOnlyModalButton("Update (u)", id="update", disabled=not bool(self.rows))
                    yield MouseOnlyModalButton("Cancel", id="cancel", variant="error")

    def on_mount(self) -> None:
        self.query_one("#requirement", Static).display = bool(_requirement_title(self.external_ref))
        self._render_rows()
        initial_entry_index = self._initial_entry_index()
        if initial_entry_index is not None:
            self._focus_entry_index(initial_entry_index)
        else:
            self.set_focus(None)
        self._update_row_doc()

    def action_focus_previous(self) -> None:
        if self._focused_row_index() is not None:
            self._focus_entry_by_delta(-1)
            return
        self.focus_previous()
        self._update_row_doc()

    def action_focus_next(self) -> None:
        if self._focused_row_index() is not None:
            self._focus_entry_by_delta(1)
            return
        self.focus_next()
        self._update_row_doc()

    def action_cancel(self) -> None:
        self.dismiss(None)

    def action_create(self) -> None:
        if self.can_create:
            self.dismiss({"action": "create"})

    def action_select(self) -> None:
        self._select_entry(self._focused_entry())

    def action_update(self) -> None:
        row = self._selected_resource_row()
        if row:
            self.dismiss({"action": "update", "row": row})

    def action_toggle_show_all(self) -> None:
        if not self._has_hidden_rows():
            return
        self._set_nonmatching_expanded(not self.show_all)

    def action_next_page(self) -> None:
        self._show_next_page()

    def action_previous_page(self) -> None:
        self._show_previous_page()

    def on_mouse_scroll_down(self, event) -> None:
        if self._focus_entry_by_delta(1):
            event.stop()

    def on_mouse_scroll_up(self, event) -> None:
        if self._focus_entry_by_delta(-1):
            event.stop()

    def action_focus_button_previous(self) -> None:
        if self._focused_row_index() is not None:
            self._collapse_focused_tree_entry()

    def action_focus_button_next(self) -> None:
        if self._focused_row_index() is not None:
            self._expand_focused_tree_entry()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        button_id = event.button.id or ""
        if button_id.startswith("row-"):
            entry = self._entry_at_button_id(button_id)
            if not entry:
                return
            self._focus_entry_index(self._absolute_index_for_button_id(button_id))
            self._select_entry(entry)
        elif button_id == "select":
            self._select_entry(self._focused_entry())
        elif button_id == "create":
            self.action_create()
        elif button_id == "update":
            self.action_update()
        elif button_id == "cancel":
            self.dismiss(None)

    def _focused_row(self) -> Optional[Dict[str, Any]]:
        entry = self._focused_entry()
        if entry and entry.get("type") == "resource":
            return entry.get("row")
        return self._selected_resource_row()

    def _focused_entry(self) -> Optional[Dict[str, Any]]:
        index = self._focused_entry_index()
        return self._entry_at_visible_index(index) if index is not None else None

    def _focused_row_index(self) -> Optional[int]:
        focused = self.app.focused or self.focused
        if not isinstance(focused, Button) or not focused.id or not focused.id.startswith("row-"):
            return None
        index = int(focused.id.removeprefix("row-"))
        return index if index < len(self._displayed_entries()) else None

    def _focused_entry_index(self) -> Optional[int]:
        row_index = self._focused_row_index()
        if row_index is None:
            return None
        return self.page_index * PICKER_PAGE_SIZE + row_index

    def _entry_at_button_id(self, button_id: str) -> Optional[Dict[str, Any]]:
        return self._entry_at_visible_index(self._absolute_index_for_button_id(button_id))

    def _absolute_index_for_button_id(self, button_id: str) -> int:
        return self.page_index * PICKER_PAGE_SIZE + int(button_id.removeprefix("row-"))

    def _entry_at_visible_index(self, index: Optional[int]) -> Optional[Dict[str, Any]]:
        if index is None:
            return None
        entries = self._visible_entries()
        return entries[index] if 0 <= index < len(entries) else None

    def _select_entry(self, entry: Optional[Dict[str, Any]]) -> None:
        if not entry:
            return
        if entry.get("type") == "create":
            self.action_create()
        elif entry.get("type") == "group" and entry.get("group") == "nonmatching":
            self._set_nonmatching_expanded(not self.show_all)
        elif entry.get("type") == "resource":
            self.dismiss({"action": "select", "row": entry.get("row")})

    def _selected_resource_row(self) -> Optional[Dict[str, Any]]:
        entry = self._focused_entry()
        if entry and entry.get("type") == "resource":
            return entry.get("row")
        return None

    def _show_next_page(self) -> bool:
        if self.page_index >= self._page_count() - 1:
            return False
        self.page_index += 1
        self._render_rows()
        self._focus_first_row_or_action()
        return True

    def _show_first_page(self) -> bool:
        if self.page_index == 0:
            if self._focus_first_row_or_action():
                return True
            return bool(self._displayed_entries())
        self.page_index = 0
        self._render_rows()
        return self._focus_first_row_or_action()

    def _show_last_page(self, focus_last: bool = False) -> bool:
        last_page = self._page_count() - 1
        if self.page_index == last_page:
            if focus_last and self._displayed_entries():
                self._focus_row(len(self._displayed_entries()) - 1)
                return True
            return bool(self._displayed_entries())
        self.page_index = last_page
        self._render_rows()
        if focus_last:
            return self._focus_last_row_or_action()
        return self._focus_first_row_or_action()

    def _set_nonmatching_expanded(self, expanded: bool) -> None:
        if not self._has_hidden_rows() or self.show_all == expanded:
            self._update_row_doc()
            return
        self.show_all = expanded
        self._render_rows()
        self._focus_nonmatching_group()

    def _expand_focused_tree_entry(self) -> None:
        entry = self._focused_entry()
        if entry and entry.get("type") == "group" and entry.get("group") == "nonmatching" and not self.show_all:
            self._set_nonmatching_expanded(True)

    def _collapse_focused_tree_entry(self) -> None:
        entry = self._focused_entry()
        if not self.show_all or not entry:
            return
        if entry.get("type") == "group" and entry.get("group") == "nonmatching":
            self._set_nonmatching_expanded(False)
        elif entry.get("type") == "resource" and self._is_nonmatching_row(entry.get("row")):
            self._set_nonmatching_expanded(False)

    def _focus_nonmatching_group(self) -> None:
        for index, entry in enumerate(self._visible_entries()):
            if entry.get("type") == "group" and entry.get("group") == "nonmatching":
                self.page_index = index // PICKER_PAGE_SIZE
                self._render_rows()
                self._focus_entry_index(index)
                return
        self._focus_first_row_or_action()

    def _show_previous_page(self) -> bool:
        if self.page_index <= 0:
            return False
        self.page_index -= 1
        self._render_rows()
        self._focus_first_row_or_action()
        return True

    def _update_row_doc(self) -> None:
        entry = self._focused_entry()
        message = ""
        if entry and entry.get("type") == "resource":
            message = _row_hint(entry.get("row") or {})
        elif entry and entry.get("type") == "group" and entry.get("group") == "nonmatching" and self._nonmatching_rows():
            message = "Press Enter or Right to show resources that may not satisfy this reference."
        doc = self.query_one("#row-doc", Static)
        doc.update(escape(message))
        doc.display = bool(message)
        self._update_action_buttons()

    def _visible_rows(self) -> List[Dict[str, Any]]:
        return [
            entry.get("row")
            for entry in self._visible_entries()
            if entry.get("type") == "resource"
        ]

    def _displayed_rows(self) -> List[Dict[str, Any]]:
        return [
            entry.get("row")
            for entry in self._displayed_entries()
            if entry.get("type") == "resource"
        ]

    def _visible_entries(self) -> List[Dict[str, Any]]:
        entries = []
        if self.can_create:
            entries.append({"type": "create", "label": "+ Create New (c)"})
        matching_rows = self._matching_rows()
        if matching_rows:
            entries.append({"type": "group", "group": "matching", "label": "Matching"})
            entries.extend({"type": "resource", "row": row, "label": f"  {_row_label(row, False)}"} for row in matching_rows)
        nonmatching_rows = self._nonmatching_rows()
        if nonmatching_rows:
            marker = "▼" if self.show_all else "▶"
            entries.append({
                "type": "group",
                "group": "nonmatching",
                "label": f"{marker} Non-Matching {_resource_kind_plural(self.external_ref, self.rows)}",
            })
            if self.show_all:
                entries.extend({"type": "resource", "row": row, "label": f"  {_row_label(row, True)}"} for row in nonmatching_rows)
        return entries

    def _displayed_entries(self) -> List[Dict[str, Any]]:
        start = self.page_index * PICKER_PAGE_SIZE
        return self._visible_entries()[start:start + PICKER_PAGE_SIZE]

    def _matching_rows(self) -> List[Dict[str, Any]]:
        return [
            row for row in self.rows
            if row.get("status") == "matching" or row.get("current")
        ]

    def _nonmatching_rows(self) -> List[Dict[str, Any]]:
        matching = set(id(row) for row in self._matching_rows())
        return [row for row in self.rows if id(row) not in matching]

    def _is_nonmatching_row(self, row: Optional[Dict[str, Any]]) -> bool:
        if row is None:
            return False
        return id(row) in {id(candidate) for candidate in self._nonmatching_rows()}

    def _page_count(self) -> int:
        visible = len(self._visible_entries())
        return max(1, (visible + PICKER_PAGE_SIZE - 1) // PICKER_PAGE_SIZE)

    def _has_hidden_rows(self) -> bool:
        return bool(self._nonmatching_rows())

    def _render_rows(self) -> None:
        displayed = self._displayed_entries()
        for index in range(PICKER_PAGE_SIZE):
            button = self.query_one(f"#row-{index}", Button)
            if index < len(displayed):
                entry = displayed[index]
                button.label = entry.get("label") or ""
                button.disabled = not self._entry_is_focusable(entry)
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
        has_displayed_entries = bool(self._displayed_entries())
        has_active_resource = bool(self._selected_resource_row())
        self.query_one("#select", Button).disabled = not has_displayed_entries
        self.query_one("#update", Button).disabled = not has_active_resource

    def _initial_entry_index(self) -> Optional[int]:
        entries = self._visible_entries()
        for index, entry in enumerate(entries):
            row = entry.get("row") or {}
            if entry.get("type") == "resource" and (row.get("current") or row.get("name") == self.current_value):
                return index
        for index, entry in enumerate(entries):
            if entry.get("type") == "resource":
                return index
        for index, entry in enumerate(entries):
            if self._entry_is_focusable(entry):
                return index
        return None

    def _entry_is_focusable(self, entry: Dict[str, Any]) -> bool:
        return entry.get("type") != "group" or entry.get("group") == "nonmatching"

    def _focus_first_row_or_action(self) -> bool:
        if self._focus_entry_by_visible_index(0, 1):
            return True
        self.set_focus(None)
        self._update_row_doc()
        return False

    def _focus_last_row_or_action(self) -> bool:
        if self._focus_entry_by_visible_index(len(self._visible_entries()) - 1, -1):
            return True
        self.set_focus(None)
        self._update_row_doc()
        return False

    def _focus_row(self, index: int) -> None:
        self._focus_entry_by_visible_index(self.page_index * PICKER_PAGE_SIZE + index, 1)

    def _focus_entry_by_visible_index(self, index: int, direction: int) -> bool:
        focusable = self._focusable_entry_indexes()
        if not focusable:
            return False
        if index in focusable:
            target = index
        else:
            if direction >= 0:
                candidates = [candidate for candidate in focusable if candidate >= index]
                target = candidates[0] if candidates else focusable[0]
            else:
                candidates = [candidate for candidate in focusable if candidate <= index]
                target = candidates[-1] if candidates else focusable[-1]
        self._focus_entry_index(target)
        return True

    def _focus_entry_by_delta(self, delta: int) -> bool:
        focusable = self._focusable_entry_indexes()
        if not focusable:
            return False
        current_index = self._focused_entry_index()
        if current_index in focusable:
            target = focusable[(focusable.index(current_index) + delta) % len(focusable)]
        else:
            target = focusable[0 if delta >= 0 else -1]
        self._focus_entry_index(target)
        return True

    def _focusable_entry_indexes(self) -> List[int]:
        return [
            index for index, entry in enumerate(self._visible_entries())
            if self._entry_is_focusable(entry)
        ]

    def _focus_entry_index(self, index: int) -> None:
        target_page = index // PICKER_PAGE_SIZE
        if target_page != self.page_index:
            self.page_index = target_page
            self._render_rows()
        else:
            self.page_index = target_page
        displayed = self._displayed_entries()
        if not displayed:
            return
        row_index = index % PICKER_PAGE_SIZE
        if row_index >= len(displayed):
            row_index = len(displayed) - 1
        self.set_focus(self.query_one(f"#row-{row_index}", Button))
        self._update_row_doc()


class ExternalResourceFormModal(ButtonArrowNavigationMixin, ModalScreen[Optional[Dict[str, str]]]):
    CSS = """
    ExternalResourceFormModal { align: center middle; background: $background 60%; }
    #dialog { width: 76; height: auto; border: thick $primary; background: $surface; padding: 0 1; }
    #title { text-align: center; margin-bottom: 0; }
    #documentation { color: gray; margin-bottom: 1; }
    .field-label { margin-top: 0; }
    #validation { color: $error; margin: 0 0 1 0; min-height: 1; }
    #actions { align: center middle; height: 1; }
    Button { margin: 0 1 0 0; min-width: 5; height: 1; min-height: 1; border: none; padding: 0 1; }
    Input { width: 100%; }
    TextArea { width: 100%; height: 5; }
    """
    BUTTON_NAV_SELECTOR = "#actions Button"
    BINDINGS = [
        *BUTTON_ARROW_BINDINGS,
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
                yield self._input_widget_for_field(field, field_id)
                if field.get("confirm"):
                    confirm_id = f"field-{index}-confirm"
                    self._confirm_input_ids[field["name"]] = confirm_id
                    yield Static(escape(f"Confirm {field.get('label') or field['name']}"), classes="field-label")
                    yield self._input_widget_for_field(field, confirm_id, confirm=True)
            yield Static("", id="validation")
            with Horizontal(id="actions"):
                yield ModalButton(f"{verb} (<Enter>)", id="save", variant="success")
                yield ModalButton("Cancel (Esc)", id="cancel", variant="error")

    def on_mount(self) -> None:
        self.query_one("#documentation", Static).display = bool(self.documentation)
        first_id = next(iter(self._field_input_ids.values()), None)
        if first_id:
            first = self.query_one(f"#{first_id}")
            if getattr(first, "disabled", False):
                self.focus_next()
            else:
                first.focus()

    def action_cancel(self) -> None:
        self.dismiss(None)

    def action_submit(self) -> None:
        values = {
            field["name"]: self._field_value(self._field_input_ids[field["name"]])
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
            validation_message = _field_validation_message(field, value)
            if validation_message:
                return validation_message
            confirm_id = self._confirm_input_ids.get(name)
            if confirm_id:
                confirm_value = self._field_value(confirm_id)
                if value != confirm_value:
                    return f"{field.get('label') or name} and confirmation do not match."
        return None

    def _input_widget_for_field(self, field: Dict[str, Any], field_id: str, confirm: bool = False) -> Widget:
        placeholder = self._placeholder_for_field(field)
        disabled = self.mode == "update" and field["name"] == self._name_field()
        value = "" if confirm else self._initial_value_for_field(field)
        if _field_is_multiline(field):
            return TextArea(
                value,
                id=field_id,
                placeholder=placeholder,
                disabled=disabled,
                show_line_numbers=False,
                tab_behavior="focus",
            )
        return Input(
            value=value,
            id=field_id,
            password=_field_is_sensitive(field),
            placeholder=placeholder,
            disabled=disabled,
        )

    def _field_value(self, field_id: str) -> str:
        widget = self.query_one(f"#{field_id}")
        if isinstance(widget, Input):
            return widget.value
        if isinstance(widget, TextArea):
            return widget.text
        return ""

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


def _requirement_title(external_ref: Dict[str, Any]) -> str:
    k8s_hint = external_ref.get("k8s") or {}
    required_keys = [str(key) for key in k8s_hint.get("requiredKeys") or []]
    recommended_keys = [str(key) for key in k8s_hint.get("recommendedKeys") or []]
    if required_keys:
        return f"(Required Keys: {', '.join(required_keys)})"
    if recommended_keys:
        return f"(Recommended Keys: {', '.join(recommended_keys)})"
    description = str(external_ref.get("description") or "").strip()
    if description:
        return description
    return ""


def _resource_kind_plural(external_ref: Dict[str, Any], rows: List[Dict[str, Any]]) -> str:
    k8s_hint = external_ref.get("k8s") or {}
    kind = str(k8s_hint.get("resource") or "").strip()
    if not kind:
        kind = str(next((row.get("kind") for row in rows if row.get("kind")), "Resource"))
    if kind.endswith("s"):
        return kind
    return f"{kind}s"


def _empty_picker_text(rows: List[Dict[str, Any]], show_all: bool) -> str:
    if rows and not show_all:
        return "No matching resources on this page."
    return "No Kubernetes resources found."


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


def _field_is_multiline(field: Dict[str, Any]) -> bool:
    return field.get("input") in {"multilineText", "secretMultilineText"}


def _field_validation_message(field: Dict[str, Any], value: str) -> Optional[str]:
    label = str(field.get("label") or field.get("name") or "Value")
    for validation_id in field.get("validationIds") or []:
        if validation_id == "non-empty" and not value.strip():
            return f"{label} is required."
        if validation_id == "k8s-name" and value and not _is_k8s_name(value):
            return f"{label} must be a valid Kubernetes DNS name."
        if validation_id == "configmap-key" and value and not _is_config_map_key(value):
            return f"{label} must be a valid ConfigMap key."
        if validation_id == "pem-certificate-chain" and value and not _looks_like_pem_certificate_chain(value):
            return f"{label} must include at least one PEM CERTIFICATE block."
        if validation_id == "pem-private-key" and value and not _looks_like_pem_private_key(value):
            return f"{label} must include a PEM PRIVATE KEY block."
        if validation_id == "log4j-properties" and value and not _looks_like_log4j_properties(value):
            return f"{label} must include at least one Log4j2 property assignment."
        if validation_id == "json" and value:
            try:
                json.loads(value)
            except json.JSONDecodeError as e:
                return f"{label} must be valid JSON: {e.msg}."
    return None


def _is_k8s_name(value: str) -> bool:
    return bool(re.fullmatch(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*", value))


def _is_config_map_key(value: str) -> bool:
    return bool(re.fullmatch(r"(?!\.{1,2}$)(?!\.\.)[A-Za-z0-9._-]+", value))


def _looks_like_pem_certificate_chain(value: str) -> bool:
    return bool(re.search(r"-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----", value.strip()))


def _looks_like_pem_private_key(value: str) -> bool:
    return bool(re.search(r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]+?-----END [A-Z ]*PRIVATE KEY-----", value.strip()))


def _looks_like_log4j_properties(value: str) -> bool:
    lines = [
        line.strip()
        for line in value.splitlines()
        if line.strip() and not line.lstrip().startswith(("#", "!"))
    ]
    return bool(lines) and any("=" in line for line in lines)
