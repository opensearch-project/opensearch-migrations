import { importProvidersFrom } from "@angular/core";
import { bootstrapApplication } from "@angular/platform-browser";
import { LucideAngularModule } from "lucide-angular";

import { AppComponent, MANAGE_ICONS } from "./app/app.component";

bootstrapApplication(AppComponent, {
  providers: [
    importProvidersFrom(LucideAngularModule.pick(MANAGE_ICONS)),
  ],
}).catch((error: unknown) => {
  console.error(error);
});
