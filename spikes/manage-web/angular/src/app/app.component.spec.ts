import { importProvidersFrom } from "@angular/core";
import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  flush,
  tick,
} from "@angular/core/testing";
import { LucideAngularModule } from "lucide-angular";

import { AppComponent, MANAGE_ICONS } from "./app.component";

describe("AppComponent partial tree updates", () => {
  let fixture: ComponentFixture<AppComponent>;
  let component: AppComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        importProvidersFrom(LucideAngularModule.pick(MANAGE_ICONS)),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AppComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
  });

  it("retains exact row DOM identity and focus across an unrelated status patch", () => {
    const rowBefore = row("resource-proxy");
    rowBefore.focus();
    expect(document.activeElement).toBe(rowBefore);

    component.simulateStatusRefresh();
    fixture.detectChanges();

    const rowAfter = row("resource-proxy");
    expect(rowAfter).toBe(rowBefore);
    expect(document.activeElement).toBe(rowBefore);
    expect(component.selectedId()).toBe("resource-proxy");
  });

  it("inserts edit rows without replacing the selected row or expansion state", fakeAsync(() => {
    const rowBefore = row("resource-proxy");
    rowBefore.focus();
    const originalExpanded = new Set(component.expandedIds());

    component.enterEditMode();
    tick(400);
    fixture.detectChanges();

    const rowAfter = row("resource-proxy");
    expect(rowAfter).toBe(rowBefore);
    expect(document.activeElement).toBe(rowBefore);
    expect(component.selectedId()).toBe("resource-proxy");
    originalExpanded.forEach((nodeId) => {
      expect(component.expandedIds().has(nodeId)).toBeTrue();
    });
    expect(row("config-proxy")).toBeTruthy();
    expect(row("config-listen-port").classList).toContain("tree-row--new");
    flush();
  }));

  it("adds a transform branch while preserving a focused unrelated row", fakeAsync(() => {
    component.enterEditMode();
    tick(400);
    fixture.detectChanges();
    const rowBefore = row("resource-proxy");
    rowBefore.focus();

    component.addTransform();
    fixture.detectChanges();

    expect(row("resource-proxy")).toBe(rowBefore);
    expect(document.activeElement).toBe(rowBefore);
    expect(row("config-transform")).toBeTruthy();
    expect(row("config-transform-file").classList).toContain("tree-row--new");
    flush();
  }));

  function row(nodeId: string): HTMLElement {
    const element = fixture.nativeElement.querySelector(
      `[data-node-id="${nodeId}"]`,
    ) as HTMLElement | null;
    if (!element) {
      throw new Error(`Expected row ${nodeId} to be rendered`);
    }
    return element;
  }
});
