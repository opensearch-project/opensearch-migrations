import { CommonModule } from "@angular/common";
import { Component, Input, OnChanges } from "@angular/core";
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
} from "@angular/forms";
import type { ConfigControl, TreeNode } from "@manage-spike/shared";

@Component({
  selector: "app-config-field",
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  template: `
    @if (control; as field) {
      @switch (field.kind) {
        @case ("text") {
          <input
            class="field-input"
            type="text"
            [formControl]="textControl"
            [placeholder]="field.placeholder ?? ''"
            [attr.aria-label]="node.label"
          >
        }
        @case ("number") {
          <input
            class="field-input field-input--number"
            type="number"
            [formControl]="numberControl"
            [min]="field.minimum ?? null"
            [max]="field.maximum ?? null"
            [attr.aria-label]="node.label"
          >
        }
        @case ("boolean") {
          <label class="switch-control">
            <input type="checkbox" [formControl]="booleanControl">
            <span class="switch-control__track" aria-hidden="true">
              <span class="switch-control__thumb"></span>
            </span>
            <span>{{ booleanControl.value ? "Enabled" : "Disabled" }}</span>
          </label>
        }
        @case ("enum") {
          <select
            class="field-input"
            [formControl]="enumControl"
            [attr.aria-label]="node.label"
          >
            @for (option of field.options; track option.value) {
              <option [value]="option.value">{{ option.label }}</option>
            }
          </select>
        }
        @case ("config-map-key") {
          <div class="reference-control" [formGroup]="referenceGroup">
            <label>
              <span>ConfigMap</span>
              <select
                class="field-input"
                formControlName="configMap"
                (change)="onConfigMapChange(field)"
              >
                @for (option of field.options; track option.name) {
                  <option [value]="option.name">{{ option.name }}</option>
                }
              </select>
            </label>
            <span class="reference-control__separator" aria-hidden="true">/</span>
            <label>
              <span>Key</span>
              <select class="field-input" formControlName="key">
                @for (key of keysForSelectedMap(field); track key) {
                  <option [value]="key">{{ key }}</option>
                }
              </select>
            </label>
          </div>
        }
      }
    }
  `,
  styleUrl: "./config-field.component.css",
})
export class ConfigFieldComponent implements OnChanges {
  @Input({ required: true }) node!: TreeNode;

  readonly textControl = new FormControl("", { nonNullable: true });
  readonly numberControl = new FormControl(0, { nonNullable: true });
  readonly booleanControl = new FormControl(false, { nonNullable: true });
  readonly enumControl = new FormControl("", { nonNullable: true });
  readonly referenceGroup = new FormGroup({
    configMap: new FormControl("", { nonNullable: true }),
    key: new FormControl("", { nonNullable: true }),
  });

  get control(): ConfigControl | undefined {
    return this.node.configControl;
  }

  ngOnChanges(): void {
    const control = this.node.configControl;
    if (!control) {
      return;
    }

    switch (control.kind) {
      case "text":
        this.textControl.setValue(control.value, { emitEvent: false });
        break;
      case "number":
        this.numberControl.setValue(control.value, { emitEvent: false });
        break;
      case "boolean":
        this.booleanControl.setValue(control.value, { emitEvent: false });
        break;
      case "enum":
        this.enumControl.setValue(control.value, { emitEvent: false });
        break;
      case "config-map-key":
        this.referenceGroup.setValue(
          { configMap: control.configMap, key: control.key },
          { emitEvent: false },
        );
        break;
    }
  }

  onConfigMapChange(control: Extract<ConfigControl, { kind: "config-map-key" }>): void {
    const firstKey = this.keysForSelectedMap(control)[0] ?? "";
    this.referenceGroup.controls.key.setValue(firstKey);
  }

  keysForSelectedMap(
    control: Extract<ConfigControl, { kind: "config-map-key" }>,
  ): ReadonlyArray<string> {
    const selectedMap = this.referenceGroup.controls.configMap.value;
    return control.options.find((option) => option.name === selectedMap)?.keys ?? [];
  }
}
